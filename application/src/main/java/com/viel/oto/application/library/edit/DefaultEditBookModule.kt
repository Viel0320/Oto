package com.viel.oto.application.library.edit

import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.book.BookMetadataGateway
import com.viel.oto.data.cover.CoverAssetGateway
import com.viel.oto.data.entity.BookEntity

/**
 * Adapter from granular gateways to the edit scene.
 * Centralizes editable metadata reads, text updates, and custom cover writes behind edit-scoped seams.
 */
class DefaultEditBookModule(
    private val bookCatalogGateway: BookCatalogGateway,
    private val bookMetadataGateway: BookMetadataGateway,
    private val coverAssetGateway: CoverAssetGateway
) : EditBookReadModel, EditBookCommands {

    override suspend fun getEditableBook(bookId: String): EditBookDraft? {
        return bookCatalogGateway.getBookById(bookId)?.toEditBookDraft()
    }

    override suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String,
        series: String
    ) {
        val normalizedTitle = title.trim()
        require(normalizedTitle.isNotBlank()) { "EDIT_TITLE_REQUIRED" }

        bookMetadataGateway.updateBookDetails(
            id = id,
            title = normalizedTitle,
            author = author,
            narrator = narrator,
            description = description,
            year = year,
            series = series
        )
    }

    override suspend fun saveCustomCover(bookId: String, coverUri: String) {
        coverAssetGateway.saveCustomCover(bookId, coverUri)
    }
}

/**
 * Convert persisted book rows into editable scene drafts.
 *
 * Contains the only BookEntity knowledge needed by the edit read path, preventing UI state and
 * composable parameters from inheriting database persistence fields.
 */
private fun BookEntity.toEditBookDraft(): EditBookDraft {
    return EditBookDraft(
        id = id,
        title = title,
        author = author,
        narrator = narrator,
        description = description,
        year = year,
        series = series,
        coverPath = coverPath,
        thumbnailPath = thumbnailPath,
        coverLastUpdated = lastScannedAt
    )
}
