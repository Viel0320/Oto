package com.viel.oto.logger

import android.annotation.SuppressLint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.Log
import coil.request.ImageRequest
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Aggregated logging for cover image loading, cache hits, and failures.
 *
 * This object does not perform image loading or business logic operations. It aggregates facts emitted by UI pages,
 * Coil callbacks, and ABS cover handlers into a centralized logging portal to prevent logs from being scattered across components.
 */
object CoverImageCacheLogger {
    private const val TAG = "CoverImageCache"
    private val requestContexts = ConcurrentHashMap<Int, RegisteredRequestContext>()
    private val requestFetcherClasses = ConcurrentHashMap<Int, String>()
    private val statsLock = Any()
    private val sceneVariantStats = linkedMapOf<String, SceneVariantStats>()

    /**
     * Determines whether a cache key represents an audiobook cover image request.
     * The global Coil EventListener only receives the raw image request, so we centralize the namespace checks
     * to avoid duplicate hardcoded string checks in other layers.
     */
    fun isCoverCacheKey(cacheKey: String?): Boolean =
        cacheKey?.startsWith("cover:") == true

    /**
     * Extract the variant segment suffix from the standard cache key format.
     * e.g. small, medium, backdrop, main1200.
     * even when the specific UI class name is missing, facilitating cache performance diagnostics.
     */
    fun variantFromCacheKey(cacheKey: String?): String =
        cacheKey
            ?.takeIf(::isCoverCacheKey)
            ?.split(':')
            ?.getOrNull(1)
            ?: "unknown"

    fun hashSource(source: String?): String {
        if (source.isNullOrBlank()) return "none"
        val normalized = source.trim().replace('\\', '/')
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
        return digest.take(4).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /**
     * Register the UI scene, variant, and source path when the ImageRequest is constructed.
     * Enables the global ImageLoader EventListener to lookup the context from a raw ImageRequest object,
     * ensuring we can accurately log scene-specific metrics.
     */
    fun registerRequest(
        request: ImageRequest,
        scene: String,
        variant: String,
        source: String?,
        cacheKey: String
    ) {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        requestContexts[requestIdentity(request)] = RegisteredRequestContext(
            scene = scene,
            variant = variant,
            source = source,
            cacheKey = cacheKey,
            lastTouchedElapsedMs = nowElapsedMs
        )
        pruneExpiredRequestContexts(nowElapsedMs)
    }

    /**
     * Temporarily store the fetcher class name captured during the fetchStart cycle.
     * The fetcher class name is only accessible during fetchStart, so we hold it until success/error/cancel callbacks
     * trigger to normalize the decode source.
     */
    fun rememberFetcherClass(request: ImageRequest, fetcherClassName: String?) {
        if (fetcherClassName.isNullOrBlank()) return
        requestFetcherClasses[requestIdentity(request)] = fetcherClassName
    }

    /**
     * Retrieve and remove the registered fetcher class name from the cache.
     * Prevents temporary memory-allocated string identifiers from hanging indefinitely after request completion.
     */
    fun takeFetcherClass(request: ImageRequest): String? =
        requestFetcherClasses.remove(requestIdentity(request))

    /**
     * Purge the fetcher class name cache for canceled or failed image request pipelines.
     */
    fun clearFetcherClass(request: ImageRequest) {
        requestFetcherClasses.remove(requestIdentity(request))
    }

    /**
     * Lookup the registered request context from the active request contexts registry.
     * If the lookup fails due to recycling or timing differences, returns a fallback context mapping to "unknown" scene
     * and the variant inferred from the cache key to keep the logging pipeline running.
     */
    fun resolveRequestContext(request: ImageRequest): RequestContext {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val requestId = requestIdentity(request)
        val registered = requestContexts[requestId]
        if (registered != null) {
            requestContexts[requestId] = registered.copy(lastTouchedElapsedMs = nowElapsedMs)
            return RequestContext(
                scene = registered.scene,
                variant = registered.variant,
                source = registered.source,
                cacheKey = registered.cacheKey
            )
        }
        val fallbackCacheKey = request.memoryCacheKey?.toString() ?: request.diskCacheKey
        return RequestContext(
            scene = "unknown",
            variant = variantFromCacheKey(fallbackCacheKey),
            source = request.data.toString(),
            cacheKey = fallbackCacheKey
        )
    }

    /**
     * Records the requested decode size without forcing original-size variants into fake pixel dimensions.
     * The target size is preformatted by the caller so bounded and unbounded requests share one diagnostic path.
     */
    fun logRequest(
        scene: String,
        variant: String,
        source: String?,
        cacheKey: String,
        targetSize: String
    ) {
        Log.d(
            TAG,
            "request: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=$cacheKey, size=$targetSize"
        )
    }

    /**
     * Log successful image loading completion events from the global Coil listener.
     * Records cache hits, load latencies, and Bitmap allocations to check whether images are shared
     * efficiently across different UI screens.
     */
    fun logPipelineSuccess(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeSource: String,
        decodeCostMs: Long,
        width: Int? = null,
        height: Int? = null,
        bitmapByteCount: Long? = null,
        metricsSnapshot: SceneVariantMetricsSnapshot
    ) {
        Log.d(
            TAG,
            "pipeline success: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeSource=$decodeSource, decodeCostMs=$decodeCostMs, drawable=${width ?: -1}x${height ?: -1}, bitmapByteCount=${bitmapByteCount ?: -1L}, cacheHitRatio=memory:${metricsSnapshot.memoryHitRatioPercent}%,disk:${metricsSnapshot.diskHitRatioPercent}%,file:${metricsSnapshot.fileDecodeRatioPercent}%,network:${metricsSnapshot.networkRatioPercent}%,other:${metricsSnapshot.otherRatioPercent}%, total=${metricsSnapshot.totalSuccesses}"
        )
    }

    /**
     * Log image request failure exceptions from the global Coil listener.
     * Combines the cache key, variant, and cost duration to help diagnose decoding or fetching errors
     * without needing to reproduce them on individual screens.
     */
    fun logPipelineError(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeCostMs: Long,
        error: Throwable
    ) {
        SecureLog.error(
            TAG,
            "pipeline error: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeCostMs=$decodeCostMs, error=${error::class.java.simpleName}: ${error.message}",
            error
        )
    }

    /**
     * Log image request cancellation events from the global Coil listener.
     * e.g. from rapid scrolling. from actual cache misses or errors
     * to keep overall reliability statistics accurate.
     */
    fun logPipelineCancel(
        scene: String,
        variant: String,
        cacheKey: String?,
        source: String?,
        decodeCostMs: Long
    ) {
        Log.d(
            TAG,
            "pipeline cancel: scene=$scene, variant=$variant, sourceKeyHash=${hashSource(source)}, key=${cacheKey ?: "none"}, decodeCostMs=$decodeCostMs"
        )
    }

    /**
     * Calculate the approximate heap size allocated for the decoded image.
     * Reads allocationByteCount directly for BitmapDrawable, falling back to a "width * height * 4" estimation
     * for custom drawables to monitor memory footprints.
     */
    fun bitmapByteCount(drawable: Drawable?): Long? {
        val bitmapDrawable = drawable as? BitmapDrawable
        if (bitmapDrawable != null) {
            return bitmapDrawable.bitmap.allocationByteCount.toLong()
        }
        val width = drawable?.intrinsicWidth ?: return null
        val height = drawable.intrinsicHeight
        if (width <= 0 || height <= 0) return null
        return width.toLong() * height.toLong() * 4L
    }

    fun logAbsCoverStreamStart(rootId: String, remoteItemId: String, contentLength: Long?) {
        Log.d(
            TAG,
            "abs stream start: root=${hashSource(rootId)}, item=${hashSource(remoteItemId)}, contentLength=${contentLength ?: -1L}"
        )
    }

    fun logAbsCoverStreamReady(rootId: String, remoteItemId: String, fileSize: Long?) {
        Log.d(
            TAG,
            "abs stream ready: root=${hashSource(rootId)}, item=${hashSource(remoteItemId)}, fileSize=${fileSize ?: -1L}"
        )
    }

    /**
     * Map the low-level Coil DataSource and fetcher type to stable metrics labels.
     * Normalizes the source type into memory_hit, disk_hit, file_decode, or network, helping keep
     * image caching statistics clean and easy to interpret.
     */
    fun normalizeDecodeSource(dataSource: String?, fetcherClassName: String?): String =
        when {
            dataSource.equals("MEMORY_CACHE", ignoreCase = true) || dataSource.equals("MEMORY", ignoreCase = true) -> "memory_hit"
            dataSource.equals("NETWORK", ignoreCase = true) -> "network"
            fetcherClassName?.contains("DiskCache", ignoreCase = true) == true -> "disk_hit"
            dataSource.equals("DISK", ignoreCase = true) -> "file_decode"
            else -> "other"
        }

    /**
     * Records in-memory statistics for the given scene and variant combination.
     * Aggregates hit counters and returns a snapshot of cache efficiency, allowing developers to inspect cache
     * performance patterns inside Logcat.
     */
    fun recordSuccessMetric(
        scene: String,
        variant: String,
        decodeSource: String
    ): SceneVariantMetricsSnapshot = synchronized(statsLock) {
        val statsKey = "$scene|$variant"
        val stats = sceneVariantStats.getOrPut(statsKey) { SceneVariantStats() }
        stats.totalSuccesses += 1
        when (decodeSource) {
            "memory_hit" -> stats.memoryHits += 1
            "disk_hit" -> stats.diskHits += 1
            "file_decode" -> stats.fileDecodes += 1
            "network" -> stats.networkLoads += 1
            else -> stats.otherLoads += 1
        }
        SceneVariantMetricsSnapshot(
            totalSuccesses = stats.totalSuccesses,
            memoryHits = stats.memoryHits,
            diskHits = stats.diskHits,
            fileDecodes = stats.fileDecodes,
            networkLoads = stats.networkLoads,
            otherLoads = stats.otherLoads
        )
    }

    /**
     * Clear stale request contexts to keep the registry map size bounded.
     * Clears contexts that exceed the TTL limits when the cache exceeds capacity, protecting memory bounds
     * without interrupting active requests.
     */
    private fun pruneExpiredRequestContexts(nowElapsedMs: Long) {
        if (requestContexts.size <= MAX_REQUEST_CONTEXTS) return
        requestContexts.entries.removeIf { entry ->
            nowElapsedMs - entry.value.lastTouchedElapsedMs > REQUEST_CONTEXT_TTL_MS
        }
    }

    private fun requestIdentity(request: ImageRequest): Int = System.identityHashCode(request)

    /**
     * Lightweight context structure containing metadata needed for pipeline diagnostics.
     * Avoids holding references to massive ImageRequest objects inside logs or event listener loops.
     */
    data class RequestContext(
        val scene: String,
        val variant: String,
        val source: String?,
        val cacheKey: String?
    )

    /**
     * Internal wrapper logging the registry timestamp to support TTL cleanup sweeps.
     */
    private data class RegisteredRequestContext(
        val scene: String,
        val variant: String,
        val source: String?,
        val cacheKey: String,
        val lastTouchedElapsedMs: Long
    )

    /**
     * Internal statistics counters tracking successful cache allocations.
     */
    private data class SceneVariantStats(
        var totalSuccesses: Int = 0,
        var memoryHits: Int = 0,
        var diskHits: Int = 0,
        var fileDecodes: Int = 0,
        var networkLoads: Int = 0,
        var otherLoads: Int = 0
    )

    /**
     * Immutable stats projection showing cache ratios with single-decimal percentage formats.
     */
    data class SceneVariantMetricsSnapshot(
        val totalSuccesses: Int,
        val memoryHits: Int,
        val diskHits: Int,
        val fileDecodes: Int,
        val networkLoads: Int,
        val otherLoads: Int
    ) {
        val memoryHitRatioPercent: String
            get() = ratioPercent(memoryHits)
        val diskHitRatioPercent: String
            get() = ratioPercent(diskHits)
        val fileDecodeRatioPercent: String
            get() = ratioPercent(fileDecodes)
        val networkRatioPercent: String
            get() = ratioPercent(networkLoads)
        val otherRatioPercent: String
            get() = ratioPercent(otherLoads)

        @SuppressLint("DefaultLocale")
        private fun ratioPercent(value: Int): String {
            if (totalSuccesses <= 0) return "0.0"
            return String.format("%.1f", (value.toDouble() * 100.0) / totalSuccesses.toDouble())
        }
    }

    private const val REQUEST_CONTEXT_TTL_MS = 10 * 60 * 1000L
    private const val MAX_REQUEST_CONTEXTS = 1024
}
