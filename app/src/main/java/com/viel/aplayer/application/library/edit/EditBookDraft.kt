package com.viel.aplayer.application.library.edit

/**
 * Editable metadata projection.
 *
 * Holds the selected book fields needed by the edit scene while keeping the Room entity shape
 * inside the application adapter that loads and maps persisted data.
 */
data class EditBookDraft(
    val id: String,
    val title: String,
    val author: String,
    val narrator: String,
    val description: String,
    val year: String,
    val series: String,
    val coverPath: String?,
    val thumbnailPath: String?,
    val coverLastUpdated: Long
)
