package com.viel.aplayer.library.orchestrator

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedAggregated
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedCue
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedM3u8
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.draftmodels.CoverExtractedSingle
import com.viel.aplayer.library.orchestrator.draftmodels.ImportRunResult
import com.viel.aplayer.library.orchestrator.steps.GroupedBookDrafts
import com.viel.aplayer.library.orchestrator.steps.HeuristicGroupStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParseStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParsedResult
import com.viel.aplayer.library.orchestrator.steps.MetadataResolveStep
import com.viel.aplayer.library.orchestrator.steps.OwnershipClaimStep
import com.viel.aplayer.library.orchestrator.steps.ResolvedMetadataDrafts
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.logger.ImportTimingLogger
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.MetadataResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 扫描导入大调度器（流水线委托入口）
 *
 * 【重要重构说明】：
 * 本类原为 800 余行的单体面条类，经过解耦重构后，其内部所有的业务逻辑均已被
 * 物理切分到了 `orchestrator/steps` 包下的各个独立工位 Step 类中。
 * 本类现在仅作为对外的向下兼容网关，负责将原有的 run 方法桥接并有序调用新的流水线步骤，
 * 既保证了 ScanSessionRunner 不受影响，又彻底实现了物理结构的解耦！
 */
@OptIn(UnstableApi::class)
class ImportPipeline(
    private val context: Context,
    // 强制从外部注入元数据标签解析提取器，消除自构 VFS 的失效默认值
    metadataResolver: MetadataResolver
) {
    // 实例化拆分出的具体工位步骤，实现单一职责
    private val manifestParseStep = ManifestParseStep(context)
    private val metadataResolveStep = MetadataResolveStep(context, metadataResolver)
    private val heuristicGroupStep = HeuristicGroupStep(context)
    private val draftFactory = BookDraftFactory(metadataResolver)
    private val ownershipClaimStep = OwnershipClaimStep(context, draftFactory)
    // 导入阶段只把元数据流已经读到的封面字节写入缓存，不重新打开音频做封面解析。
    private val coverExtractor = CoverExtractor(context)

    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger = RunClaimLedger(),
        timingScopeId: String = "inventory:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {

        // 1. 初始化统一导入上下文，将 inventory 缓存入内，并构建 session 会话级唯一的 VFS 文件读取快照门面
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // 由 ScopeOrchestrator 注入跨 scope 的 claim ledger 副本，保证即时入库后仍能维持整轮扫描级 claim 规则。
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        // 2. 执行工位一：清单文件深度物理解析 (CUE/M3U8)
        val manifestParsedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.manifestParse",
            detail = inventory.timingCountDetail()
        ) {
            // 清单文件物理深度解析，直接执行并接收结果
            manifestParseStep.execute(inventory, importCtx)
        }

        // 3. 执行工位二：音轨 ID3 元数据提取
        val resolvedMetadataDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.metadataResolve",
            detail = "audio=${inventory.audioFiles.size}"
        ) {
            // 散落音频 ID3 元数据解析，直接执行并接收结果
            metadataResolveStep.execute(manifestParsedResult, importCtx)
        }

        // 4. 执行工位三：启发式分类与智能聚类
        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${resolvedMetadataDrafts.looseAudioMetadataRefs.size}"
        ) {
            // 启发式聚类分步计算，直接执行并接收结果
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        // 5. 执行工位四：导入阶段不再重新解码音频封面，只复用元数据阶段预读的 covr；轻量级缺失时仍交给 CoverRecoveryHelper 异步重建。
        // 封面阶段仍不重新解析音频，只把元数据阶段预读到的 covr 写入缓存；没有预读封面的书继续走原恢复路径。
        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "cue=${groupedBookDrafts.manifestParsedResult.cueDrafts.size} m3u8=${groupedBookDrafts.manifestParsedResult.m3u8Drafts.size} aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory, scopeVfs)
        }

        // 6. 执行工位五：冲突认领决策与 Draft 最终组装
        val claimDecidedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "cue=${coverExtractedResult.cueBooks.size} m3u8=${coverExtractedResult.m3u8Books.size} aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            // 冲突所有权认领决策，直接执行并接收结果
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }

        // 7. 返回完美的 ImportRunResult 实例
        claimDecidedResult
    }

    // 复用已经预读取好的目录音频元数据，避免为“有章节音频提前入库”再重复扫描同一批 ID3/章节元数据。
    internal suspend fun runWithPreResolvedAudio(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger,
        looseAudioMetadataRefs: List<AudioMetadataRef>,
        timingScopeId: String = "directory-audio:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {
        // 构建子批次会话级唯一的 VFS 文件读取快照门面，统一物理操作入口
        val scopeVfs = VfsFileInterface(context.applicationContext, rootsById = inventory.roots.associateBy { it.id })
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // 目录内分流后的子批次仍使用调用方提供的 scope ledger，保证 claim 提交节奏由 DirectoryAudioImporter 控制。
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory,
            scopeFileReader = scopeVfs
        )

        // 目录剩余音频没有 CUE/M3U8 清单输入，直接从预读取音频元数据进入启发式分组步骤。
        val resolvedMetadataDrafts = ResolvedMetadataDrafts(
            manifestParsedResult = ManifestParsedResult(
                cueDrafts = emptyList(),
                m3u8Drafts = emptyList()
            ),
            looseAudioMetadataRefs = looseAudioMetadataRefs
        )

        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${looseAudioMetadataRefs.size}"
        ) {
            // 启发式聚类分步计算，直接执行并接收结果
            heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)
        }

        // 目录音频子批次同样只消费 AudioMetadataRef 中的预读封面，避免刚解析完元数据又二次读取 MP4。
        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory, scopeVfs)
        }

        ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            // 冲突所有权认领决策，直接执行并接收结果
            ownershipClaimStep.execute(coverExtractedResult, importCtx)
        }
    }

    // 把 inventory 规模压缩成统一日志字段，方便横向比较不同 scope 的解析耗时。
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // 封面载体仍复用 CoverExtractedResult；这里只绑定预读封面，绑定不到时保持 null 让原恢复路径兜底。
    private suspend fun GroupedBookDrafts.toDeferredCoverResult(
        inventory: FileInventory,
        fileReader: VfsFileInterface
    ): CoverExtractedResult {
        // manifest 解析结果按 VFS 文件键回查扫描音频，彻底切掉旧 URI 关联方式。
        val audioByVfsKey = inventory.audioFiles.associateBy { it.vfsKey }
        return CoverExtractedResult(
            cueBooks = manifestParsedResult.cueDrafts.map { cueDraft ->
                // Manifest 书籍的音频引用仍从当前 scope inventory 映射，保证 claim 使用的音频列表不依赖封面解析步骤。
                val audioRefs =
                    cueDraft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
                val coverResult = cueDraft.result.sidecarCoverFile?.let { sidecarFile ->
                    // manifest parser 已经选出同目录最合适的 sidecover 候选，
                    // 这里不再自己做第二套目录图片优先级判断，只负责把 parser 结果落成缓存封面。
                    saveExternalSidecarCover(sidecarFile, fileReader)
                }
                CoverExtractedCue(
                    UUID.randomUUID().toString(),
                    cueDraft,
                    audioRefs,
                    coverResult = coverResult
                )
            },
            m3u8Books = manifestParsedResult.m3u8Drafts.map { m3u8Draft ->
                // M3U8 书籍同样保留解析到的音频文件列表，只延迟封面生成，不延迟 claim 和章节入库。
                val audioRefs =
                    m3u8Draft.resolvedAudioKeys.values.mapNotNull { key -> audioByVfsKey[key] }
                val coverResult = m3u8Draft.result.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                }
                CoverExtractedM3u8(
                    UUID.randomUUID().toString(),
                    m3u8Draft,
                    audioRefs,
                    coverResult = coverResult
                )
            },
            aggregatedBooks = aggregatedPlans.map { plan ->
                // 启发式聚合书籍先尝试写入预读封面，失败才以空封面入库并等待恢复流程补齐。
                val bookId = UUID.randomUUID().toString()
                // 启发式聚合书现在也先尊重 parser 内部选出的 sidecover；
                // 如果没有 sidecover，再退回到首个可用的内嵌封面字节。
                val coverResult = plan.sidecarCoverFile?.let { sidecarFile ->
                    saveExternalSidecarCover(sidecarFile, fileReader)
                } ?: savePreReadEmbeddedCover(plan.chapters.map { it.audio })
                CoverExtractedAggregated(bookId, plan, coverResult)
            },
            singleBooks = singleAudios.map { audioRef ->
                // 单音频书籍不再二次打开文件取封面，只消费元数据阶段已经带出的 covr。
                val bookId = UUID.randomUUID().toString()
                // 单文件书直接复用该音频的预读 covr；若没有封面则保持 null，原封面恢复路径不变。
                val coverResult = savePreReadEmbeddedCover(listOf(audioRef))
                CoverExtractedSingle(bookId, audioRef, coverResult)
            }
        )
    }

    // 复用已经传入的统一 VfsFileInterface 实例，避免重复多次构建 VFS 引起开销
    private suspend fun saveExternalSidecarCover(sidecarFile: FileRef, fileReader: VfsFileInterface): CoverExtractor.CoverResult? {
        val result = coverExtractor.processExternalImage(sidecarFile.vfsKey) { fileReader.open(sidecarFile) }
        return result.takeIf { it.hasImage() }
    }

    private suspend fun savePreReadEmbeddedCover(audioRefs: List<AudioMetadataRef>): CoverExtractor.CoverResult? {
        // 只消费 AudioMetadataRef 中已经随元数据带出的封面字节，不在这里重新通过 VFS 打开音频文件。
        for (audioRef in audioRefs) {
            val cover = audioRef.embeddedCover ?: continue
            val result = coverExtractor.saveEmbeddedImage("${audioRef.file.vfsKey}:covr", cover.bytes)
            if (result.hasImage()) return result
        }
        return null
    }

    private fun CoverExtractor.CoverResult.hasImage(): Boolean =
        // 封面缓存只要原图或缩略图任一路径写入成功，就可阻止后续恢复流程做重复工作。
        originalPath != null || thumbnailPath != null
}