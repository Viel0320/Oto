package com.viel.aplayer.application.library.detail

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Detail Book Item (Room-free selected book projection)
 * Carries only the fields the detail scene renders or routes, keeping Room entities inside data adapters.
 */
data class DetailBookItem(
    val id: String,
    val rootId: String,
    val sourceType: AudiobookSchema.SourceType,
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
    // Detail Read Status Projection (Carry optional manual status for detail-owned action dialogs)
    // Keeping the value nullable lets callers that do not have read-status data open the detail action menu without marking any status as selected.
    // Read Status Type Safe: Change readStatus field type to ReadStatus enum for type safety.
    val readStatus: AudiobookSchema.ReadStatus? = null
)

/**
 * Detail Snapshot Transition Model (Carries selected detail data through the scene boundary)
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

    val sourceType: AudiobookSchema.SourceType
        get() = item.sourceType

    val progressPercent: Int
        get() = item.progressPercent

    /**
     * Snapshot Metadata Refresh (Preserve detail-local fields while replacing live metadata)
     * Live database observers refresh book fields, while source location and availability stay owned by the current detail selection.
     */
    fun withItem(updatedItem: DetailBookItem): DetailSnapshot {
        return copy(item = updatedItem.copy(progressPercent = progressPercent))
    }
}

/**
 * Detail Source File Projection (Formatter-safe source file data)
 * Keeps source-location formatting away from Room entities while carrying only the fields needed for the detail breadcrumb.
 */
data class DetailSourceFile(
    val fileRole: AudiobookSchema.FileRole,
    val sourcePath: String,
    val displayName: String,
    val index: Int
)

/**
 * Detail Source Root Projection (Formatter-safe library root data)
 * Carries the registered root label and protocol without exposing raw storage URIs or credentials to formatter callers.
 */
data class DetailSourceRoot(
    val id: String,
    val sourceType: AudiobookSchema.LibrarySourceType,
    val displayName: String
)
