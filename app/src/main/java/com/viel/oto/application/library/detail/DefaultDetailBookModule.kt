package com.viel.oto.application.library.detail

import com.viel.oto.application.library.toLibraryBookSourceType
import com.viel.oto.application.library.toLibraryReadStatus
import com.viel.oto.data.availability.BookAvailabilityGateway
import com.viel.oto.data.book.BookCatalogGateway
import com.viel.oto.data.root.LibraryRootGateway
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Adapter from granular gateways to the detail scene.
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

        return sourceLocationFormatter.format(
            snapshot = snapshot,
            files = files,
            root = detailRoot
        )
    }

    override fun observeLiveSnapshot(snapshot: DetailSnapshot): Flow<DetailSnapshot> {
        return bookCatalogGateway.observeBookById(snapshot.bookId)
            .map { updatedBook ->
                if (updatedBook != null && updatedBook.id == snapshot.bookId) {
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
