package com.viel.aplayer.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseDownloadMetadataMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `migration 42 to 43 should create download metadata and preserve existing rows`() {
        val databasePath = RuntimeEnvironment.getApplication().getDatabasePath(TEST_DATABASE).absolutePath

        helper.createDatabase(databasePath, 42).apply {
            seedExistingRows()
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            databasePath,
            43,
            true,
            AppDatabase.MIGRATION_42_43
        )

        assertEquals("Migration Book", migrated.singleString("SELECT title FROM books WHERE id = 'book-1'"))
        assertEquals("file-1", migrated.singleString("SELECT id FROM book_files WHERE id = 'file-1'"))
        assertEquals(123L, migrated.singleLong("SELECT globalPositionMs FROM book_progress WHERE bookId = 'book-1'"))
        assertEquals("bookmark-1", migrated.singleString("SELECT id FROM bookmarks WHERE id = 'bookmark-1'"))
        assertEquals("ABS", migrated.singleString("SELECT sourceType FROM library_roots WHERE id = 'root-1'"))
        assertEquals("ACTIVE", migrated.singleString("SELECT state FROM abs_item_mirror WHERE localBookId = 'book-1'"))
        assertEquals(0L, migrated.singleLong("SELECT COUNT(*) FROM download_metadata"))
        migrated.close()
    }

    private fun SupportSQLiteDatabase.seedExistingRows() {
        execSQL(
            """
            INSERT INTO library_roots (
                id, sourceType, sourceUri, basePath, credentialId, availabilityStatus,
                lastAvailabilityCheckedAt, lastAvailabilityErrorCode, displayName, grantedAt,
                lastScannedAt, status
            ) VALUES (
                'root-1', 'ABS', 'https://example.com/audiobookshelf', 'lib-1', 'cred-1',
                'AVAILABLE', 1, NULL, 'ABS Root', 1, 1, 'ACTIVE'
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO books (
                id, rootId, sourceType, sourceRoot, generatedManifestJson, heuristicRuleVersion,
                title, author, narrator, description, year, totalDurationMs, totalFileSize,
                coverPath, thumbnailPath, addedAt, lastScannedAt, status, readStatus, series
            ) VALUES (
                'book-1', 'root-1', 'ABS_REMOTE', '', NULL, NULL, 'Migration Book', 'Author',
                'Narrator', '', '', 1000, 500, NULL, NULL, 1, 1, 'READY', 'IN_PROGRESS', ''
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO book_files (
                id, bookId, fileRole, rootId, `index`, sourcePath, sourceIdentity, etag,
                manifestEntryPath, displayName, durationMs, fileSize, lastModified,
                fingerprint, lastSeenScanId, status
            ) VALUES (
                'file-1', 'book-1', 'AUDIO', 'root-1', 0, '/api/items/item-1/file/1',
                'abs-file-1', NULL, NULL, 'chapter.mp3', 1000, 500, 1, NULL, NULL, 'READY'
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO book_progress (
                bookId, globalPositionMs, bookFileId, currentFileIndex, positionInFileMs,
                fileFingerprint, anchorStatus, playbackSpeed, lastPlayedAt
            ) VALUES (
                'book-1', 123, 'file-1', 0, 123, NULL, 'OK', 1.0, 2
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO bookmarks (
                id, bookId, globalPositionMs, bookFileId, fileOffsetMs,
                fileFingerprint, anchorStatus, title, createdAt
            ) VALUES (
                'bookmark-1', 'book-1', 100, 'file-1', 100, NULL, 'OK', 'Bookmark', 3
            )
            """.trimIndent()
        )
        execSQL(
            """
            INSERT INTO abs_item_mirror (
                localBookId, rootId, serverKey, remoteItemId, lastSeenSyncRunId,
                lastSeenAt, remoteUpdatedAt, state
            ) VALUES (
                'book-1', 'root-1', 'server-key', 'item-1', 'sync-1', 4, 5, 'ACTIVE'
            )
            """.trimIndent()
        )
    }

    private fun SupportSQLiteDatabase.singleString(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Expected row for query: $sql" }
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.singleLong(sql: String): Long =
        query(sql).use { cursor ->
            check(cursor.moveToFirst()) { "Expected row for query: $sql" }
            cursor.getLong(0)
        }

    private companion object {
        const val TEST_DATABASE = "download-metadata-migration"
    }
}
