package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.library.orchestrator.mapWithBoundedConcurrency
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import java.util.UUID
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 旧 ImportPipelineEngine 使用的封面提取步骤。
 *
 * 这里也同步收口成“只向 MetadataResolver 要内嵌封面字节”，
 * 不再在步骤层直接区分 MP4 / 非 MP4，也不直接调用任何容器专属封面解析器。
 */
@UnstableApi
internal class CoverExtractStep(
    private val context: Context,
    // 详尽的中文注释：添加虚拟文件系统物理 I/O 读取通道接口的注入
    private val vfsFileInterface: VfsFileInterface,
    private val coverExtractor: CoverExtractor = CoverExtractor(context),
    private val maxConcurrent: Int = 4
) : ImportStep<GroupedBookDrafts, CoverExtractedResult> {

    override val stepName: String = "CoverExtractStep"
    private val semaphore = Semaphore(maxConcurrent)
    
    // 详尽的中文注释：物理封面图提取需配合 MetadataResolver 向各 parser 范围读取请求，
    // 注入 vfsFileInterface 实例进行规整重构，消除底层隐式自构行为。
    private val MetadataResolver = MetadataResolver(vfsFileInterface)

    override suspend fun execute(
        input: GroupedBookDrafts,
        context: ImportContext
    ): StepResult<CoverExtractedResult> = runCatching {
        val inventory = context.sharedInventory ?: FileInventory(
            roots = emptyList(),
            cueFiles = emptyList(),
            m3u8Files = emptyList(),
            audioFiles = emptyList(),
            imageFilesByParent = emptyMap(),
            // 空 inventory 兜底也要补齐 txt 侧车字段，
            // 保持 FileInventory 数据结构完整，避免旧步骤编译失败。
            textFilesByParent = emptyMap()
        )
        val audioByVfsKey = inventory.audioFiles.associateBy { it.vfsKey }

        val cueBooks = input.manifestParsedResult.cueDrafts.mapWithBoundedConcurrency(maxConcurrent) { cueDraft ->
            val bookId = UUID.randomUUID().toString()
            val audioRefs = cueDraft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
            val coverResult = resolveCoverWithSemaphore(
                bookId = bookId,
                primaryAudio = null,
                manifestFile = cueDraft.sourceFile,
                fallbackAudio = audioRefs,
                inventory = inventory
            )
            CoverExtractedCue(bookId, cueDraft, audioRefs, coverResult)
        }

        val m3u8Books = input.manifestParsedResult.m3u8Drafts.mapWithBoundedConcurrency(maxConcurrent) { m3u8Draft ->
            val bookId = UUID.randomUUID().toString()
            val audioRefs = m3u8Draft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
            val coverResult = resolveCoverWithSemaphore(
                bookId = bookId,
                primaryAudio = null,
                manifestFile = m3u8Draft.sourceFile,
                fallbackAudio = audioRefs,
                inventory = inventory
            )
            CoverExtractedM3u8(bookId, m3u8Draft, audioRefs, coverResult)
        }

        val aggregatedBooks = input.aggregatedPlans.mapWithBoundedConcurrency(maxConcurrent) { plan ->
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

        val singleBooks = input.singleAudios.mapWithBoundedConcurrency(maxConcurrent) { audioRef ->
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
    }.getOrElse { error ->
        StepResult.Failure(error, "多线程提取有声书封面发生异常，详情: ${error.localizedMessage}")
    }

    private suspend fun resolveCoverWithSemaphore(
        bookId: String,
        primaryAudio: FileRef?,
        manifestFile: FileRef?,
        fallbackAudio: List<FileRef>,
        inventory: FileInventory
    ): CoverExtractor.CoverResult? {
        val fileReader = VfsFileInterface(
            context.applicationContext,
            rootsById = inventory.roots.associateBy { it.id }
        )

        primaryAudio?.let { audio ->
            extractCoverSafety(audio, bookId)?.takeIf { it.hasImage }?.let { return it }
        }

        val sidecarAnchor = manifestFile ?: primaryAudio ?: fallbackAudio.firstOrNull()
        sidecarAnchor?.let { anchor ->
            findDirectoryCover(anchor.parentSourceKey, inventory)?.let { image ->
                coverExtractor.processExternalImage(image.vfsKey) { fileReader.open(image) }
                    .takeIf { it.hasImage }
                    ?.let { return it }
            }
        }

        fallbackAudio.forEach { audio ->
            extractCoverSafety(audio, bookId)?.takeIf { it.hasImage }?.let { return it }
        }
        return null
    }

    private suspend fun extractCoverSafety(
        file: FileRef,
        bookId: String
    ): CoverExtractor.CoverResult? =
        runCatching {
            semaphore.withPermit {
                val embeddedCover = MetadataResolver.extractWithEmbeddedCover(file).embeddedCover
                if (embeddedCover == null || embeddedCover.bytes.isEmpty()) {
                    CoverExtractor.CoverResult(null, null)
                } else {
                    // parser 已经在内部完成容器相关的封面提取，
                    // 这个步骤只负责把统一返回的字节落缓存。
                    coverExtractor.saveEmbeddedImage("$bookId:${file.vfsKey}:embedded", embeddedCover.bytes)
                }
            }
        }.getOrNull()

    private val CoverExtractor.CoverResult.hasImage: Boolean
        get() = originalPath != null || thumbnailPath != null

    private fun findDirectoryCover(parentKey: String, inventory: FileInventory): FileRef? {
        val images = inventory.imageFilesByParent[parentKey].orEmpty()
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        return images.firstOrNull { image ->
            val baseName = image.displayName.substringBeforeLast('.').lowercase()
            baseName in priorityNames
        } ?: images.firstOrNull()
    }
}

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
