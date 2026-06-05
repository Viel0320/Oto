package com.viel.aplayer.library.vfs.cache

import android.os.SystemClock
import com.viel.aplayer.library.vfs.VfsNode
import com.viel.aplayer.logger.CacheDiagnosticsLogger

/**
 * Cached Range Reader (Decorates metadata-sized VFS range reads with a bounded disk cache)
 * Applies only to provider-supported bounded readRange calls and deliberately avoids openInputStream and playback offset
 * streams.
 */
class CachedRangeReader(
    private val rangeCache: VfsRangeCache,
    private val supportsRangeRead: (VfsNode) -> Boolean,
    private val readRange: suspend (VfsNode, Long, Int) -> ByteArray?,
    private val elapsedRealtimeMillis: () -> Long = { SystemClock.elapsedRealtime() }
) {
    /**
     * Read (Serves a bounded range from disk cache or delegates to the provider)
     * Validates provider capability and range-key eligibility before touching disk, then writes only successful small blocks.
     */
    suspend fun read(file: VfsNode, offset: Long, length: Int): ByteArray? {
        if (!supportsRangeRead(file)) {
            return readRange(file, offset, length)
        }
        val key = VfsRangeCacheKey.from(file, offset, length)
            ?: return readRange(file, offset, length)
        val sourceHash = "${key.rootIdHash}:${key.sourcePathHash}"
        val readStartedAt = elapsedRealtimeMillis()
        rangeCache.read(key)?.let { bytes ->
            CacheDiagnosticsLogger.logCacheEvent(
                cacheType = "range",
                operation = "readRange",
                hit = true,
                costMs = elapsedRealtimeMillis() - readStartedAt,
                sourceHash = sourceHash,
                sizeBytes = bytes.size.toLong()
            )
            return bytes
        }

        CacheDiagnosticsLogger.logCacheEvent(
            cacheType = "range",
            operation = "readRange",
            hit = false,
            costMs = elapsedRealtimeMillis() - readStartedAt,
            sourceHash = sourceHash,
            sizeBytes = length.toLong()
        )
        val delegateStartedAt = elapsedRealtimeMillis()
        val bytes = readRange(file, offset, length) ?: return null
        rangeCache.write(key, bytes)
        CacheDiagnosticsLogger.logCacheEvent(
            cacheType = "range",
            operation = "writeRange",
            hit = null,
            costMs = elapsedRealtimeMillis() - delegateStartedAt,
            sourceHash = sourceHash,
            sizeBytes = bytes.size.toLong()
        )
        return bytes
    }
}
