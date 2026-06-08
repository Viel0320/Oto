package com.viel.aplayer.data.dao

import androidx.room.Room
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.LibraryRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Room Progress Ordering Harness (Runs the real DAO transaction against an in-memory database)
// The bug is a persistence-ordering issue, so this test avoids fakes and verifies the actual Room update path used by ProgressService.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
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

            // Stale Polling Rejection (Locks seek checkpoints as newer than delayed automatic polling saves)
            // A 10-second polling snapshot that completes after a 100-second seek must not rewind book_progress.globalPositionMs.
            assertTrue(seekAccepted)
            assertFalse(delayedPollingAccepted)
            assertEquals(100_000L, progress?.globalPositionMs)
            assertEquals(2_000L, progress?.lastPlayedAt)
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

    private companion object {
        const val ROOT_ID = "root-progress-ordering"
        const val BOOK_ID = "book-progress-ordering"
        const val FILE_ID = "file-progress-ordering"
    }
}
