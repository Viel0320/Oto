package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.viel.aplayer.library.AudioMetadataRef
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.HeuristicAggregationPlan
import com.viel.aplayer.media.CoverExtractor
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * 封面提取与 dominant 调色板背景色提取分步物理类
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本工位专门负责多级封面提取。引入协程信号量（Semaphore）进行高并发限流，
 * 避免因为同时启动过多的 MediaMetadataRetriever 图片解码导致爆内存与发烫。
 * 本工位会产出已经绑定好 bookId 和 CoverResult 的有声书信息。
 */
// 详尽的中文注释：声明类可见性为 internal，防止其泄漏模块内使用 internal 标记的实体对象导致编译错误
internal class CoverExtractStep(
    private val context: Context,
    private val coverExtractor: CoverExtractor = CoverExtractor(context),
    private val maxConcurrent: Int = 4
) : ImportStep<GroupedBookDrafts, CoverExtractedResult> {

    override val stepName: String = "CoverExtractStep"
    
    // 初始化并发度限制信号量
    private val semaphore = Semaphore(maxConcurrent)

    override suspend fun execute(
        input: GroupedBookDrafts,
        context: ImportContext
    ): StepResult<CoverExtractedResult> = runCatching {
        // 详尽的中文注释：FileInventory 无默认无参构造函数，使用显式 5 参数空构造进行降级保护
        val inventory = context.sharedInventory ?: FileInventory(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        // 1. 解析 CUE 封面的书籍列表
        val cueBooks = input.manifestParsedResult.cueDrafts.map { cueDraft ->
            val bookId = UUID.randomUUID().toString()
            // 挑出所有的关联物理文件
            val audioRefs = cueDraft.resolvedAudioUris.values.mapNotNull { uri ->
                inventory.audioFiles.firstOrNull { it.uri == uri }
            }
            val coverResult = resolveCoverWithSemaphore(bookId, primaryAudio = null, manifestFile = cueDraft.sourceFile, fallbackAudio = audioRefs, inventory = inventory)
            CoverExtractedCue(bookId, cueDraft, audioRefs, coverResult)
        }

        // 2. 解析 M3U8 封面的书籍列表
        val m3u8Books = input.manifestParsedResult.m3u8Drafts.map { m3u8Draft ->
            val bookId = UUID.randomUUID().toString()
            val audioRefs = m3u8Draft.resolvedAudioUris.values.mapNotNull { uri ->
                inventory.audioFiles.firstOrNull { it.uri == uri }
            }
            val coverResult = resolveCoverWithSemaphore(bookId, primaryAudio = null, manifestFile = m3u8Draft.sourceFile, fallbackAudio = audioRefs, inventory = inventory)
            CoverExtractedM3u8(bookId, m3u8Draft, audioRefs, coverResult)
        }

        // 3. 解析启发式聚合有声书封面
        val aggregatedBooks = input.aggregatedPlans.map { plan ->
            val bookId = UUID.randomUUID().toString()
            val orderedFiles = plan.chapters.map { it.audio }
            val coverResult = resolveCoverWithSemaphore(
                bookId = bookId,
                primaryAudio = orderedFiles.firstOrNull()?.file,
                manifestFile = null,
                fallbackAudio = orderedFiles.drop(1).map { it.file },
                inventory = inventory
            )
            CoverExtractedAggregated(bookId, plan, coverResult)
        }

        // 4. 解析单音频有声书封面
        val singleBooks = input.singleAudios.map { audioRef ->
            val bookId = UUID.randomUUID().toString()
            val coverResult = resolveCoverWithSemaphore(
                bookId = bookId,
                primaryAudio = audioRef.file,
                manifestFile = null,
                fallbackAudio = emptyList(),
                inventory = inventory
            )
            CoverExtractedSingle(bookId, audioRef, coverResult)
        }

        StepResult.Success(CoverExtractedResult(cueBooks, m3u8Books, aggregatedBooks, singleBooks))
    }.getOrElse { e ->
        StepResult.Failure(e, "多线程提取有声书封面发生异常，详情: ${e.localizedMessage}")
    }

    private suspend fun resolveCoverWithSemaphore(
        bookId: String,
        primaryAudio: FileRef?,
        manifestFile: FileRef?,
        fallbackAudio: List<FileRef>,
        inventory: FileInventory
    ): CoverExtractor.CoverResult? {
        // 1. 首先尝试内嵌的封面
        primaryAudio?.let { audio ->
            extractCoverSafety(audio.uri, bookId)?.takeIf { it.hasImage }?.let { return it }
        }
        
        // 2. 然后尝试同级目录下的外部 sidecar 图像
        val sidecarAnchor = manifestFile ?: primaryAudio ?: fallbackAudio.firstOrNull()
        sidecarAnchor?.let { anchor ->
            findDirectoryCover(anchor.parentUri, inventory)?.let { image ->
                coverExtractor.processExternalImage(Uri.parse(image.uri))?.takeIf { it.hasImage }?.let { return it }
            }
        }
        
        // 3. 最后退而求其次尝试其余音频的封面
        fallbackAudio.forEach { audio ->
            extractCoverSafety(audio.uri, bookId)?.takeIf { it.hasImage }?.let { return it }
        }
        return null
    }

    private val CoverExtractor.CoverResult.hasImage: Boolean
        get() = originalPath != null || thumbnailPath != null

    private fun findDirectoryCover(parentUri: String, inventory: FileInventory): FileRef? {
        val images = inventory.imageFilesByParent[parentUri].orEmpty()
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        return images.firstOrNull { image ->
            val baseName = image.displayName.substringBeforeLast('.').lowercase()
            priorityNames.contains(baseName)
        } ?: images.firstOrNull()
    }

    private suspend fun extractCoverSafety(uri: String, bookId: String): CoverExtractor.CoverResult? =
        runCatching {
            // 使用协程信号量进行高并发度安全并发控制，防发热和 OOM 崩溃
            semaphore.withPermit {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, Uri.parse(uri))
                    coverExtractor.extractFromRetriever(retriever, bookId)
                } finally {
                    retriever.release()
                }
            }
        }.getOrNull()
}

/**
 * 承载被提取封面有声书的统一大实体
 *
 * 详尽的中文注释：添加 internal 修饰符，收紧其内部使用范围，彻底消灭 public 泄漏模块内 internal 实体的编译错误
 */
internal data class CoverExtractedResult(
    val cueBooks: List<CoverExtractedCue>,
    val m3u8Books: List<CoverExtractedM3u8>,
    val aggregatedBooks: List<CoverExtractedAggregated>,
    val singleBooks: List<CoverExtractedSingle>
)

internal data class CoverExtractedCue(
    val bookId: String,
    val draft: ParsedCueDraft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

internal data class CoverExtractedM3u8(
    val bookId: String,
    val draft: ParsedM3u8Draft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

internal data class CoverExtractedAggregated(
    val bookId: String,
    val plan: HeuristicAggregationPlan,
    val coverResult: CoverExtractor.CoverResult?
)

internal data class CoverExtractedSingle(
    val bookId: String,
    val audioRef: AudioMetadataRef,
    val coverResult: CoverExtractor.CoverResult?
)
