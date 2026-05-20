package com.viel.aplayer.library.orchestrator.steps

import com.viel.aplayer.library.AudioMetadataRef
import com.viel.aplayer.library.HeuristicAggregationPlan
import com.viel.aplayer.library.HeuristicAudioAggregator
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.orchestrator.ImportStep
import com.viel.aplayer.library.orchestrator.StepResult

/**
 * 启发式智能聚类分步类
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本工位接收已提取元数据的散落音频，按照文件夹边界以及 HeuristicAudioAggregator.shouldAggregate
 * 的逻辑对其进行智能分类聚合，决定它们是汇编成一部“聚合有声书”，还是分别作为“单音频独立有声书”。
 */
internal class HeuristicGroupStep : ImportStep<ResolvedMetadataDrafts, GroupedBookDrafts> {

    override val stepName: String = "HeuristicGroupStep"

    override suspend fun execute(
        input: ResolvedMetadataDrafts,
        context: ImportContext
    ): StepResult<GroupedBookDrafts> = runCatching {
        val aggregatedPlans = mutableListOf<HeuristicAggregationPlan>()
        val singleAudios = mutableListOf<AudioMetadataRef>()

        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (HeuristicAudioAggregator.shouldAggregate(pendingHeuristic)) {
                // 如果满足启发式合并条件（如共享相同的 album 或者文件名呈数字递增关系），则构造成一本书的 plan
                val plan = HeuristicAudioAggregator.buildPlan(pendingHeuristic)
                aggregatedPlans.add(plan)
            } else {
                // 否则，拆散作为单本有声书
                singleAudios.addAll(pendingHeuristic)
            }
            pendingHeuristic.clear()
        }

        // 顺序对散落音频进行智能聚合分类
        input.looseAudioMetadataRefs.forEach { audioRef ->
            if (audioRef.metadata.chapters.isNotEmpty()) {
                // 如果音频自身就内嵌了章节信息，直接作为独立单曲，先冲刷掉当前的缓冲池
                flushHeuristic()
                singleAudios.add(audioRef)
            } else {
                val last = pendingHeuristic.lastOrNull()
                // 如果音频跨越了不同的文件夹，则同样把前一个文件夹的内容先冲刷处理掉
                if (last != null && last.file.parentUri != audioRef.file.parentUri) {
                    flushHeuristic()
                }
                pendingHeuristic.add(audioRef)
            }
        }
        flushHeuristic()

        StepResult.Success(GroupedBookDrafts(
            manifestParsedResult = input.manifestParsedResult,
            aggregatedPlans = aggregatedPlans,
            singleAudios = singleAudios
        ))
    }.getOrElse { e ->
        StepResult.Failure(e, "启发式分类聚合处理失败，详情: ${e.localizedMessage}")
    }
}

/**
 * 承载聚类分类结果的实体类
 */
internal data class GroupedBookDrafts(
    // 往下透传清单解析数据，为最终 Draft 汇总保留上下文
    val manifestParsedResult: ManifestParsedResult,
    
    // 启发式聚合计划
    val aggregatedPlans: List<HeuristicAggregationPlan>,
    
    // 独立单音频列表
    val singleAudios: List<AudioMetadataRef>
)
