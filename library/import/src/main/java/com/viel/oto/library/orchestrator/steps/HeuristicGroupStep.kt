package com.viel.oto.library.orchestrator.steps

import android.content.Context
import com.viel.oto.library.FileInventory
import com.viel.oto.library.orchestrator.ImportContext
import com.viel.oto.media.manifest.AudioMetadataRef
import com.viel.oto.media.manifest.HeuristicAggregationPlan
import com.viel.oto.media.manifest.HeuristicAudioAggregator
import com.viel.oto.media.manifest.ManifestSidecarSupport

/**
 * Groups loose audio metadata into single-file books or heuristic multi-file book plans.
 */
internal class HeuristicGroupStep(private val appContext: Context) {

    /**
     * Processes metadata drafts and returns grouped book candidates for downstream ownership checks.
     */
    suspend fun execute(
        input: ResolvedMetadataDrafts,
        context: ImportContext
    ): GroupedBookDrafts {
        val aggregatedPlans = mutableListOf<HeuristicAggregationPlan>()
        val singleAudios = mutableListOf<AudioMetadataRef>()
        val inventory = context.sharedInventory
        val fileReader = context.scopeFileReader

        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        suspend fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (HeuristicAudioAggregator.shouldAggregate(pendingHeuristic)) {
                val first = pendingHeuristic.first()
                val plan = HeuristicAudioAggregator.buildPlan(
                    files = pendingHeuristic.toList(),
                    directoryContext = directoryContextFor(inventory, first.file.parentSourceKey),
                    openTextFile = { textFile -> fileReader?.open(textFile) }
                )
                aggregatedPlans.add(plan)
            } else {
                singleAudios.addAll(pendingHeuristic)
            }
            pendingHeuristic.clear()
        }

        input.looseAudioMetadataRefs.forEach { audioRef ->
            if (audioRef.metadata.chapters.isNotEmpty()) {
                flushHeuristic()
                singleAudios.add(audioRef)
            } else {
                val last = pendingHeuristic.lastOrNull()
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
 * Carries manifest drafts and grouped loose-audio plans to the cover-binding stage.
 */
internal data class GroupedBookDrafts(
    val manifestParsedResult: ManifestParsedResult,

    val aggregatedPlans: List<HeuristicAggregationPlan>,

    val singleAudios: List<AudioMetadataRef>
)
