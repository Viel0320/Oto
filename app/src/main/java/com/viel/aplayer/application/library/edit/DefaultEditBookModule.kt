package com.viel.aplayer.application.library.edit

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.gateway.BookCatalogGateway
import com.viel.aplayer.data.gateway.BookMetadataGateway
import com.viel.aplayer.data.gateway.CoverAssetGateway

/**
 * Default Edit Book Module (Adapter from granular gateways to the edit scene)
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
        // Edit Metadata Delegation (Route text writes through the metadata-only gateway)
        // The edit scene still reads selected books from the catalog seam but no longer inherits catalog search operations for writes.
        bookMetadataGateway.updateBookDetails(
            id = id,
            title = title,
            author = author,
            narrator = narrator,
            description = description,
            year = year,
            series = series
        )
    }

    override suspend fun saveCustomCover(bookId: String, tempCoverPath: String) {
        coverAssetGateway.saveCustomCover(bookId, tempCoverPath)
    }
}

/**
 * Edit Draft Mapper (Convert persisted book rows into editable scene drafts)
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
