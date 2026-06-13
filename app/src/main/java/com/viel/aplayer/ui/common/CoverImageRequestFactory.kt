package com.viel.aplayer.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.viel.aplayer.logger.CacheDiagnosticsLogger
import com.viel.aplayer.logger.CoverImageCacheLogger
import java.io.File
import androidx.core.net.toUri

/**
 * Cover display specification (CoverImageVariant).
 *
 * Each variant has a distinct cache key segment and target decoding dimensions, preventing duplicate Bitmaps of the same cover in different pages caused by manual keys
 * or slight dimension discrepancies, and ensuring small-image scenes do not inadvertently hold onto full-size main cover Bitmaps.
 */
enum class CoverImageVariant(
    val keySegment: String,
    val targetWidth: Int,
    val targetHeight: Int
) {
    ThumbnailSmall("small180", 180, 180),
    ThumbnailMedium("medium360", 360, 360),
    // Backdrop Cache Coalescing (Reuse the exact small180 request key)
    // Backdrop keeps the same 180px dimensions and cache key as ThumbnailSmall so blurred ambience, list thumbnails, mini-player covers, and transition preloads reuse one Coil cache entry; the request scene still identifies background usage in logs.
    Backdrop("small180", 180, 180),
    Main1200("main1200", 1200, 1200)
}

/**
 * Unified generation of Coil cover requests (CoverImageRequestFactory).
 *
 * This object is solely responsible for request specifications, cache keys, and basic logging. It does not hold UI states or access the database, preventing this image loading
 * entry-point utility from bloating into a cross-hierarchy centralized manager.
 */
object CoverImageRequestFactory {
    // Modify CoverImageRequestFactory build signature (Support Bitmap.Config specification)
    // Accept an optional bitmapConfig parameter to customize the loaded Bitmap's format (e.g. RGB_565 for memory reduction).
    fun build(
        context: Context,
        sourcePath: String,
        lastUpdated: Long,
        variant: CoverImageVariant,
        scene: String,
        allowHardware: Boolean = true,
        bitmapConfig: Bitmap.Config? = null
    ): ImageRequest {
        val cacheKey = cacheKey(sourcePath, lastUpdated, variant)
        CoverImageCacheLogger.logRequest(
            scene = scene,
            variant = variant.keySegment,
            source = sourcePath,
            cacheKey = cacheKey,
            targetWidth = variant.targetWidth,
            targetHeight = variant.targetHeight
        )
        // Bind metric logs directly to the request listener.
        //
        // Here we bind the "document-required metrics logs" directly to the request-level listener,
        // avoiding metric occurrences depending on whether Compose hit a global singleton ImageLoader by default.
        // As long as this ImageRequest is actually executed, the final metrics will always be outputted; the global EventListener shifts back to
        // an auxiliary role providing fetcher clues to distinguish between disk cache hits and local file decoding as much as possible.
        val pipelineListener = object : ImageRequest.Listener {
            private var startedAtElapsedMs = SystemClock.elapsedRealtime()

            override fun onStart(request: ImageRequest) {
                startedAtElapsedMs = SystemClock.elapsedRealtime()
            }

            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                val decodeSource = CoverImageCacheLogger.normalizeDecodeSource(
                    dataSource = result.dataSource.name,
                    fetcherClassName = CoverImageCacheLogger.takeFetcherClass(request)
                )
                val metricsSnapshot = CoverImageCacheLogger.recordSuccessMetric(
                    scene = scene,
                    variant = variant.keySegment,
                    decodeSource = decodeSource
                )
                val decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
                CoverImageCacheLogger.logPipelineSuccess(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeSource = decodeSource,
                    decodeCostMs = decodeCostMs,
                    width = result.drawable.intrinsicWidth,
                    height = result.drawable.intrinsicHeight,
                    bitmapByteCount = CoverImageCacheLogger.bitmapByteCount(result.drawable),
                    metricsSnapshot = metricsSnapshot
                )
                // Cover Decode Cache Diagnostics (Adds a normalized cache event beside existing cover pipeline logs)
                // Uses the existing source hash and decode source classification so summary logs can detect cache hits without printing local cover paths.
                CacheDiagnosticsLogger.logCacheEvent(
                    cacheType = "cover",
                    operation = "decode",
                    hit = decodeSource.toCoverCacheHit(),
                    costMs = decodeCostMs,
                    sourceHash = CoverImageCacheLogger.hashSource(sourcePath),
                    sizeBytes = CoverImageCacheLogger.bitmapByteCount(result.drawable),
                    detail = "scene=$scene, variant=${variant.keySegment}, decodeSource=$decodeSource"
                )
            }

            override fun onError(request: ImageRequest, result: ErrorResult) {
                CoverImageCacheLogger.clearFetcherClass(request)
                CoverImageCacheLogger.logPipelineError(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs,
                    error = result.throwable
                )
            }

            override fun onCancel(request: ImageRequest) {
                CoverImageCacheLogger.clearFetcherClass(request)
                CoverImageCacheLogger.logPipelineCancel(
                    scene = scene,
                    variant = variant.keySegment,
                    cacheKey = cacheKey,
                    source = sourcePath,
                    decodeCostMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
                )
            }
        }
        // Cover Source URI Resolution (Support content URIs and file URIs alongside raw file paths)
        // If the path starts with content:// or file://, we parse it as an android.net.Uri so Coil can resolve ContentResolver or scheme providers.
        // Otherwise, it is treated as a local absolute file path and mapped directly to java.io.File to preserve existing filesystem contracts.
        val dataObject = if (sourcePath.startsWith("content://") || sourcePath.startsWith("file://")) {
            sourcePath.toUri()
        } else {
            File(sourcePath)
        }
        val request = ImageRequest.Builder(context)
            .data(dataObject)
            .memoryCacheKey(cacheKey)
            .diskCacheKey(cacheKey)
            .allowHardware(allowHardware)
            // Crossfade behavior.
            //
            // A single 1200px main cover bitmap takes about 5.8MB of native heap, and Backdrop immediately enters the blur pipeline.
            // These two variants skip crossfade to avoid concurrently holding both the old and new bitmaps in memory during transitions; small images retain transition animations.
            .crossfade(variant != CoverImageVariant.Main1200 && variant != CoverImageVariant.Backdrop)
            .size(variant.targetWidth, variant.targetHeight)
            .listener(pipelineListener)
            // Apply bitmapConfig option (Pass customized bitmap config to Coil builder)
            // Configures the decoding profile, e.g. RGB_565 to optimize RAM overhead and prevent hardware-copying overhead.
            .apply {
                if (bitmapConfig != null) {
                    bitmapConfig(bitmapConfig)
                }
            }
            .build()
        // Register metrics context.
        //
        // Bind the metrics context (scene / variant / source / cacheKey) to the final generated request object.
        // This allows the EventListener of the global ImageLoader to trace back to which UI scene a request belongs after it hits the loader layer,
        // thereby outputting the required metrics: `scene + variant + sourceKeyHash + decodeCostMs + decodeSource + cacheHitRatio`.
        CoverImageCacheLogger.registerRequest(
            request = request,
            scene = scene,
            variant = variant.keySegment,
            source = sourcePath,
            cacheKey = cacheKey
        )
        return request
    }

    fun cacheKey(sourcePath: String, lastUpdated: Long, variant: CoverImageVariant): String =
        "cover:${variant.keySegment}:${CoverImageCacheLogger.hashSource(sourcePath)}:$lastUpdated"
}

private fun String.toCoverCacheHit(): Boolean? =
    when (this) {
        "memory_hit", "disk_hit" -> true
        "file_decode", "network" -> false
        else -> null
    }

/**
 * Cover path selection rules.
 *
 * The UI layer only declares whether it is a small image, main cover, or backdrop scene. The preference of using thumbnails vs original images is centralized here.
 * This improves cache hit rates and prevents list components from repeatedly decoding from original full-size images.
 */
object CoverImageSourceSelector {
    fun small(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath

    fun medium(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath

    fun main(coverPath: String?, thumbnailPath: String?): String? = coverPath ?: thumbnailPath

    fun backdrop(thumbnailPath: String?, coverPath: String?): String? = thumbnailPath ?: coverPath
}
