package com.viel.oto.library.orchestrator

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.entity.BookProgressEntity
import com.viel.oto.data.entity.BookmarkEntity
import com.viel.oto.library.orchestrator.draftmodels.BookDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnershipStateMigratorTest {

    private val migrator = OwnershipStateMigrator()

    private fun bookFile(
        id: String,
        index: Int,
        sourcePath: String,
        sourceIdentity: String = "",
        durationMs: Long = 1_000L,
        fingerprint: String? = null,
        bookId: String = "new-book",
        rootId: String = "root-1",
        fileRole: AudiobookSchema.FileRole = AudiobookSchema.FileRole.AUDIO
    ): BookFileEntity = BookFileEntity(
        id = id,
        bookId = bookId,
        fileRole = fileRole,
        rootId = rootId,
        index = index,
        sourcePath = sourcePath,
        sourceIdentity = sourceIdentity,
        displayName = id,
        durationMs = durationMs,
        fileSize = 0L,
        lastModified = 0L,
        fingerprint = fingerprint
    )

    private fun newBook(
        id: String = "new-book",
        totalDurationMs: Long = 2_000L,
        addedAt: Long = 5_000L,
        readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.NOT_STARTED
    ): BookEntity = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.CUE,
        title = id,
        totalDurationMs = totalDurationMs,
        addedAt = addedAt,
        readStatus = readStatus
    )

    private fun oldBook(
        id: String,
        addedAt: Long,
        readStatus: AudiobookSchema.ReadStatus = AudiobookSchema.ReadStatus.NOT_STARTED
    ): BookEntity = BookEntity(
        id = id,
        rootId = "root-1",
        sourceType = AudiobookSchema.SourceType.M3U8,
        title = id,
        addedAt = addedAt,
        readStatus = readStatus
    )

    private fun draftOf(files: List<BookFileEntity>, book: BookEntity = newBook()): BookDraft =
        BookDraft(book = book, files = files, chapters = emptyList())

    private fun progress(
        bookId: String = "old-book",
        globalPositionMs: Long = 0L,
        bookFileId: String? = null,
        positionInFileMs: Long = 0L,
        fileFingerprint: String? = null,
        lastPlayedAt: Long = 0L
    ): BookProgressEntity = BookProgressEntity(
        bookId = bookId,
        globalPositionMs = globalPositionMs,
        bookFileId = bookFileId,
        positionInFileMs = positionInFileMs,
        fileFingerprint = fileFingerprint,
        lastPlayedAt = lastPlayedAt
    )

    private fun bookmark(
        id: String = "bm-1",
        bookId: String = "old-book",
        globalPositionMs: Long = 0L,
        bookFileId: String? = null,
        fileOffsetMs: Long = 0L,
        fileFingerprint: String? = null
    ): BookmarkEntity = BookmarkEntity(
        id = id,
        bookId = bookId,
        globalPositionMs = globalPositionMs,
        bookFileId = bookFileId,
        fileOffsetMs = fileOffsetMs,
        fileFingerprint = fileFingerprint,
        title = id
    )

    @Test
    fun `picks latest progress by lastPlayedAt`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3")
        )
        val oldFiles = listOf(bookFile(id = "old1", index = 0, sourcePath = "dir/a.mp3", bookId = "old-book"))
        val older = progress(bookFileId = "old1", positionInFileMs = 100L, lastPlayedAt = 1L)
        val newer = progress(bookFileId = "old1", positionInFileMs = 700L, lastPlayedAt = 9L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(older, newer),
                oldBookmarks = emptyList()
            )
        )

        // Latest (lastPlayedAt=9) wins: positionInFile 700 mapped onto nf1.
        val progress = result.progress!!
        assertEquals(700L, progress.positionInFileMs)
        assertEquals("nf1", progress.bookFileId)
    }

    @Test
    fun `remap progress matches new file by path`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 1_000L, fingerprint = "fpA"),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3", durationMs = 1_000L)
        )
        val oldFiles = listOf(bookFile(id = "old1", index = 0, sourcePath = "dir/a.mp3", bookId = "old-book"))
        val prog = progress(bookFileId = "old1", positionInFileMs = 500L, globalPositionMs = 500L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        val migrated = result.progress!!
        assertEquals("new-book", migrated.bookId)
        assertEquals("nf1", migrated.bookFileId)
        assertEquals(0, migrated.currentFileIndex)
        assertEquals(500L, migrated.positionInFileMs)
        assertEquals(500L, migrated.globalPositionMs)
        assertEquals("fpA", migrated.fileFingerprint)
        assertEquals(AudiobookSchema.AnchorStatus.REMAPPED, migrated.anchorStatus)
    }

    @Test
    fun `remap progress matches new file by sourceIdentity when path differs`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/renamed.mp3", sourceIdentity = "sid-1", durationMs = 800L)
        )
        val oldFiles = listOf(
            bookFile(id = "old1", index = 0, sourcePath = "dir/original.mp3", sourceIdentity = "sid-1", bookId = "old-book")
        )
        val prog = progress(bookFileId = "old1", positionInFileMs = 300L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        val migrated = result.progress!!
        assertEquals("nf1", migrated.bookFileId)
        assertEquals(AudiobookSchema.AnchorStatus.REMAPPED, migrated.anchorStatus)
        assertEquals(300L, migrated.positionInFileMs)
    }

    @Test
    fun `remap progress prefers sourceIdentity over exact path`() {
        val newFiles = listOf(
            bookFile(id = "path-match", index = 0, sourcePath = "dir/original.mp3", sourceIdentity = "sid-other", durationMs = 1_000L),
            bookFile(id = "identity-match", index = 1, sourcePath = "dir/renamed.mp3", sourceIdentity = "sid-1", durationMs = 1_000L)
        )
        val oldFiles = listOf(
            bookFile(id = "old1", index = 0, sourcePath = "dir/original.mp3", sourceIdentity = "sid-1", bookId = "old-book")
        )
        val prog = progress(bookFileId = "old1", positionInFileMs = 300L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        val migrated = result.progress!!
        assertEquals("identity-match", migrated.bookFileId)
        assertEquals(1, migrated.currentFileIndex)
        assertEquals(1_300L, migrated.globalPositionMs)
    }

    @Test
    fun `remap progress matches new file by fingerprint when path and identity differ`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/x.mp3", sourceIdentity = "sid-new", fingerprint = "fp-shared")
        )
        val oldFiles = listOf(
            bookFile(
                id = "old1",
                index = 0,
                sourcePath = "dir/y.mp3",
                sourceIdentity = "sid-old",
                fingerprint = "fp-shared",
                bookId = "old-book"
            )
        )
        val prog = progress(bookFileId = "old1", positionInFileMs = 200L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        val migrated = result.progress!!
        assertEquals("nf1", migrated.bookFileId)
        assertEquals("fp-shared", migrated.fileFingerprint)
        assertEquals(AudiobookSchema.AnchorStatus.REMAPPED, migrated.anchorStatus)
    }

    @Test
    fun `remap progress coerces position into shorter new file`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 400L))
        val oldFiles = listOf(bookFile(id = "old1", index = 0, sourcePath = "dir/a.mp3", bookId = "old-book"))
        val prog = progress(bookFileId = "old1", positionInFileMs = 5_000L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        // positionInFile clamped to the new 400ms duration.
        val progress = result.progress!!
        assertEquals(400L, progress.positionInFileMs)
        assertEquals(400L, progress.globalPositionMs)
    }

    @Test
    fun `remap progress unresolved uses global position fallback`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 1_000L, fingerprint = "fpA"),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3", durationMs = 1_000L, fingerprint = "fpB")
        )
        // Old file id not present in oldFiles map and no fingerprint -> no match.
        val prog = progress(bookFileId = "missing", globalPositionMs = 1_500L, fileFingerprint = null, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = emptyList(),
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        val migrated = result.progress!!
        // global 1500 -> file index 1, offset 500.
        assertEquals(1_500L, migrated.globalPositionMs)
        assertEquals(1, migrated.currentFileIndex)
        assertEquals(500L, migrated.positionInFileMs)
        assertEquals("nf2", migrated.bookFileId)
        assertEquals(AudiobookSchema.AnchorStatus.UNRESOLVED, migrated.anchorStatus)
    }

    @Test
    fun `empty old progresses yields null migrated progress`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertNull(result.progress)
    }

    @Test
    fun `remap bookmark matches by path and unresolved falls back to global`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 1_000L, fingerprint = "fpA"),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3", durationMs = 1_000L)
        )
        val oldFiles = listOf(bookFile(id = "old1", index = 0, sourcePath = "dir/a.mp3", bookId = "old-book"))
        val matched = bookmark(id = "bm-matched", bookFileId = "old1", fileOffsetMs = 250L, globalPositionMs = 250L)
        val unresolved = bookmark(id = "bm-unresolved", bookFileId = "gone", fileOffsetMs = 0L, globalPositionMs = 1_200L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = emptyList(),
                oldBookmarks = listOf(matched, unresolved)
            )
        )

        val m = result.bookmarks.first { it.id == "bm-matched" }
        assertEquals("nf1", m.bookFileId)
        assertEquals(250L, m.fileOffsetMs)
        assertEquals(250L, m.globalPositionMs)
        assertEquals(AudiobookSchema.AnchorStatus.REMAPPED, m.anchorStatus)

        val u = result.bookmarks.first { it.id == "bm-unresolved" }
        // global 1200 -> file index 1, offset 200.
        assertEquals(1_200L, u.globalPositionMs)
        assertEquals(200L, u.fileOffsetMs)
        assertEquals("nf2", u.bookFileId)
        assertEquals(AudiobookSchema.AnchorStatus.UNRESOLVED, u.anchorStatus)
    }

    @Test
    fun `read status is finished when an old book was finished`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = listOf(oldBook("ob1", addedAt = 100L, readStatus = AudiobookSchema.ReadStatus.FINISHED)),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(AudiobookSchema.ReadStatus.FINISHED, result.draft.book.readStatus)
    }

    @Test
    fun `read status is finished when progress reaches 99 percent of total`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 1_000L),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3", durationMs = 1_000L)
        )
        val oldFiles = listOf(bookFile(id = "old1", index = 1, sourcePath = "dir/b.mp3", bookId = "old-book"))
        // Matches nf2 (index 1), offset clamped to 1000 -> global = 1000 + 1000 = 2000 >= 99% of 2000.
        val prog = progress(bookFileId = "old1", positionInFileMs = 1_000L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles, book = newBook(totalDurationMs = 2_000L)),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(AudiobookSchema.ReadStatus.FINISHED, result.draft.book.readStatus)
    }

    @Test
    fun `read status is in progress when progress is positive but below threshold`() {
        val newFiles = listOf(
            bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3", durationMs = 1_000L),
            bookFile(id = "nf2", index = 1, sourcePath = "dir/b.mp3", durationMs = 1_000L)
        )
        val oldFiles = listOf(bookFile(id = "old1", index = 0, sourcePath = "dir/a.mp3", bookId = "old-book"))
        val prog = progress(bookFileId = "old1", positionInFileMs = 100L, lastPlayedAt = 1L)

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles, book = newBook(totalDurationMs = 2_000L)),
                oldBooks = emptyList(),
                oldFiles = oldFiles,
                oldProgresses = listOf(prog),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, result.draft.book.readStatus)
    }

    @Test
    fun `read status is in progress when an old book was in progress and no playback progress`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = listOf(oldBook("ob1", addedAt = 100L, readStatus = AudiobookSchema.ReadStatus.IN_PROGRESS)),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(AudiobookSchema.ReadStatus.IN_PROGRESS, result.draft.book.readStatus)
    }

    @Test
    fun `read status is not started without progress or prior status`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles),
                oldBooks = listOf(oldBook("ob1", addedAt = 100L, readStatus = AudiobookSchema.ReadStatus.NOT_STARTED)),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(AudiobookSchema.ReadStatus.NOT_STARTED, result.draft.book.readStatus)
    }

    @Test
    fun `added at takes minimum of old books`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles, book = newBook(addedAt = 9_999L)),
                oldBooks = listOf(oldBook("ob1", addedAt = 300L), oldBook("ob2", addedAt = 150L)),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(150L, result.draft.book.addedAt)
    }

    @Test
    fun `added at falls back to draft when no old books`() {
        val newFiles = listOf(bookFile(id = "nf1", index = 0, sourcePath = "dir/a.mp3"))

        val result = migrator.migrate(
            OwnershipStateMigrationInput(
                draft = draftOf(newFiles, book = newBook(addedAt = 9_999L)),
                oldBooks = emptyList(),
                oldFiles = emptyList(),
                oldProgresses = emptyList(),
                oldBookmarks = emptyList()
            )
        )

        assertEquals(9_999L, result.draft.book.addedAt)
    }
}
