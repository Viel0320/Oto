package com.viel.aplayer.library.orchestrator.steps

import android.content.Context
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.orchestrator.ImportContext
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.media.manifest.HeuristicAudioAggregator
import com.viel.aplayer.media.manifest.ManifestSidecarSupport

/**
 * Heuristic Audiobook Clustering (Pipeline Step)
 *
 * This step has been refactored to remove the legacy generic ImportStep<I, O> interface and StepResult wrappers.
 * The execute method directly returns GroupedBookDrafts and lets exceptions bubble up naturally.
 */
internal class HeuristicGroupStep(private val appContext: Context) {

    /**
     * Execute Clustering Logic (Core Execution)
     *
     * Processes metadata drafts to group related files and compile heuristic aggregation plans.
     */
    suspend fun execute(
        input: ResolvedMetadataDrafts,
        context: ImportContext
    ): GroupedBookDrafts {
        val aggregatedPlans = mutableListOf<HeuristicAggregationPlan>()
        val singleAudios = mutableListOf<AudioMetadataRef>()
        val inventory = context.sharedInventory
        // Reuse Session-Level VFS Reader (Performance Optimization)
        // Obtains the shared file reader from ImportContext to prevent redundant resource allocations.
        val fileReader = context.scopeFileReader

        val pendingHeuristic = mutableListOf<AudioMetadataRef>()

        suspend fun flushHeuristic() {
            if (pendingHeuristic.isEmpty()) return
            if (HeuristicAudioAggregator.shouldAggregate(pendingHeuristic)) {
                // Build Aggregation Plan (Heuristic Grouping)
                // If conditions are met (e.g. sharing an album tag or sequential numeric filenames), construct a multi-file book plan.
                val first = pendingHeuristic.first()
                val plan = HeuristicAudioAggregator.buildPlan(
                    files = pendingHeuristic.toList(),
                    directoryContext = directoryContextFor(inventory, first.file.parentSourceKey),
                    openTextFile = { textFile -> fileReader?.open(textFile) }
                )
                aggregatedPlans.add(plan)
            } else {
                // Fallback to Loose Files (Individual Books)
                // Treat each file as a separate single-file audiobook.
                singleAudios.addAll(pendingHeuristic)
            }
            pendingHeuristic.clear()
        }

        // Process Loose Audios Sequentially (Clustering Algorithm)
        // Group candidate files into sequential aggregation blocks.
        input.looseAudioMetadataRefs.forEach { audioRef ->
            if (audioRef.metadata.chapters.isNotEmpty()) {
                // Flush on Embedded Chapters (Metadata Boundary)
                // Audio files with embedded chapters cannot be grouped; flush current buffer and emit as a single-file book.
                flushHeuristic()
                singleAudios.add(audioRef)
            } else {
                val last = pendingHeuristic.lastOrNull()
                // Flush on Directory Change (Directory Boundary)
                // Ensure files from different VFS parent directories are never grouped together.
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
 * Consolidated Clustering Results (Data Holder)
 */
internal data class GroupedBookDrafts(
    // Propagate Manifest Parse Data (Pipeline Context Preservation)
    // Forwards parsed manifest results for eventual consolidation.
    val manifestParsedResult: ManifestParsedResult,
    
    // Compiled Heuristic Aggregation Plans (Data Model)
    val aggregatedPlans: List<HeuristicAggregationPlan>,
    
    // Property Alignment (Aligns property name to singleAudios to resolve pipeline compilation error)
    // Renamed from looseAudioMetadataRefs to match orchestrator single track referencing conventions.
    val singleAudios: List<AudioMetadataRef>
)
