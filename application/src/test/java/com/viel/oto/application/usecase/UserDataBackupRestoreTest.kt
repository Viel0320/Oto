package com.viel.oto.application.usecase

import android.content.Context
import android.content.ContextWrapper
import com.viel.oto.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserDataBackupRestoreTest {

    @Test
    fun testExportAndImportCycle() = runBlocking {
        val tempRoot = Files.createTempDirectory("oto-backup-cycle").toFile()
        try {
            val context = IsolatedBackupContext(RuntimeEnvironment.getApplication(), tempRoot)

            val dbFile = context.getDatabasePath("oto_database")
            dbFile.parentFile?.mkdirs()
            dbFile.writeText("database-content")

            val walFile = context.getDatabasePath("oto_database-wal")
            walFile.writeText("wal-content")

            val shmFile = context.getDatabasePath("oto_database-shm")
            shmFile.writeText("shm-content")

            val datastoreDir = File(context.filesDir, "datastore")
            datastoreDir.mkdirs()
            val settingsFile = File(datastoreDir, "app_settings.preferences_pb")
            settingsFile.writeText("settings-content")
            val absCredsFile = File(datastoreDir, "abs_credentials.preferences_pb")
            absCredsFile.writeText("abs-credentials-content")

            val webdavCredsFile = File(datastoreDir, "webdav_credentials.preferences_pb")
            webdavCredsFile.writeText("webdav-credentials-content")

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

            dbFile.writeText("stale-db")
            walFile.writeText("stale-wal")
            shmFile.writeText("stale-shm")
            settingsFile.writeText("stale-settings")
            absCredsFile.writeText("stale-abs")
            webdavCredsFile.writeText("stale-webdav")

            val inputStream = ByteArrayInputStream(zipBytes)
            val importResult = importUseCase.execute(inputStream)
            assertTrue(importResult.isSuccess)

            assertEquals("database-content", dbFile.readText())
            assertEquals("wal-content", walFile.readText())
            assertEquals("shm-content", shmFile.readText())
            assertEquals("settings-content", settingsFile.readText())
            assertEquals("abs-credentials-content", absCredsFile.readText())
            assertEquals("webdav-credentials-content", webdavCredsFile.readText())
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            e.printStackTrace(pw)
            throw AssertionError("Captured exception inside testExportAndImportCycle:\n$sw")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun testMalformedManifestPeekReturnsNull() = runBlocking {
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
        val tempRoot = Files.createTempDirectory("oto-backup-zipslip").toFile()
        try {
            val context = IsolatedBackupContext(RuntimeEnvironment.getApplication(), tempRoot)
            val maliciousOutput = ByteArrayOutputStream()

            ZipOutputStream(maliciousOutput).use { zos ->
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

            val escapedFile = File(context.dataDir.parentFile, "escaped.txt")
            assertFalse(escapedFile.exists())
        } catch (e: Throwable) {
            val sw = java.io.StringWriter()
            val pw = java.io.PrintWriter(sw)
            e.printStackTrace(pw)
            throw AssertionError("Captured exception inside testImportZipSlipPrevention:\n$sw")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Builds a deterministic backup manifest so export tests assert metadata is mandatory.
     */
    private fun sampleBackupManifest(): BackupManifest = BackupManifest(
        appName = "Oto",
        packageName = "com.viel.oto",
        versionName = "1.2.3",
        versionCode = 123L,
        libraryRoots = listOf("content://library/root"),
        exportedAt = "2026-06-19 12:00:00",
        databaseVersion = AppDatabase.VERSION
    )
    /**
     * Redirects app-private file APIs into a test-owned data directory.
     * Keeps backup tests deterministic when the real OtoApplication background warmup opens Room during full-suite runs.
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

    }
}
