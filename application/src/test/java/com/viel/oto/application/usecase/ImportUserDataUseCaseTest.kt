package com.viel.oto.application.usecase

import android.content.Context
import com.squareup.moshi.Moshi
import com.viel.oto.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Locks the restore boundary: manifest compatibility gating, tolerant manifest peeking, the ZIP path
 * traversal guard, and routed extraction of database/datastore payloads into the app sandbox.
 *
 * Runs under Robolectric because restore resolves real Context database/files directories and writes
 * extracted entries to the sandbox filesystem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ImportUserDataUseCaseTest {

    private lateinit var context: Context
    private lateinit var useCase: ImportUserDataUseCase
    private val manifestAdapter = Moshi.Builder().build().adapter(BackupManifest::class.java)

    @Before
    fun setUp() {
        context = org.robolectric.RuntimeEnvironment.getApplication()
        useCase = ImportUserDataUseCase(context = context)
    }

    @Test
    fun `current database version is exposed from the schema`() {
        assertEquals(AppDatabase.VERSION, useCase.currentDatabaseVersion)
    }

    @Test
    fun `manifest compatibility allows equal and older schema versions`() {
        assertTrue(useCase.isManifestCompatible(manifest(databaseVersion = AppDatabase.VERSION)))
        assertTrue(useCase.isManifestCompatible(manifest(databaseVersion = AppDatabase.VERSION - 1)))
        assertTrue(useCase.isManifestCompatible(manifest(databaseVersion = 1)))
    }

    @Test
    fun `manifest compatibility rejects a newer schema version`() {
        assertFalse(useCase.isManifestCompatible(manifest(databaseVersion = AppDatabase.VERSION + 1)))
    }

    @Test
    fun `peek manifest reads the manifest entry without extracting payloads`() = runBlocking {
        val manifest = manifest(databaseVersion = 7, appName = "Oto Backup")
        val archive = zipBytes(
            "manifest.json" to manifestAdapter.toJson(manifest).toByteArray(Charsets.UTF_8),
            "database/oto_database" to byteArrayOf(1, 2, 3)
        )

        val peeked = useCase.peekManifest(ByteArrayInputStream(archive))

        assertEquals("Oto Backup", peeked?.appName)
        assertEquals(7, peeked?.databaseVersion)
    }

    @Test
    fun `peek manifest returns null for an archive with no manifest`() = runBlocking {
        val archive = zipBytes("database/oto_database" to byteArrayOf(9, 9))

        assertNull(useCase.peekManifest(ByteArrayInputStream(archive)))
    }

    @Test
    fun `peek manifest returns null for malformed zip content`() = runBlocking {
        val notAZip = "this is not a zip archive".toByteArray(Charsets.UTF_8)

        assertNull(useCase.peekManifest(ByteArrayInputStream(notAZip)))
    }

    @Test
    fun `execute extracts routed database and datastore entries into the sandbox`() = runBlocking {
        val archive = zipBytes(
            "database/oto_database" to "db-bytes".toByteArray(Charsets.UTF_8),
            "datastore/app_settings.preferences_pb" to "settings-bytes".toByteArray(Charsets.UTF_8),
            "unknown/ignored.txt" to "ignored".toByteArray(Charsets.UTF_8)
        )

        val result = useCase.execute(ByteArrayInputStream(archive))

        assertTrue(result.isSuccess)
        assertEquals("db-bytes", context.getDatabasePath("oto_database").readText())
        assertEquals(
            "settings-bytes",
            File(context.filesDir, "datastore/app_settings.preferences_pb").readText()
        )
        // Unrecognized prefixes are skipped, never written anywhere under the sandbox.
        assertFalse(File(context.dataDir, "unknown/ignored.txt").exists())
    }

    @Test
    fun `execute rejects a zip entry that escapes the sandbox`() = runBlocking {
        val archive = zipBytes(
            "database/../../../../tmp/evil" to "pwned".toByteArray(Charsets.UTF_8)
        )

        val result = useCase.execute(ByteArrayInputStream(archive))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    private fun manifest(
        databaseVersion: Int,
        appName: String = "Oto"
    ): BackupManifest =
        BackupManifest(
            appName = appName,
            packageName = "com.viel.oto",
            versionName = "1.0",
            versionCode = 1L,
            libraryRoots = emptyList(),
            exportedAt = "2026-06-27 00:00:00",
            databaseVersion = databaseVersion
        )

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
