package com.viel.aplayer.library.orchestrator.draftmodels

import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.steps.ParsedCueDraft
import com.viel.aplayer.library.orchestrator.steps.ParsedM3u8Draft
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.media.parser.CoverExtractor

/**
 * Cover-binding models shared by the import orchestration steps.
 *
 * Keeping these small result shapes separate lets ownership decisions consume cover outcomes without
 * depending on parser-specific draft types.
 */

/**
 * Aggregates cover results for CUE lists, M3U8 lists, heuristic folders, and loose single audio files.
 */
internal data class CoverExtractedResult(
    val cueBooks: List<CoverExtractedCue>,
    val m3u8Books: List<CoverExtractedM3u8>,
    val aggregatedBooks: List<CoverExtractedAggregated>,
    val singleBooks: List<CoverExtractedSingle>
)

/**
 * Represents an audiobook parsed from a CUE manifest with its associated extracted cover result.
 */
internal data class CoverExtractedCue(
    val bookId: String,
    val draft: ParsedCueDraft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * Represents an audiobook parsed from an M3U8 playlist with its associated extracted cover result.
 */
internal data class CoverExtractedM3u8(
    val bookId: String,
    val draft: ParsedM3u8Draft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * Represents an audiobook grouped via folder-level heuristics with its associated extracted cover result.
 */
internal data class CoverExtractedAggregated(
    val bookId: String,
    val plan: HeuristicAggregationPlan,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * Represents an audiobook compiled from a single audio track with its associated extracted cover result.
 */
internal data class CoverExtractedSingle(
    val bookId: String,
    val audioRef: AudioMetadataRef,
    val coverResult: CoverExtractor.CoverResult?
)
