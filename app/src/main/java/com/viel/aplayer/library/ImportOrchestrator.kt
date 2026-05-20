package com.viel.aplayer.library

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.StepResult
import com.viel.aplayer.library.orchestrator.steps.ConflictClaimStep
import com.viel.aplayer.library.orchestrator.steps.CoverExtractStep
import com.viel.aplayer.library.orchestrator.steps.HeuristicGroupStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParseStep
import com.viel.aplayer.library.orchestrator.steps.MetadataResolveStep
import com.viel.aplayer.media.parse.CoverExtractor
import com.viel.aplayer.media.parse.MetadataExtractor

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
    metadataExtractor: MetadataExtractor = MetadataExtractor(context),
    coverExtractor: CoverExtractor = CoverExtractor(context)
) {
    // 实例化拆分出的具体工位步骤，实现单一职责
    private val manifestParseStep = ManifestParseStep(context)
    private val metadataResolveStep = MetadataResolveStep(context, metadataExtractor)
    private val heuristicGroupStep = HeuristicGroupStep()
    private val coverExtractStep = CoverExtractStep(context, coverExtractor)
    private val conflictClaimStep = ConflictClaimStep(context, metadataExtractor)

    suspend fun run(
        scanId: String,
        inventory: FileInventory,
        existingClaimIndex: ExistingClaimIndex
    ): ImportRunResult = withContext(Dispatchers.IO) {
        
        // 1. 初始化统一导入上下文，将 inventory 缓存入内
        val importCtx = ImportContext(
            scanId = scanId,
            existingClaimIndex = existingClaimIndex,
            sharedInventory = inventory
        )

        // 2. 执行工位一：清单文件深度物理解析 (CUE/M3U8)
        val manifestParsedResult = when (val res = manifestParseStep.execute(inventory, importCtx)) {
            is StepResult.Success -> res.data
            is StepResult.Failure -> throw res.throwable
        }

        // 3. 执行工位二：音轨 ID3 元数据提取
        val resolvedMetadataDrafts = when (val res = metadataResolveStep.execute(manifestParsedResult, importCtx)) {
            is StepResult.Success -> res.data
            is StepResult.Failure -> throw res.throwable
        }

        // 4. 执行工位三：启发式分类与智能聚类
        val groupedBookDrafts = when (val res = heuristicGroupStep.execute(resolvedMetadataDrafts, importCtx)) {
            is StepResult.Success -> res.data
            is StepResult.Failure -> throw res.throwable
        }

        // 5. 执行工位四：音轨封面解码与 dominant 调色板背景色提取
        val coverExtractedResult = when (val res = coverExtractStep.execute(groupedBookDrafts, importCtx)) {
            is StepResult.Success -> res.data
            is StepResult.Failure -> throw res.throwable
        }

        // 6. 执行工位五：冲突认领决策与 Draft 最终组装
        val claimDecidedResult = when (val res = conflictClaimStep.execute(coverExtractedResult, importCtx)) {
            is StepResult.Success -> res.data
            is StepResult.Failure -> throw res.throwable
        }

        // 7. 返回完美的 ImportRunResult 实例
        claimDecidedResult
    }
}