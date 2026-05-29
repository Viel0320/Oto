package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.media.manifest.HeuristicAudioAggregator
import com.viel.aplayer.media.manifest.ManifestSidecarSupport
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.library.vfs.VfsFileInterface

/**
 * 启发式智能聚类分步类。
 * 
 * 本类已被重构，去除了原有的泛型接口 ImportStep<I, O> 和 StepResult 密封类包装。
 * 现在 execute 方法直接返回具体的 GroupedBookDrafts 结果，遇到异常自然向上抛出。
 */
internal class HeuristicGroupStep(private val appContext: Context) {

    /**
     * 执行聚类分步逻辑。直接接收 ResolvedMetadataDrafts 并返回 GroupedBookDrafts 结果，不再包装在 StepResult 中。
     */
    suspend fun execute(
        input: ResolvedMetadataDrafts,
        context: ImportContext
    ): GroupedBookDrafts {
        val aggregatedPlans = mutableListOf<HeuristicAggregationPlan>()
        val singleAudios = mutableListOf<AudioMetadataRef>()
        val inventory = context.sharedInventory
        // 复用从 ImportContext 传入的统一会话级 VFS 读取门面，避免多处自构造成额外性能开销
        val fileReader = context.scopeFileReader

        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        suspend fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (HeuristicAudioAggregator.shouldAggregate(pendingHeuristic)) {
                // 如果满足启发式合并条件（如共享相同的 album 或者文件名呈数字递增关系），则构造成一本书 the plan
                val first = pendingHeuristic.first()
                val plan = HeuristicAudioAggregator.buildPlan(
                    files = pendingHeuristic.toList(),
                    directoryContext = directoryContextFor(inventory, first.file.parentSourceKey),
                    openTextFile = { textFile -> fileReader?.open(textFile) }
                )
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
                if (last != null && last.file.parentSourceKey != audioRef.file.parentSourceKey) {
                    flushHeuristic()
                }
                pendingHeuristic.add(audioRef)
            }
        }
        flushHeuristic()

        return GroupedBookDrafts(
            manifestParsedResult = input.manifestParsedResult,
            aggregatedPlans = aggregatedPlans,
            singleAudios = singleAudios
        )
    }

    private fun directoryContextFor(input: FileInventory?, parentKey: String): ManifestSidecarSupport.DirectoryContext =
        ManifestSidecarSupport.DirectoryContext(
            imageFiles = input?.imageFilesByParent?.get(parentKey).orEmpty(),
            textFiles = input?.textFilesByParent?.get(parentKey).orEmpty()
        )
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
