package com.viel.aplayer.library


// Parsed manifest chapter candidate used before BookFile ids are assigned.
data class ChapterCandidate(
    val title: String,
    // 解析器阶段保存原始清单条目，进入导入阶段后会替换成 VFS 文件键而不是 provider URI。
    val fileKey: String,
    val fileOffsetMs: Long,
    val durationMs: Long = 0L
)

// Parsed manifest-level metadata; null means the manifest did not define it.
data class MetadataSuggestion(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val year: String? = null,
    val description: String? = null
)
