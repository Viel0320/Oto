package com.viel.aplayer.application.usecase

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.squareup.moshi.Moshi
import com.viel.aplayer.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExportUserDataUseCase(private val context: Context) {
    private val manifestAdapter = Moshi.Builder().build().adapter(BackupManifest::class.java)
    private val exportedAtFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * Builds the required backup manifest at the application boundary.
     *
     * Keeping package identity and database schema lookup here prevents settings UI code from depending
     * on the Room database class while still letting the UI provide already formatted library-root labels.
     */
    fun buildManifest(libraryRoots: List<String>): BackupManifest {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            android.content.pm.PackageManager.PackageInfoFlags.of(0L)
        )
        return BackupManifest(
            appName = context.applicationInfo.loadLabel(context.packageManager).toString(),
            packageName = context.packageName,
            versionName = packageInfo.versionName ?: "",
            versionCode = packageInfo.longVersionCode,
            libraryRoots = libraryRoots,
            exportedAt = LocalDateTime.now().format(exportedAtFormatter),
            databaseVersion = AppDatabase.VERSION
        )
    }

    /**
     * Exports a complete backup archive with a required metadata manifest and a sanitized database snapshot.
     *
     * The database snapshot first copies any pending WAL/SHM side files into a temporary SQLite database,
     * removes transient download metadata there, checkpoints the temporary database, and archives only the
     * resulting main database file so restore never mixes sanitized data with stale WAL state.
     */
    suspend fun execute(outputStream: OutputStream, manifest: BackupManifest): Result<Unit> =
        withContext(Dispatchers.IO) {
        runCatching {
            AppDatabase.closeInstance()

            ZipOutputStream(outputStream).use { zos ->
                val manifestEntry = ZipEntry("manifest.json")
                zos.putNextEntry(manifestEntry)
                zos.write(manifestAdapter.toJson(manifest).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                val dbName = "aplayer_database"
                val dbFile = context.getDatabasePath(dbName)
                val walFile = context.getDatabasePath("$dbName-wal")
                val shmFile = context.getDatabasePath("$dbName-shm")
                if (dbFile.exists()) {
                    if (isSQLiteFile(dbFile)) {
                        val tempDbFile = createSanitizedDatabaseSnapshot(dbFile, walFile, shmFile)
                        try {
                            writeToZip(zos, tempDbFile, "database/$dbName")
                        } finally {
                            deleteTempSnapshotFiles(tempDbFile)
                        }
                    } else {
                        writeToZip(zos, dbFile, "database/$dbName")
                        if (walFile.exists()) {
                            writeToZip(zos, walFile, "database/$dbName-wal")
                        }
                        if (shmFile.exists()) {
                            writeToZip(zos, shmFile, "database/$dbName-shm")
                        }
                    }
                }

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

    /**
     * Creates a consistent temporary SQLite database that has transient manual-download rows removed.
     *
     * Copying the WAL and SHM files before opening the temporary database lets SQLite replay pending
     * transactions into the snapshot before the deletion and checkpoint are committed into the main file.
     */
    private fun createSanitizedDatabaseSnapshot(dbFile: File, walFile: File, shmFile: File): File {
        val tempDbFile = File(dbFile.parentFile, "export_temp_db")
        val tempWalFile = File(tempDbFile.parentFile, "${tempDbFile.name}-wal")
        val tempShmFile = File(tempDbFile.parentFile, "${tempDbFile.name}-shm")
        deleteTempSnapshotFiles(tempDbFile)

        dbFile.copyTo(tempDbFile, overwrite = true)
        if (walFile.exists()) {
            walFile.copyTo(tempWalFile, overwrite = true)
        }
        if (shmFile.exists()) {
            shmFile.copyTo(tempShmFile, overwrite = true)
        }

        SQLiteDatabase.openDatabase(tempDbFile.path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { db ->
                db.execSQL("DELETE FROM download_metadata")
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                    cursor.moveToFirst()
                }
            }

        return tempDbFile
    }

    /**
     * Deletes the temporary SQLite snapshot and any side files SQLite may have created while sanitizing it.
     */
    private fun deleteTempSnapshotFiles(tempDbFile: File) {
        tempDbFile.delete()
        File(tempDbFile.parentFile, "${tempDbFile.name}-wal").delete()
        File(tempDbFile.parentFile, "${tempDbFile.name}-shm").delete()
    }

    private fun writeToZip(zos: ZipOutputStream, file: File, zipPath: String) {
        val entry = ZipEntry(zipPath)
        zos.putNextEntry(entry)
        file.inputStream().use { input ->
            input.copyTo(zos)
        }
        zos.closeEntry()
    }

    /**
     * Detects real SQLite database files before opening them in tests or partially prepared fixtures.
     */
    private fun isSQLiteFile(file: File): Boolean {
        if (file.length() < 16) return false
        return file.inputStream().use { it.readNBytes(16) }
            .take(6).map { it.toInt().and(0xFF) }
            .let { it == listOf(0x53, 0x51, 0x4C, 0x69, 0x74, 0x65) }
    }
}
