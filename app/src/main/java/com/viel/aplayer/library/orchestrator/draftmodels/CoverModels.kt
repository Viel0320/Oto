package com.viel.aplayer.library.orchestrator.draftmodels

import com.viel.aplayer.library.FileRef
import com.viel.aplayer.library.orchestrator.steps.ParsedCueDraft
import com.viel.aplayer.library.orchestrator.steps.ParsedM3u8Draft
import com.viel.aplayer.media.manifest.AudioMetadataRef
import com.viel.aplayer.media.manifest.HeuristicAggregationPlan
import com.viel.aplayer.media.parser.CoverExtractor

/**
 * 本文件是从原 CoverExtractStep.kt 中提取出来的 5 个核心有声书封面提取状态数据模型。
 * 它们在后续的入库步骤 ConflictClaimStep (以及未来的 OwnershipClaimStep) 以及
 * 主编排器 ImportOrchestrator 中被作为流程流转媒介使用。
 * 
 * 将其独立提取有利于降低各 Step 之间的耦合度，避免因旧 Step 被删除而导致的核心数据模型丢失问题。
 */

/**
 * 聚合了 CUE、M3U8、启发式目录及单音频这四类有声书经过封面图处理后的综合结果集。
 */
internal data class CoverExtractedResult(
    val cueBooks: List<CoverExtractedCue>,
    val m3u8Books: List<CoverExtractedM3u8>,
    val aggregatedBooks: List<CoverExtractedAggregated>,
    val singleBooks: List<CoverExtractedSingle>
)

/**
 * 描述通过 CUE 描述文件解析并提取封面图后的有声书草稿。
 */
internal data class CoverExtractedCue(
    val bookId: String,
    val draft: ParsedCueDraft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * 描述通过 M3U8 描述文件解析并提取封面图后的有声书草稿。
 */
internal data class CoverExtractedM3u8(
    val bookId: String,
    val draft: ParsedM3u8Draft,
    val audioRefs: List<FileRef>,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * 描述通过启发式目录扫描并提取封面图后的有声书草稿。
 */
internal data class CoverExtractedAggregated(
    val bookId: String,
    val plan: HeuristicAggregationPlan,
    val coverResult: CoverExtractor.CoverResult?
)

/**
 * 描述单个音频文件且无外部描述文件，在提取封面图后的有声书草稿。
 */
internal data class CoverExtractedSingle(
    val bookId: String,
    val audioRef: AudioMetadataRef,
    val coverResult: CoverExtractor.CoverResult?
)
