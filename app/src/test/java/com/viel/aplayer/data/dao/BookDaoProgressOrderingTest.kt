package com.viel.aplayer.data.dao

import androidx.room.Room
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookDaoProgressOrderingTest {

    @Test
    fun `older polling checkpoint should not overwrite newer seek checkpoint`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        try {
            database.seedProgressBook()

            val seekAccepted = database.bookDao().updateProgressWithReadStatus(
                bookId = BOOK_ID,
                position = 100_000L,
                currentTime = 2_000L
            )
            val delayedPollingAccepted = database.bookDao().updateProgressWithReadStatus(
                bookId = BOOK_ID,
                position = 10_000L,
                currentTime = 1_000L
            )
            val progress = database.bookDao().getProgressForBookSync(BOOK_ID)

            assertTrue(seekAccepted)
            assertFalse(delayedPollingAccepted)
            assertEquals(100_000L, progress?.globalPositionMs)
            assertEquals(2_000L, progress?.lastPlayedAt)
        } finally {
            database.close()
        }
    }

    @Test
    fun `home catalog projection should join progress and hide deleted books`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        try {
            database.seedHomeCatalogRows()

            val rows = database.bookDao().observeHomeCatalogRows().first()

            assertEquals(listOf("book-no-progress", "book-with-progress"), rows.map { row -> row.id })
            assertEquals(0, rows.first { row -> row.id == "book-no-progress" }.progressPercent)
            assertEquals(0L, rows.first { row -> row.id == "book-no-progress" }.lastPlayedAt)
            assertEquals(34, rows.first { row -> row.id == "book-with-progress" }.progressPercent)
            assertEquals(12_345L, rows.first { row -> row.id == "book-with-progress" }.lastPlayedAt)
        } finally {
            database.close()
        }
    }

    private suspend fun AppDatabase.seedProgressBook() {
        libraryRootDao().insertRoot(
            LibraryRootEntity(
                id = ROOT_ID,
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://library-root",
                displayName = "Local Library"
            )
        )
        bookDao().insertBook(
            BookEntity(
                id = BOOK_ID,
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = "Progress Ordering Fixture",
                totalDurationMs = 120_000L
            )
        )
        bookDao().insertBookFiles(
            listOf(
                BookFileEntity(
                    id = FILE_ID,
                    bookId = BOOK_ID,
                    rootId = ROOT_ID,
                    index = 0,
                    sourcePath = "book.m4b",
                    sourceIdentity = "book.m4b",
                    displayName = "book.m4b",
                    durationMs = 120_000L,
                    fileSize = 1024L,
                    lastModified = 1L
                )
            )
        )
    }

    /**
     * Seeds active, progressed, and deleted books.
     *
     * Keeps the query test focused on the new Home projection semantics without requiring file rows or playback plans.
     */
    private suspend fun AppDatabase.seedHomeCatalogRows() {
        libraryRootDao().insertRoot(
            LibraryRootEntity(
                id = ROOT_ID,
                sourceType = AudiobookSchema.LibrarySourceType.SAF,
                sourceUri = "content://library-root",
                displayName = "Local Library"
            )
        )
        bookDao().insertBook(
            BookEntity(
                id = "book-with-progress",
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = "B Progressed",
                totalDurationMs = 3_000L,
                readStatus = AudiobookSchema.ReadStatus.IN_PROGRESS
            )
        )
        bookDao().insertProgress(
            BookProgressEntity(
                bookId = "book-with-progress",
                globalPositionMs = 1_001L,
                lastPlayedAt = 12_345L
            )
        )
        bookDao().insertBook(
            BookEntity(
                id = "book-no-progress",
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = "A Empty",
                totalDurationMs = 3_000L
            )
        )
        bookDao().insertBook(
            BookEntity(
                id = "book-deleted",
                rootId = ROOT_ID,
                sourceType = AudiobookSchema.SourceType.SINGLE_AUDIO,
                title = "C Deleted",
                status = AudiobookSchema.BookStatus.DELETED
            )
        )
    }

    private companion object {
        const val ROOT_ID = "root-progress-ordering"
        const val BOOK_ID = "book-progress-ordering"
        const val FILE_ID = "file-progress-ordering"
    }
}
