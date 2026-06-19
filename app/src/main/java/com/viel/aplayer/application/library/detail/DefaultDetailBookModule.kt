package com.viel.aplayer.application.library.detail

import com.viel.aplayer.application.library.toLibraryBookSourceType
import com.viel.aplayer.application.library.toLibraryReadStatus
import com.viel.aplayer.data.availability.BookAvailabilityGateway
import com.viel.aplayer.data.book.BookCatalogGateway
import com.viel.aplayer.data.root.LibraryRootGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Default Detail Book Module (Adapter from granular gateways to the detail scene)
 * Centralizes source formatting, availability refresh, and live book observation so DetailViewModel no longer reaches into root, file, or availability gateways.
 */
class DefaultDetailBookModule(
    private val bookCatalogGateway: BookCatalogGateway,
    private val bookAvailabilityGateway: BookAvailabilityGateway,
    private val libraryRootGateway: LibraryRootGateway,
    private val sourceLocationFormatter: DetailSourceLocationFormatter = DetailSourceLocationFormatter()
) : DetailBookReadModel, DetailBookCommands {

    override suspend fun resolveSourceLocation(snapshot: DetailSnapshot): String {
        val files = bookCatalogGateway.getAllFilesForBookSync(snapshot.bookId)
            .map { file ->
                DetailSourceFile(
                    fileRole = file.fileRole,
                    sourcePath = file.sourcePath,
                    displayName = file.displayName,
                    index = file.index
                )
            }
        val root = libraryRootGateway.getCachedLibraryRoots()
            .firstOrNull { root -> root.id == snapshot.rootId }
            ?: libraryRootGateway.getAllRootsOnce()
                .firstOrNull { root -> root.id == snapshot.rootId }
        val detailRoot = root?.let { matchedRoot ->
            DetailSourceRoot(
                id = matchedRoot.id,
                sourceType = matchedRoot.sourceType,
                displayName = matchedRoot.displayName
            )
        }

        // Detail Source Location Assembly (Combine selected book, source files, and root label)
        // The module performs root/file lookup once per selection so the ViewModel only receives display-ready text.
        return sourceLocationFormatter.format(
            snapshot = snapshot,
            files = files,
            root = detailRoot
        )
    }

    override fun observeLiveSnapshot(snapshot: DetailSnapshot): Flow<DetailSnapshot> {
        return bookCatalogGateway.observeBookById(snapshot.bookId)
            .map { updatedBook ->
                // Selected Book Guard (Ignore unexpected observer rows for non-selected ids)
                // The DAO should already scope emissions by id, but this guard keeps the module safe against fake or future adapter drift.
                if (updatedBook != null && updatedBook.id == snapshot.bookId) {
                    // Live Detail Item Mapping (Collapse the Room row into the detail scene projection)
                    // Only metadata needed by Detail rendering is copied, while playback progress remains the selection-time value managed by the ViewModel/service sync.
                    snapshot.withItem(
                        DetailBookItem(
                            id = updatedBook.id,
                            rootId = updatedBook.rootId,
                            sourceType = updatedBook.sourceType.toLibraryBookSourceType(),
                            title = updatedBook.title,
                            author = updatedBook.author,
                            narrator = updatedBook.narrator,
                            description = updatedBook.description,
                            year = updatedBook.year,
                            totalDurationMs = updatedBook.totalDurationMs,
                            totalFileSize = updatedBook.totalFileSize,
                            coverPath = updatedBook.coverPath,
                            thumbnailPath = updatedBook.thumbnailPath,
                            lastScannedAt = updatedBook.lastScannedAt,
                            progressPercent = snapshot.progressPercent,
                            // Live Detail Read Status Projection (Refresh manual read status with live book metadata)
                            // The action dialog consumes DetailBookItem only, so live database updates must carry readStatus through the same projection boundary.
                            readStatus = updatedBook.readStatus.toLibraryReadStatus()
                        )
                    )
                } else {
                    snapshot
                }
            }
    }

    override suspend fun refreshAvailability(bookId: String): Boolean {
        return bookAvailabilityGateway.refreshDetailAvailabilityStatus(bookId)
    }
}
