package com.viel.aplayer.data.gateway

/**
 * Compatibility Book Query Gateway (Temporary aggregate of narrower book gateways)
 *
 * Kept only as a migration bridge for tests and older callers while production wiring moves to
 * BookCatalogGateway, BookMetadataGateway, BookmarkGateway, ChapterGateway, and BookDeletionGateway.
 */
@Deprecated(
    message = "Depend on BookCatalogGateway, BookMetadataGateway, BookmarkGateway, ChapterGateway, or BookDeletionGateway instead.",
    replaceWith = ReplaceWith("BookCatalogGateway")
)
interface BookQueryGateway :
    BookCatalogGateway,
    BookMetadataGateway,
    BookmarkGateway,
    ChapterGateway,
    BookDeletionGateway
