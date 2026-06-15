package com.viel.aplayer.application.usecase

import android.content.Context
import androidx.core.content.edit
import com.viel.aplayer.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

// Title: Import User Data UseCase (UseCase responsible for closing resources and overwriting database/settings from a ZIP input stream)
// Validates paths against Zip Slip vulnerabilities, terminates active Room instances, and replaces data files.
class ImportUserDataUseCase(private val context: Context) {
    suspend fun execute(inputStream: InputStream): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 1. Close Active Database Connection
            AppDatabase.closeInstance()

            // 2. Clear SharedPreferences Cache
            // Clear memory cache to prevent system from rewriting stale memory cache on app process termination.
            context.getSharedPreferences("webdav_credentials", Context.MODE_PRIVATE).edit(commit = true) { clear() }

            // 3. Extract and Overwrite Files
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
                            // Zip Slip Vulnerability Protection
                            // Validates target path is strictly inside app data package sandbox directory to prevent directory traversal.
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
}
