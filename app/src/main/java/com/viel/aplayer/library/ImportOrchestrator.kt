package com.viel.aplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.orchestrator.steps.ConflictClaimStep
import com.viel.aplayer.library.orchestrator.steps.CoverExtractedAggregated
import com.viel.aplayer.library.orchestrator.steps.CoverExtractedCue
import com.viel.aplayer.library.orchestrator.steps.CoverExtractedM3u8
import com.viel.aplayer.library.orchestrator.steps.CoverExtractedResult
import com.viel.aplayer.library.orchestrator.steps.CoverExtractedSingle
import com.viel.aplayer.library.orchestrator.steps.GroupedBookDrafts
import com.viel.aplayer.library.orchestrator.steps.HeuristicGroupStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParsedResult
import com.viel.aplayer.library.orchestrator.steps.ManifestParseStep
import com.viel.aplayer.library.orchestrator.steps.MetadataResolveStep
import com.viel.aplayer.library.orchestrator.steps.ResolvedMetadataDrafts
import com.viel.aplayer.media.parse.MetadataExtractor
import java.util.UUID

/**
 * 扫描导入大调度器（流水线委托入口）
 * 
 * 为每一次改动添加详尽 of 中文注释：
 * 【重要重构说明】：
 * 本类原为 800 余行的单体面条类，经过解耦重构后，其内部所有的业务逻辑均已被
 * 物理切分到了 `orchestrator/steps` 包下的各个独立工位 Step 类中。
 * 本类现在仅作为对外的向下兼容网关，负责将原有的 run 方法桥接并有序调用新的流水线步骤，
 * 既保证了 RescanCoordinator 不受影响，又彻底实现了物理结构的解耦！
 */
class ImportOrchestrator(
    private val context: Context,
    metadataExtractor: MetadataExtractor = MetadataExtractor(context)
) {
    // 实例化拆分出的具体工位步骤，实现单一职责
    private val manifestParseStep = ManifestParseStep(context)
    private val metadataResolveStep = MetadataResolveStep(context, metadataExtractor)
    private val heuristicGroupStep = HeuristicGroupStep()
    private val conflictClaimStep = ConflictClaimStep(context, metadataExtractor)

    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger = RunClaimLedger(),
        timingScopeId: String = "inventory:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {
        
        // 1. 初始化统一导入上下文，将 inventory 缓存入内
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // 详尽的中文注释：由 RescanCoordinator 注入跨 scope 的 claim ledger 副本，保证即时入库后仍能维持整轮扫描级 claim 规则。
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory
        )

        // 2. 执行工位一：清单文件深度物理解析 (CUE/M3U8)
        val manifestParsedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.manifestParse",
            detail = inventory.timingCountDetail()
        ) {
            when (val res = manifestParseStep.execute(inventory, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }

        // 3. 执行工位二：音轨 ID3 元数据提取
        val resolvedMetadataDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.metadataResolve",
            detail = "audio=${inventory.audioFiles.size}"
        ) {
            when (val res = metadataResolveStep.execute(manifestParsedResult, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }

        // 4. 执行工位三：启发式分类与智能聚类
        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${resolvedMetadataDrafts.looseAudioMetadataRefs.size}"
        ) {
            when (val res = heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }

        // 5. 执行工位四：导入阶段不再同步解码封面，只生成带 bookId 的空封面载体；真正封面交给入库后的 CoverRecoveryHelper 异步重建。
        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "cue=${groupedBookDrafts.manifestParsedResult.cueDrafts.size} m3u8=${groupedBookDrafts.manifestParsedResult.m3u8Drafts.size} aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory)
        }

        // 6. 执行工位五：冲突认领决策与 Draft 最终组装
        val claimDecidedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "cue=${coverExtractedResult.cueBooks.size} m3u8=${coverExtractedResult.m3u8Books.size} aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            when (val res = conflictClaimStep.execute(coverExtractedResult, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }

        // 7. 返回完美的 ImportRunResult 实例
        claimDecidedResult
    }

    // 详尽的中文注释：复用已经预读取好的目录音频元数据，避免为“有章节音频提前入库”再重复扫描同一批 ID3/章节元数据。
    internal suspend fun runResolvedDirectoryAudio(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex,
        runClaimLedger: RunClaimLedger,
        looseAudioMetadataRefs: List<AudioMetadataRef>,
        timingScopeId: String = "directory-audio:$scanId"
    ): ImportRunResult = withContext(Dispatchers.IO) {
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            // 详尽的中文注释：目录内分流后的子批次仍使用调用方提供的 scope ledger，保证 claim 提交节奏由 RescanCoordinator 控制。
            runClaimLedger = runClaimLedger,
            sharedInventory = inventory
        )

        // 详尽的中文注释：目录剩余音频没有 CUE/M3U8 清单输入，直接从预读取音频元数据进入启发式分组步骤。
        val resolvedMetadataDrafts = ResolvedMetadataDrafts(
            manifestParsedResult = ManifestParsedResult(cueDrafts = emptyList(), m3u8Drafts = emptyList()),
            looseAudioMetadataRefs = looseAudioMetadataRefs
        )

        val groupedBookDrafts = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.heuristicGroup",
            detail = "loose=${looseAudioMetadataRefs.size}"
        ) {
            when (val res = heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }

        val coverExtractedResult = ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.coverDefer",
            detail = "aggregated=${groupedBookDrafts.aggregatedPlans.size} single=${groupedBookDrafts.singleAudios.size}"
        ) {
            groupedBookDrafts.toDeferredCoverResult(inventory)
        }

        ImportTimingLogger.measure(
            scopeId = timingScopeId,
            stage = "orchestrator.conflictClaim",
            detail = "aggregated=${coverExtractedResult.aggregatedBooks.size} single=${coverExtractedResult.singleBooks.size}"
        ) {
            when (val res = conflictClaimStep.execute(coverExtractedResult, importCtx)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> throw res.throwable
            }
        }
    }

    // 详尽的中文注释：把 inventory 规模压缩成统一日志字段，方便横向比较不同 scope 的解析耗时。
    private fun FileInventory.timingCountDetail(): String =
        "cue=${cueFiles.size} m3u8=${m3u8Files.size} audio=${audioFiles.size} imageParents=${imageFilesByParent.size}"

    // 详尽的中文注释：延迟封面模式仍复用原 CoverExtractedResult 数据结构，只把 coverResult 置空，让 ConflictClaimStep 可以不改草稿组装逻辑直接生成可入库书籍。
    private fun GroupedBookDrafts.toDeferredCoverResult(inventory: FileInventory): CoverExtractedResult {
        val audioByUri = inventory.audioFiles.associateBy { it.uri }
        return CoverExtractedResult(
            cueBooks = manifestParsedResult.cueDrafts.map { cueDraft ->
                // 详尽的中文注释：Manifest 书籍的音频引用仍从当前 scope inventory 映射，保证 claim 使用的音频列表不依赖封面解析步骤。
                val audioRefs = cueDraft.resolvedAudioUris.values.mapNotNull { uri -> audioByUri[uri] }
                CoverExtractedCue(UUID.randomUUID().toString(), cueDraft, audioRefs, coverResult = null)
            },
            m3u8Books = manifestParsedResult.m3u8Drafts.map { m3u8Draft ->
                // 详尽的中文注释：M3U8 书籍同样保留解析到的音频文件列表，只延迟封面生成，不延迟 claim 和章节入库。
                val audioRefs = m3u8Draft.resolvedAudioUris.values.mapNotNull { uri -> audioByUri[uri] }
                CoverExtractedM3u8(UUID.randomUUID().toString(), m3u8Draft, audioRefs, coverResult = null)
            },
            aggregatedBooks = aggregatedPlans.map { plan ->
                // 详尽的中文注释：启发式聚合书籍先以空封面入库，后续由 CoverRecoveryHelper 根据 BookFile 重新从首个音频或同目录 sidecar 生成缓存。
                CoverExtractedAggregated(UUID.randomUUID().toString(), plan, coverResult = null)
            },
            singleBooks = singleAudios.map { audioRef ->
                // 详尽的中文注释：单音频书籍先以空封面入库，避免每批导入被 MediaMetadataRetriever 封面解码阻塞。
                CoverExtractedSingle(UUID.randomUUID().toString(), audioRef, coverResult = null)
            }
        )
    }
}
