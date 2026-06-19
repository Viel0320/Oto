package com.viel.aplayer.application.usecase

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.viel.aplayer.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Title: User Data Backup and Restore Test (Verifies complete data export, integrity on restoration, and vulnerability mitigation)
// Tests the full lifecycle of backing up settings, SharedPreferences, and database files into a single ZIP archive, 
// ensuring proper extraction, clean up, and safety against malicious zip payloads (Zip Slip).
@RunWith(RobolectricTestRunner::class)
class UserDataBackupRestoreTest {

    @Test
    fun testExportAndImportCycle() = runBlocking {
        // Title: Test Export and Import Cycle
        // Sets up dummy database, datastore, and shared preferences files, exports them to a ZIP stream, 
        // cleanses/modifies the active files, imports from the ZIP, and verifies identical content restoration.
        val tempRoot = Files.createTempDirectory("aplayer-backup-cycle").toFile()
        try {
            val context = IsolatedBackupContext(RuntimeEnvironment.getApplication(), tempRoot)

            // Isolated Backup Fixture (Close any Room singleton before writing raw sample files)
            // Full-suite Robolectric runs can leave application warmup work alive, so this test writes into its own dataDir instead of the process application dataDir.
            AppDatabase.closeInstance()

            // 1. Prepare dummy database files
            val dbFile = context.getDatabasePath("aplayer_database")
            dbFile.parentFile?.mkdirs()
            dbFile.writeText("database-content")

            val walFile = context.getDatabasePath("aplayer_database-wal")
            walFile.writeText("wal-content")

            val shmFile = context.getDatabasePath("aplayer_database-shm")
            shmFile.writeText("shm-content")

            // 2. Prepare dummy Datastore files
            val datastoreDir = File(context.filesDir, "datastore")
            datastoreDir.mkdirs()
            val settingsFile = File(datastoreDir, "app_settings.preferences_pb")
            settingsFile.writeText("settings-content")
            val absCredsFile = File(datastoreDir, "abs_credentials.preferences_pb")
            absCredsFile.writeText("abs-credentials-content")

            // 3. Prepare dummy SharedPreferences files
            val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
            sharedPrefsDir.mkdirs()
            val webdavPrefs = File(sharedPrefsDir, "webdav_credentials.xml")
            webdavPrefs.writeText("webdav-credentials-content")

            // 4. Export
            val exportUseCase = ExportUserDataUseCase(context)
            val outputStream = ByteArrayOutputStream()
            val backupManifest = sampleBackupManifest()
            val exportResult = exportUseCase.execute(outputStream, backupManifest)
            assertTrue(
                "Export failed: ${exportResult.exceptionOrNull()?.stackTraceToString()}",
                exportResult.isSuccess
            )

            val zipBytes = outputStream.toByteArray()
            assertTrue(zipBytes.isNotEmpty())

            val importUseCase = ImportUserDataUseCase(context)
            val exportedManifest = importUseCase.peekManifest(ByteArrayInputStream(zipBytes))
            assertEquals(backupManifest, exportedManifest)

            // 5. Clear / Modify files to verify restoration
            dbFile.writeText("stale-db")
            walFile.writeText("stale-wal")
            shmFile.writeText("stale-shm")
            settingsFile.writeText("stale-settings")
            absCredsFile.writeText("stale-abs")
            webdavPrefs.writeText("stale-webdav")

            // 6. Import
            val inputStream = ByteArrayInputStream(zipBytes)
            val importResult = importUseCase.execute(inputStream)
            assertTrue(importResult.isSuccess)

            // 7. Verify restoration integrity
            assertEquals("database-content", dbFile.readText())
            assertEquals("wal-content", walFile.readText())
            assertEquals("shm-content", shmFile.readText())
            assertEquals("settings-content", settingsFile.readText())
            assertEquals("abs-credentials-content", absCredsFile.readText())
            assertEquals("webdav-credentials-content", webdavPrefs.readText())
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            e.printStackTrace(pw)
            throw AssertionError("Captured exception inside testExportAndImportCycle:\n$sw")
        } finally {
            // Isolated Backup Fixture Cleanup (Remove only this test-owned data directory)
            // The temporary root is not shared with Robolectric's application sandbox, so recursive deletion cannot affect other tests.
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testMalformedManifestPeekReturnsNull() = runBlocking {
        // Title: Test Malformed Manifest Peek
        // Verifies that metadata preview failures do not bypass the later user-confirmed import failure path.
        val context = RuntimeEnvironment.getApplication()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("{broken".toByteArray())
            zos.closeEntry()
        }

        val manifest =
            ImportUserDataUseCase(context).peekManifest(ByteArrayInputStream(output.toByteArray()))

        assertEquals(null, manifest)
    }
    @Test
    fun testImportZipSlipPrevention() = runBlocking {
        // Title: Test Import Zip Slip Prevention
        // Constructs a malicious ZIP stream that attempts directory traversal via relative traversal entry names.
        // Verifies that the import use case rejects the import with a SecurityException and does not write out-of-sandbox files.
        val tempRoot = Files.createTempDirectory("aplayer-backup-zipslip").toFile()
        try {
            val context = IsolatedBackupContext(RuntimeEnvironment.getApplication(), tempRoot)
            val maliciousOutput = ByteArrayOutputStream()
            
            ZipOutputStream(maliciousOutput).use { zos ->
                // Path pointing outside the target directories (relative traversal)
                val badEntry = ZipEntry("database/../../escaped.txt")
                zos.putNextEntry(badEntry)
                zos.write("malicious-payload".toByteArray())
                zos.closeEntry()
            }

            val maliciousBytes = maliciousOutput.toByteArray()
            val importUseCase = ImportUserDataUseCase(context)
            val inputStream = ByteArrayInputStream(maliciousBytes)

            val result = importUseCase.execute(inputStream)
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is SecurityException)

            // Verify that the file was not written to the external location
            val escapedFile = File(context.dataDir.parentFile, "escaped.txt")
            assertFalse(escapedFile.exists())
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            e.printStackTrace(pw)
            throw AssertionError("Captured exception inside testImportZipSlipPrevention:\n$sw")
        } finally {
            // Isolated Zip Slip Fixture Cleanup (Remove only this test-owned data directory)
            // The escaped-file assertion targets the parent of this temporary root, so cleanup remains constrained to the fixture root.
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Builds a deterministic backup manifest so export tests assert metadata is mandatory.
     */
    private fun sampleBackupManifest(): BackupManifest = BackupManifest(
        appName = "APlayer",
        packageName = "com.viel.aplayer",
        versionName = "1.2.3",
        versionCode = 123L,
        libraryRoots = listOf("content://library/root"),
        exportedAt = "2026-06-19 12:00:00",
        databaseVersion = AppDatabase.VERSION
    )
    /**
     * Isolated Backup Context (Redirects app-private file APIs into a test-owned data directory)
     * Keeps backup tests deterministic when the real APlayerApplication background warmup opens Room during full-suite runs.
     */
    private class IsolatedBackupContext(
        base: Context,
        private val root: File
    ) : ContextWrapper(base) {
        override fun getDataDir(): File = root.apply { mkdirs() }

        override fun getFilesDir(): File =
            File(dataDir, "files").apply { mkdirs() }

        override fun getDatabasePath(name: String): File =
            File(dataDir, "databases/$name").also { file -> file.parentFile?.mkdirs() }

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("${root.name}-$name", mode)
    }
}
