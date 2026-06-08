package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.library.orchestrator.draftmodels.BookDraft
import com.viel.aplayer.timeline.PositionMapper

/**
 * Ownership State Migrator (Replacement playback-state policy)
 *
 * Remaps user-owned state from obsolete book ownership rows to a replacement draft.
 * This class is deliberately database-free so BookImporter can remain a transactional persistence boundary.
 */
internal class OwnershipStateMigrator {

    /**
     * Migrate Replacement State (State transformation entry)
     *
     * Produces a replacement draft with inherited chronology/read-state plus remapped progress and bookmarks.
     */
    fun migrate(input: OwnershipStateMigrationInput): OwnershipStateMigrationResult {
        val latestProgress = input.oldProgresses.maxByOrNull { it.lastPlayedAt }
        val newAudioFiles = input.draft.files
            .filter { it.fileRole == AudiobookSchema.FileRole.AUDIO }
            .sortedBy { it.index }
        val oldFilesById = input.oldFiles.associateBy { it.id }
        val migratedProgress = latestProgress?.let { progress ->
            remapProgress(progress, oldFilesById, newAudioFiles, input.draft.book.id)
        }
        val migratedBook = input.draft.book.copy(
            // Replacement Added-Time Preservation (Library chronology continuity)
            // Keeps the oldest replaced book timestamp so ownership upgrades do not appear as newly added shelf items.
            addedAt = input.oldBooks.minOfOrNull { it.addedAt } ?: input.draft.book.addedAt,
            // Replacement Read-State Migration (Progress continuity)
            // Derives the new logical read status from migrated progress and prior completed/in-progress states.
            readStatus = resolveMigratedReadStatus(input.oldBooks, migratedProgress, input.draft.book)
        )
        val migratedBookmarks = input.oldBookmarks.map { bookmark ->
            remapBookmark(bookmark, oldFilesById, newAudioFiles, input.draft.book.id)
        }

        return OwnershipStateMigrationResult(
            draft = input.draft.copy(book = migratedBook),
            progress = migratedProgress,
            bookmarks = migratedBookmarks
        )
    }

    // Progress Anchor Remapping (Stable ownership migration)
    // Moves progress from an obsolete book to the replacement using file anchors first, falling back to global position when no file match exists.
    private fun remapProgress(
        progress: BookProgressEntity,
        oldFilesById: Map<String, BookFileEntity>,
        newAudioFiles: List<BookFileEntity>,
        newBookId: String
    ): BookProgressEntity {
        val oldAnchorFile = progress.bookFileId?.let { oldFilesById[it] }
        val matchedNewFile = findMatchingNewFile(oldAnchorFile, progress.fileFingerprint, newAudioFiles)
        if (matchedNewFile != null) {
            val newIndex = newAudioFiles.indexOfFirst { it.id == matchedNewFile.id }.coerceAtLeast(0)
            val positionInFile = progress.positionInFileMs.coerceIn(0L, matchedNewFile.durationMs.coerceAtLeast(0L))
            return progress.copy(
                bookId = newBookId,
                globalPositionMs = PositionMapper.fileToGlobalPosition(newIndex, positionInFile, newAudioFiles),
                bookFileId = matchedNewFile.id,
                currentFileIndex = newIndex,
                positionInFileMs = positionInFile,
                fileFingerprint = matchedNewFile.fingerprint ?: progress.fileFingerprint,
                anchorStatus = AudiobookSchema.AnchorStatus.REMAPPED
            )
        }

        val globalPosition = progress.globalPositionMs.coerceIn(0L, totalDuration(newAudioFiles))
        val (fileIndex, positionInFile) = PositionMapper.globalToFilePosition(globalPosition, newAudioFiles)
        val fallbackFile = newAudioFiles.getOrNull(fileIndex)
        return progress.copy(
            bookId = newBookId,
            globalPositionMs = globalPosition,
            bookFileId = fallbackFile?.id,
            currentFileIndex = fileIndex,
            positionInFileMs = positionInFile,
            fileFingerprint = fallbackFile?.fingerprint ?: progress.fileFingerprint,
            anchorStatus = AudiobookSchema.AnchorStatus.UNRESOLVED
        )
    }

    // Bookmark Anchor Remapping (Stable ownership migration)
    // Preserves bookmark IDs while moving them to the replacement book so old-book cascade deletion does not erase user notes.
    private fun remapBookmark(
        bookmark: BookmarkEntity,
        oldFilesById: Map<String, BookFileEntity>,
        newAudioFiles: List<BookFileEntity>,
        newBookId: String
    ): BookmarkEntity {
        val oldAnchorFile = bookmark.bookFileId?.let { oldFilesById[it] }
        val matchedNewFile = findMatchingNewFile(oldAnchorFile, bookmark.fileFingerprint, newAudioFiles)
        if (matchedNewFile != null) {
            val newIndex = newAudioFiles.indexOfFirst { it.id == matchedNewFile.id }.coerceAtLeast(0)
            val fileOffset = bookmark.fileOffsetMs.coerceIn(0L, matchedNewFile.durationMs.coerceAtLeast(0L))
            return bookmark.copy(
                bookId = newBookId,
                globalPositionMs = PositionMapper.fileToGlobalPosition(newIndex, fileOffset, newAudioFiles),
                bookFileId = matchedNewFile.id,
                fileOffsetMs = fileOffset,
                fileFingerprint = matchedNewFile.fingerprint ?: bookmark.fileFingerprint,
                anchorStatus = AudiobookSchema.AnchorStatus.REMAPPED
            )
        }

        val globalPosition = bookmark.globalPositionMs.coerceIn(0L, totalDuration(newAudioFiles))
        val (fileIndex, fileOffset) = PositionMapper.globalToFilePosition(globalPosition, newAudioFiles)
        val fallbackFile = newAudioFiles.getOrNull(fileIndex)
        return bookmark.copy(
            bookId = newBookId,
            globalPositionMs = globalPosition,
            bookFileId = fallbackFile?.id,
            fileOffsetMs = fileOffset,
            fileFingerprint = fallbackFile?.fingerprint ?: bookmark.fileFingerprint,
            anchorStatus = AudiobookSchema.AnchorStatus.UNRESOLVED
        )
    }

    // File Anchor Resolver (Stable path and identity matching)
    // Matches old and new audio rows by VFS path, provider identity, or fingerprint to survive book ID regeneration during replacement.
    private fun findMatchingNewFile(
        oldFile: BookFileEntity?,
        fallbackFingerprint: String?,
        newAudioFiles: List<BookFileEntity>
    ): BookFileEntity? {
        if (oldFile != null) {
            newAudioFiles.firstOrNull { candidate ->
                candidate.rootId == oldFile.rootId && candidate.sourcePath == oldFile.sourcePath
            }?.let { return it }
            if (oldFile.sourceIdentity.isNotBlank()) {
                newAudioFiles.firstOrNull { candidate ->
                    candidate.rootId == oldFile.rootId && candidate.sourceIdentity == oldFile.sourceIdentity
                }?.let { return it }
            }
            oldFile.fingerprint?.takeIf { it.isNotBlank() }?.let { fingerprint ->
                newAudioFiles.firstOrNull { it.fingerprint == fingerprint }?.let { return it }
            }
        }
        return fallbackFingerprint
            ?.takeIf { it.isNotBlank() }
            ?.let { fingerprint -> newAudioFiles.firstOrNull { it.fingerprint == fingerprint } }
    }

    // Migrated Read Status Resolver (State continuity)
    // Recomputes the replacement book's read state from prior book states and the remapped progress position.
    private fun resolveMigratedReadStatus(
        oldBooks: List<BookEntity>,
        migratedProgress: BookProgressEntity?,
        replacementBook: BookEntity
    ): String {
        if (oldBooks.any { it.readStatus == AudiobookSchema.ReadStatus.FINISHED }) {
            return AudiobookSchema.ReadStatus.FINISHED
        }
        val progressPosition = migratedProgress?.globalPositionMs ?: 0L
        return when {
            replacementBook.totalDurationMs > 0L && progressPosition >= (replacementBook.totalDurationMs * 0.99).toLong() ->
                AudiobookSchema.ReadStatus.FINISHED
            progressPosition > 0L || oldBooks.any { it.readStatus == AudiobookSchema.ReadStatus.IN_PROGRESS } ->
                AudiobookSchema.ReadStatus.IN_PROGRESS
            else ->
                AudiobookSchema.ReadStatus.NOT_STARTED
        }
    }

    private fun totalDuration(files: List<BookFileEntity>): Long =
        files.sumOf { it.durationMs }.coerceAtLeast(0L)
}

/**
 * Ownership State Migration Input (Database snapshot boundary)
 *
 * Carries the old persisted state and incoming replacement draft into the pure migration algorithm.
 */
internal data class OwnershipStateMigrationInput(
    val draft: BookDraft,
    val oldBooks: List<BookEntity>,
    val oldFiles: List<BookFileEntity>,
    val oldProgresses: List<BookProgressEntity>,
    val oldBookmarks: List<BookmarkEntity>
)

/**
 * Ownership State Migration Result (Persistence-ready output)
 *
 * Contains only the rows BookImporter needs to persist after the migration algorithm finishes.
 */
internal data class OwnershipStateMigrationResult(
    val draft: BookDraft,
    val progress: BookProgressEntity?,
    val bookmarks: List<BookmarkEntity>
)
