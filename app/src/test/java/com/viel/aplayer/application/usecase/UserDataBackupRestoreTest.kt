package com.viel.aplayer.application.usecase

import android.content.Context
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
        try {
            val context = RuntimeEnvironment.getApplication()

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
            val exportResult = exportUseCase.execute(outputStream)
            assertTrue(exportResult.isSuccess)

            val zipBytes = outputStream.toByteArray()
            assertTrue(zipBytes.isNotEmpty())

            // 5. Clear / Modify files to verify restoration
            dbFile.writeText("stale-db")
            walFile.writeText("stale-wal")
            shmFile.writeText("stale-shm")
            settingsFile.writeText("stale-settings")
            absCredsFile.writeText("stale-abs")
            webdavPrefs.writeText("stale-webdav")

            // 6. Import
            val importUseCase = ImportUserDataUseCase(context)
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
        }
    }

    @Test
    fun testImportZipSlipPrevention() = runBlocking {
        // Title: Test Import Zip Slip Prevention
        // Constructs a malicious ZIP stream that attempts directory traversal via relative traversal entry names.
        // Verifies that the import use case rejects the import with a SecurityException and does not write out-of-sandbox files.
        try {
            val context = RuntimeEnvironment.getApplication()
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
        }
    }
}
