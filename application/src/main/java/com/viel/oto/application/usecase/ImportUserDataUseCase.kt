package com.viel.oto.application.usecase

import android.content.Context
import com.squareup.moshi.Moshi
import com.viel.oto.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

class ImportUserDataUseCase(
    private val context: Context,
    private val closeDatabaseForRestore: () -> Unit = {}
) {
    private val manifestAdapter = Moshi.Builder().build().adapter(BackupManifest::class.java)

    /**
     * Exposes the current restore schema version from the application boundary.
     *
     * Settings UI uses this value only for localized feedback, while the database-version source remains
     * owned by the import use case instead of leaking the Room database class into UI code.
     */
    val currentDatabaseVersion: Int
        get() = AppDatabase.VERSION

    /**
     * Checks whether a backup manifest can be restored by this app build.
     */
    fun isManifestCompatible(manifest: BackupManifest): Boolean =
        manifest.databaseVersion <= currentDatabaseVersion

    /**
     * Reads only the optional backup manifest without extracting payload files.
     *
     * Malformed ZIP content or malformed manifest JSON returns null so the UI can still show the
     * overwrite confirmation and let the full import path report the real restore failure after consent.
     */
    suspend fun peekManifest(inputStream: InputStream): BackupManifest? =
        withContext(Dispatchers.IO) {
            runCatching {
                ZipInputStream(inputStream).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "manifest.json") {
                            return@runCatching manifestAdapter.fromJson(
                                zis.readBytes().toString(Charsets.UTF_8)
                            )
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                    null
                }
            }.getOrNull()
        }

    suspend fun execute(inputStream: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            closeDatabaseForRestore()

            deleteWebDavCredentialRestoreTargets()

            ZipInputStream(inputStream).use { zis ->
                var entry = zis.nextEntry
                val baseDataDir = context.dataDir.canonicalPath
                while (entry != null) {
                    val path = entry.name
                    if (!entry.isDirectory) {
                        val targetFile = when {
                            path.startsWith("database/") -> {
                                val name = path.substringAfter("database/")
                                context.getDatabasePath(name)
                            }
                            path.startsWith("datastore/") -> {
                                val name = path.substringAfter("datastore/")
                                File(context.filesDir, "datastore/$name")
                            }
                            path.startsWith("shared_prefs/") -> {
                                val name = path.substringAfter("shared_prefs/")
                                File(context.filesDir.parentFile, "shared_prefs/$name")
                            }
                            else -> null
                        }

                        if (targetFile != null) {
                            val canonicalPath = targetFile.canonicalPath
                            if (!canonicalPath.startsWith(baseDataDir)) {
                                throw SecurityException("Security violation: ZIP entry tried to write outside sandbox: $path")
                            }
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { output ->
                                zis.copyTo(output)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
    }

    /**
     * Removes both the migrated DataStore file and the legacy WebDAV preferences file before restore.
     *
     * The subsequent ZIP extraction can then restore either the new DataStore payload or an older
     * shared_prefs payload, which will be migrated by WebDavCredentialStore on the next app start.
     */
    private fun deleteWebDavCredentialRestoreTargets() {
        File(context.filesDir, "datastore/webdav_credentials.preferences_pb").delete()
        File(context.filesDir.parentFile, "shared_prefs/webdav_credentials.xml").delete()
    }
}
