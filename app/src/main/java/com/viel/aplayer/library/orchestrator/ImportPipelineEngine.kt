package com.viel.aplayer.library.orchestrator

import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.orchestrator.steps.ConflictClaimStep
import com.viel.aplayer.library.orchestrator.steps.CoverExtractStep
import com.viel.aplayer.library.orchestrator.steps.HeuristicGroupStep
import com.viel.aplayer.library.orchestrator.steps.InventoryScanStep
import com.viel.aplayer.library.orchestrator.steps.ManifestParseStep
import com.viel.aplayer.library.orchestrator.steps.MetadataResolveStep

/**
 * 流水线任务组合与编排引擎调度器
 * 
 * 本引擎持有并组合了拆分出来的 6 个流水线工位物理类（扫描、解析、元数据、聚类、封面、冲突），
 * 负责定义其顺序与挂载冷流（Flow），为前端的进度条提供精细化的百分比和状态显示。
 */
@UnstableApi
internal class ImportPipelineEngine(
    private val inventoryScanStep: InventoryScanStep,
    private val manifestParseStep: ManifestParseStep,
    private val metadataResolveStep: MetadataResolveStep,
    private val heuristicGroupStep: HeuristicGroupStep,
    private val coverExtractStep: CoverExtractStep,
    private val conflictClaimStep: ConflictClaimStep
) {
    /**
     * 运行完整的扫描导入流水线
     * 
     * @param roots 有声书物理扫描根目录实体列表
     * @param scanSessionId 会话ID
     * @param existingClaimIndex 已有书籍数据库认领快照索引
     * @return Flow<PipelineState> 返回一个冷流，实时向外界汇报百分比进度与最终落库结果
     */
    fun runImportPipeline(
        roots: List<LibraryRootEntity>,
        scanSessionId: String,
        existingClaimIndex: ExistingClaimIndex
    ): Flow<PipelineState> = flow {
        // 1. 发射初始化状态
        emit(PipelineState.Progress(statusText = "正在初始化扫描上下文...", percentage = 5))

        val context = ImportContext(
            scanId = scanSessionId,
            existingClaimIndex = existingClaimIndex
        )

        // 2. 切换至 IO 线程池，开始跑流水线
        withContext(Dispatchers.IO) {

            // Step 1: 扫描物理文件
            emit(PipelineState.Progress(statusText = "正在物理扫描目录树...", percentage = 15))
            val scanResult = when (val res = inventoryScanStep.execute(roots, context)) {
                is StepResult.Success -> {
                    // 将物理扫描出的 inventory 存入上下文缓存，供后续步骤读取
                    context.sharedInventory = res.data
                    res.data
                }
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 2: 清单文件解析 (.cue / .m3u8)
            emit(PipelineState.Progress(statusText = "正在深度解析 CUE 与 M3U8 清单...", percentage = 30))
            val manifestResult = when (val res = manifestParseStep.execute(scanResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 3: 音频元数据 ID3 并发解析步骤（此处调整顺序为聚类之前，以便HeuristicGroupStep有ID3信息可用）
            emit(PipelineState.Progress(statusText = "正在多线程提取音轨媒体元数据 (ID3)...", percentage = 50))
            val metadataResult = when (val res = metadataResolveStep.execute(manifestResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 4: 音频分段智能聚类步骤
            emit(PipelineState.Progress(statusText = "正在根据文件名进行启发式智能分类聚合...", percentage = 70))
            val groupedResult = when (val res = heuristicGroupStep.execute(metadataResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 5: 并发封面提取与调色板取色步骤
            emit(PipelineState.Progress(statusText = "正在抓取封面图像并生成调色板取色...", percentage = 85))
            val coverResult = when (val res = coverExtractStep.execute(groupedResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // Step 6: 解决数据库所有权冲突与认领，产出最终落库的 ImportRunResult
            emit(PipelineState.Progress(statusText = "正在进行冲突裁决与所有权认领决策...", percentage = 95))
            val claimResult = when (val res = conflictClaimStep.execute(coverResult, context)) {
                is StepResult.Success -> res.data
                is StepResult.Failure -> {
                    emit(PipelineState.Failed(res.errorMessage)); return@withContext
                }
            }

            // 发射扫描完成，将最终的 ImportRunResult 吐出
            emit(PipelineState.Completed(result = claimResult))
        }
    }
}

/**
 * 流水线整体运行状态的密封接口
 */
internal sealed interface PipelineState {
    // 进度汇报状态，包含通俗易懂的中文文字描述和 0-100 的百分比数值
    data class Progress(val statusText: String, val percentage: Int) : PipelineState

    // 整体流水线执行完毕，携带最终落库的书籍元数据汇总结果
    data class Completed(val result: ImportRunResult) : PipelineState

    // 发生严重不可逆异常，直接中断并返回中文错误信息
    data class Failed(val errorMsg: String) : PipelineState
}