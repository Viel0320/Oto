package com.viel.aplayer.library.vfs.cache

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * VFS Range Cache (Stores small bounded range-read blocks on local disk)
 * Owns only metadata-sized byte blocks under the app cache directory and never opens playback streams or provider-native
 * handles.
 */
class VfsRangeCache(
    private val cacheDir: File,
    private val maxBlockBytes: Int = MAX_BLOCK_BYTES,
    private val maxTotalBytes: Long = MAX_TOTAL_BYTES
) {
    constructor(
        context: Context,
        maxBlockBytes: Int = MAX_BLOCK_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES
    ) : this(
        cacheDir = File(context.applicationContext.cacheDir, CACHE_DIR_NAME),
        maxBlockBytes = maxBlockBytes,
        maxTotalBytes = maxTotalBytes
    )

    /**
     * Read Block (Loads a cached range block when its hashed key file exists)
     * Updates the file timestamp on successful reads so trimToSize can keep recently reused metadata blocks longer.
     */
    suspend fun read(key: VfsRangeCacheKey): ByteArray? = withContext(Dispatchers.IO) {
        val file = key.toCacheFile()
        if (!file.exists() || !file.isFile) return@withContext null
        runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    /**
     * Write Block (Persists one small range block using a temporary file and atomic replacement)
     * Ignores oversized blocks and empty writes so playback-sized payloads cannot accidentally populate the metadata cache.
     */
    suspend fun write(key: VfsRangeCacheKey, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        if (bytes.isEmpty() || bytes.size > maxBlockBytes) return@withContext
        runCatching {
            cacheDir.mkdirs()
            val target = key.toCacheFile()
            val temp = File(cacheDir, "${target.name}.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
            trimToSizeBlocking()
        }
    }

    /**
     * Evict Root (Deletes range blocks associated with one hashed root id)
     * Matches only the root hash prefix produced by VfsRangeCacheKey, preventing unrelated roots from sharing cleanup scope.
     */
    suspend fun evictRoot(rootIdHash: String): Int = withContext(Dispatchers.IO) {
        if (!cacheDir.exists()) return@withContext 0
        cacheDir.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.startsWith("${rootIdHash}_") }
            .count { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    /**
     * Trim To Size (Applies the global cache budget by removing oldest blocks first)
     * Keeps the cache bounded to the documented 64MB default without requiring a database index or background worker.
     */
    suspend fun trimToSize(): Unit = withContext(Dispatchers.IO) {
        trimToSizeBlocking()
    }

    private fun VfsRangeCacheKey.toCacheFile(): File =
        File(cacheDir, toFileName())

    private fun trimToSizeBlocking() {
        val files = cacheDir.listFiles()
            .orEmpty()
            .filter { file -> file.isFile && file.name.endsWith(".bin") }
            .sortedBy { file -> file.lastModified() }
            .toMutableList()
        var totalBytes = files.sumOf { file -> file.length() }
        while (totalBytes > maxTotalBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            val length = oldest.length()
            if (runCatching { oldest.delete() }.getOrDefault(false)) {
                totalBytes -= length
            }
        }
    }

    private companion object {
        private const val CACHE_DIR_NAME = "vfs_range_cache"
        private const val MAX_BLOCK_BYTES = 64 * 1024
        private const val MAX_TOTAL_BYTES = 64L * 1024L * 1024L
    }
}
