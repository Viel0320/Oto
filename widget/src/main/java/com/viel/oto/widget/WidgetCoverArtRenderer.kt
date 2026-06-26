package com.viel.oto.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.viel.oto.logger.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Desktop widget cover bitmap renderer.
 *
 * Widget covers are updated cross-process via Glance/RemoteViews. Therefore, this renderer strictly outputs a small software Bitmap,
 * instead of reusing the 1200px specification from the main player cover, avoiding disk checks and sampling calculations scattered within Widget compositions.
 */
internal object WidgetCoverArtRenderer {
    private const val TAG = "WidgetCoverArtRenderer"
    private const val TARGET_MAX_SIZE = 180
    private const val BLUR_RADIUS = 8
    private const val BLUR_PASSES = 2

    @Volatile
    private var latestCover: CachedCover? = null

    /**
     * Load cover bitmap. Decodes, downsamples, and blurs the cover file on the IO dispatcher, returning a compact Bitmap suitable for widget background presentation.
     *
     * Returns null if the cover path is blank, the file does not exist, or decoding fails, letting the caller fallback to the default placeholder.
     */
    suspend fun loadCoverBitmap(coverPath: String?): Bitmap? = withContext(Dispatchers.IO) {
        decodeBoundedBitmap(coverPath)
    }

    private fun decodeBoundedBitmap(coverPath: String?): Bitmap? {
        if (coverPath.isNullOrBlank()) return null

        val file = File(coverPath)
        if (!file.exists()) return null
        val cacheKey = WidgetCoverCacheKey(
            path = file.absolutePath,
            lastModified = file.lastModified(),
            byteLength = file.length()
        )

        latestCover
            ?.takeIf { it.key == cacheKey && !it.bitmap.isRecycled }
            ?.let { return it.bitmap }

        return try {
            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            val bounded = decoded.scaleDownToWidgetBound()
            val blurred = bounded.blurForWidgetBackground()
            if (blurred !== bounded) {
                bounded.recycle()
            }
            latestCover = CachedCover(cacheKey, blurred)
            blurred
        } catch (oom: OutOfMemoryError) {
            SecureLog.warn(TAG, "decode widget cover skipped because bitmap memory is insufficient: ${file.name}", oom)
            null
        } catch (error: Exception) {
            SecureLog.warn(TAG, "decode widget cover failed: ${file.name}", error)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxEdge = maxOf(width, height)
        while (maxEdge / (sampleSize * 2) >= TARGET_MAX_SIZE) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun Bitmap.scaleDownToWidgetBound(): Bitmap {
        val maxEdge = maxOf(width, height)
        if (maxEdge <= TARGET_MAX_SIZE) return this

        val scale = TARGET_MAX_SIZE.toFloat() / maxEdge.toFloat()
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        val scaled = this.scale(targetWidth, targetHeight)
        if (scaled !== this) {
            recycle()
        }
        return scaled
    }

    private fun Bitmap.blurForWidgetBackground(): Bitmap {
        if (width <= 1 || height <= 1) {
            return copy(Bitmap.Config.RGB_565, false)
        }

        val sourcePixels = IntArray(width * height)
        getPixels(sourcePixels, 0, width, 0, 0, width, height)

        var blurredPixels = sourcePixels
        repeat(BLUR_PASSES) {
            blurredPixels = blurredPixels.boxBlur(width, height, BLUR_RADIUS)
        }

        val mutableBlurred = createBitmap(width, height, Bitmap.Config.RGB_565)
        mutableBlurred.setPixels(blurredPixels, 0, width, 0, 0, width, height)

        val immutableBlurred = mutableBlurred.copy(Bitmap.Config.RGB_565, false)
        mutableBlurred.recycle()
        return immutableBlurred
    }

    private fun IntArray.boxBlur(width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(size)
        val output = IntArray(size)

        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                horizontal[rowStart + x] = averageHorizontalPixel(rowStart, x, width, radius)
            }
        }

        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                output[rowStart + x] = horizontal.averageVerticalPixel(x, y, width, height, radius)
            }
        }

        return output
    }

    private fun IntArray.averageHorizontalPixel(rowStart: Int, centerX: Int, width: Int, radius: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0

        val left = (centerX - radius).coerceAtLeast(0)
        val right = (centerX + radius).coerceAtMost(width - 1)
        for (x in left..right) {
            val pixel = this[rowStart + x]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }

        return composeOpaquePixel(red / count, green / count, blue / count)
    }

    private fun IntArray.averageVerticalPixel(centerX: Int, centerY: Int, width: Int, height: Int, radius: Int): Int {
        var red = 0
        var green = 0
        var blue = 0
        var count = 0

        val top = (centerY - radius).coerceAtLeast(0)
        val bottom = (centerY + radius).coerceAtMost(height - 1)
        for (y in top..bottom) {
            val pixel = this[y * width + centerX]
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
            count++
        }

        return composeOpaquePixel(red / count, green / count, blue / count)
    }

    private fun composeOpaquePixel(red: Int, green: Int, blue: Int): Int {
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private data class WidgetCoverCacheKey(
        val path: String,
        val lastModified: Long,
        val byteLength: Long
    )

    private data class CachedCover(
        val key: WidgetCoverCacheKey,
        val bitmap: Bitmap
    )
}
