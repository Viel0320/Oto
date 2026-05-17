package com.viel.aplayer.library

import com.viel.aplayer.data.LibraryRootEntity

/**
 * 扫描过程中产生的文件归属来源类型。
 */
enum class ClaimSourceType {
    SINGLE_AUDIO,
    CUE,
    M3U8,
    M4B_EMBEDDED,
    GENERATED_M3U8
}

/**
 * 代表一个扫描到的潜在书籍来源。
 */
data class ClaimSource(
    val type: ClaimSourceType,
    val sourceUri: String,
    val rootId: String,
    val priority: Int,
    /** 该来源引用的所有音频文件 URI 列表 */
    val referencedFileUris: List<String>,
    /** 扫描到的候选封面图 URI */
    val coverUri: String? = null,
    /** 文件的标题映射（URI -> 标题） */
    val fileTitles: Map<String, String> = emptyMap(),
    /** 文件的时长映射（URI -> 时长 Ms） */
    val fileDurations: Map<String, Long> = emptyMap(),
    /** 明确定义的章节候选列表 */
    val chapters: List<ChapterCandidate> = emptyList(),
    /** 书籍元数据建议 */
    val metadata: MetadataSuggestion? = null,
    /** 来源文件的元数据，用于检测变化 */
    val sourceFileSize: Long = 0L,
    val sourceLastModified: Long = 0L,
    /** 来源文件的显示名称 */
    val displayName: String,
    /** 可选：关联的父目录 URI */
    val parentUri: String? = null,
    /** 扫描到的候选字幕 URI */
    val subtitleUri: String? = null
)

/**
 * 章节候选。
 */
data class ChapterCandidate(
    val title: String,
    val fileUri: String,
    val fileOffsetMs: Long,
    val durationMs: Long = 0L
)

/**
 * 书籍元数据建议（从 Manifest 中提取）。
 */
data class MetadataSuggestion(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val description: String? = null
)

/**
 * 媒体库扫描结果快照。
 */
data class LibrarySnapshot(
    val scanId: String,
    val roots: List<LibraryRootEntity>,
    val claims: List<ClaimSource>,
    val timestamp: Long = System.currentTimeMillis()
)
