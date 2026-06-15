package com.viel.aplayer.application.usecase

import android.content.Context
import com.viel.aplayer.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Title: Export User Data UseCase (UseCase responsible for packaging user database and settings into a ZIP file)
// Encapsulates the sequential zipping of Room database, preference DataStore, and SharedPreferences files.
class ExportUserDataUseCase(private val context: Context) {
    suspend fun execute(outputStream: OutputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // Stable Database Snapshot (Close the Room singleton before reading database files)
            // Export must copy the latest on-disk database and WAL state instead of racing an open SQLite connection that may rewrite the file while the ZIP is being built.
            AppDatabase.closeInstance()

            ZipOutputStream(outputStream).use { zos ->
                // 1. Database Files
                val dbName = "aplayer_database"
                val dbFile = context.getDatabasePath(dbName)
                if (dbFile.exists()) {
                    writeToZip(zos, dbFile, "database/$dbName")
                }
                val walFile = context.getDatabasePath("$dbName-wal")
                if (walFile.exists()) {
                    writeToZip(zos, walFile, "database/$dbName-wal")
                }
                val shmFile = context.getDatabasePath("$dbName-shm")
                if (shmFile.exists()) {
                    writeToZip(zos, shmFile, "database/$dbName-shm")
                }

                // 2. DataStore Files
                val datastoreDir = File(context.filesDir, "datastore")
                if (datastoreDir.exists()) {
                    val settingsFile = File(datastoreDir, "app_settings.preferences_pb")
                    if (settingsFile.exists()) {
                        writeToZip(zos, settingsFile, "datastore/app_settings.preferences_pb")
                    }
                    val absCredentialsFile = File(datastoreDir, "abs_credentials.preferences_pb")
                    if (absCredentialsFile.exists()) {
                        writeToZip(zos, absCredentialsFile, "datastore/abs_credentials.preferences_pb")
                    }
                }

                // 3. SharedPreferences Files
                val sharedPrefsDir = File(context.filesDir.parentFile, "shared_prefs")
                if (sharedPrefsDir.exists()) {
                    val webdavPrefs = File(sharedPrefsDir, "webdav_credentials.xml")
                    if (webdavPrefs.exists()) {
                        writeToZip(zos, webdavPrefs, "shared_prefs/webdav_credentials.xml")
                    }
                }
            }
        }
    }

    private fun writeToZip(zos: ZipOutputStream, file: File, zipPath: String) {
        val entry = ZipEntry(zipPath)
        zos.putNextEntry(entry)
        file.inputStream().use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }
}
