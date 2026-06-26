package com.viel.oto.ui.common

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.core.net.toUri
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import com.viel.oto.logger.CacheDiagnosticsLogger
import com.viel.oto.logger.CoverImageCacheLogger
import java.io.File

/**
 * Each variant has a distinct cache key segment and target decoding dimensions, preventing duplicate Bitmaps of the same cover in different pages caused by manual keys
 * or slight dimension discrepancies, and ensuring small-image scenes do not inadvertently hold onto full-size main cover Bitmaps.
 */
enum class CoverImageVariant(
    val keySegment: String,
    val targetWidth: Int?,
    val targetHeight: Int?
) {
    Original("original", null, null),
    ThumbnailSmall("small180", 180, 180),
    ThumbnailMedium("medium360", 360, 360),
    Backdrop("small180", 180, 180),
    Main1200("main1200", 1200, 1200);

    /**
     * Formats bounded variants as pixels and the source-sized variant as original.
     * Keeps diagnostics readable after adding an unbounded source-size decode path.
     */
    val requestSizeLabel: String
        get() = if (targetWidth == null || targetHeight == null) {
            "original"
        } else {
            "${targetWidth}x$targetHeight"
        }
}

/**
 * This object is solely responsible for request specifications, cache keys, and basic logging. It does not hold UI states or access the database, preventing this image loading
 * entry-point utility from bloating into a cross-hierarchy centralized manager.
 */
object CoverImageRequestFactory {
    /**
     * Builds a Coil request for a cover display surface.
     *
     * The request owns cache keys, decode sizing, and metrics hooks only; visual transitions are
     * intentionally left outside this factory.
     */
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
            targetSize = variant.requestSizeLabel
        )
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
            .apply {
                val targetWidth = variant.targetWidth
                val targetHeight = variant.targetHeight
                if (targetWidth == null || targetHeight == null) {
                    size(Size.ORIGINAL)
                } else {
                    size(targetWidth, targetHeight)
                }
            }
            .listener(pipelineListener)
            .apply {
                if (bitmapConfig != null) {
                    bitmapConfig(bitmapConfig)
                }
            }
            .build()
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
