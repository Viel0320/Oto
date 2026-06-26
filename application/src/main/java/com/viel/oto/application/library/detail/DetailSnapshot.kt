package com.viel.oto.application.library.detail

import com.viel.oto.application.library.LibraryBookSourceType
import com.viel.oto.application.library.LibraryReadStatus
import com.viel.oto.data.db.AudiobookSchema

/**
 * Room-free selected book projection.
 * Carries only the fields the detail scene renders or routes, keeping Room entities inside data adapters.
 */
data class DetailBookItem(
    val id: String,
    val rootId: String,
    val sourceType: LibraryBookSourceType,
    val title: String,
    val author: String = "",
    val narrator: String = "",
    val description: String = "",
    val year: String = "",
    val totalDurationMs: Long = 0L,
    val totalFileSize: Long = 0L,
    val coverPath: String? = null,
    val thumbnailPath: String? = null,
    val lastScannedAt: Long = 0L,
    val progressPercent: Int = 0,

    val readStatus: LibraryReadStatus? = null
)

/**
 * Carries selected detail data through the scene boundary.
 * Wraps the Room-free detail item with detail-owned source and availability fields so UI state never exposes database projections.
 */
data class DetailSnapshot(
    val item: DetailBookItem,
    val isAvailable: Boolean = true,
    val sourceLocation: String = ""
) {
    val bookId: String
        get() = item.id

    val rootId: String
        get() = item.rootId

    val sourceType: LibraryBookSourceType
        get() = item.sourceType

    val progressPercent: Int
        get() = item.progressPercent

    /**
     * Preserve detail-local fields while replacing live metadata.
     * Live database observers refresh book fields, while source location and availability stay owned by the current detail selection.
     */
    fun withItem(updatedItem: DetailBookItem): DetailSnapshot {
        return copy(item = updatedItem.copy(progressPercent = progressPercent))
    }
}

/**
 * Formatter-safe source file data.
 * Keeps source-location formatting away from Room entities while carrying only the fields needed for the detail breadcrumb.
 */
data class DetailSourceFile(
    val fileRole: AudiobookSchema.FileRole,
    val sourcePath: String,
    val displayName: String,
    val index: Int
)

/**
 * Formatter-safe library root data.
 * Carries the registered root label and protocol without exposing raw storage URIs or credentials to formatter callers.
 */
data class DetailSourceRoot(
    val id: String,
    val sourceType: AudiobookSchema.LibrarySourceType,
    val displayName: String
)
