package com.viel.aplayer.widget

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 桌面小组件专用封面位图渲染器。
 *
 * 小组件封面最终会通过 Glance/RemoteViews 进入跨进程更新链路，因此这里固定输出小尺寸软件 Bitmap，
 * 不复用播放器主封面的 1200px 规格，也不把磁盘探测和采样计算散落在 Widget 组合函数里。
 */
internal object WidgetCoverArtRenderer {
    private const val TAG = "WidgetCoverArtRenderer"
    private const val TARGET_MAX_SIZE = 120
    private const val BLUR_RADIUS = 8
    private const val BLUR_PASSES = 2

    // 小组件通常只展示当前播放书籍的封面，保留最近一次解码结果即可覆盖播放/暂停等高频状态刷新场景。
    // 缓存内容保存的是已经缩小并模糊后的最终背景 Bitmap，缓存键同时纳入修改时间和文件大小，避免封面文件被原路径覆盖后继续复用旧图。
    @Volatile
    private var latestCover: CachedCover? = null

    /**
     * 在 IO 线程读取、下采样并模糊封面文件，返回适合小组件背景直接展示的小尺寸 Bitmap。
     *
     * 返回值保持可为空：封面路径为空、文件不存在或解码失败时，调用方继续使用内置占位图。
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

        // 命中最近封面时直接复用已缩小且已模糊的软件位图，避免播放状态刷新反复触发 BitmapFactory 解码和像素处理。
        latestCover
            ?.takeIf { it.key == cacheKey && !it.bitmap.isRecycled }
            ?.let { return it.bitmap }

        return try {
            val bounds = BitmapFactory.Options().apply {
                // 先只读取宽高，避免为了计算采样率提前分配大图像素内存。
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
                // 小组件背景有深色蒙层覆盖，不需要透明通道；RGB_565 可把跨进程 Bitmap 体积降到 ARGB_8888 的一半。
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
            Log.w(TAG, "decode widget cover skipped because bitmap memory is insufficient: ${file.name}", oom)
            null
        } catch (error: Exception) {
            Log.w(TAG, "decode widget cover failed: ${file.name}", error)
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        val maxEdge = maxOf(width, height)
        // 以最大边作为采样约束，避免横向或纵向极端长图因为短边较小而跳过下采样。
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
        val scaled = Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
        if (scaled !== this) {
            recycle()
        }
        return scaled
    }

    private fun Bitmap.blurForWidgetBackground(): Bitmap {
        if (width <= 1 || height <= 1) {
            // 极小图没有可见模糊收益，直接转成不可变 RGB_565，保持后续跨进程传输的位图格式一致。
            return copy(Bitmap.Config.RGB_565, false)
        }

        val sourcePixels = IntArray(width * height)
        getPixels(sourcePixels, 0, width, 0, 0, width, height)

        // 小组件封面已经被压到 120px 级别，两轮 box blur 可以用很低成本得到接近高斯模糊的背景观感。
        var blurredPixels = sourcePixels
        repeat(BLUR_PASSES) {
            blurredPixels = blurredPixels.boxBlur(width, height, BLUR_RADIUS)
        }

        val mutableBlurred = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        mutableBlurred.setPixels(blurredPixels, 0, width, 0, 0, width, height)

        // Glance/RemoteViews 只需要展示成品背景，不需要再被修改；转成不可变 Bitmap 可降低后续被误写像素的风险。
        val immutableBlurred = mutableBlurred.copy(Bitmap.Config.RGB_565, false)
        mutableBlurred.recycle()
        return immutableBlurred
    }

    private fun IntArray.boxBlur(width: Int, height: Int, radius: Int): IntArray {
        val horizontal = IntArray(size)
        val output = IntArray(size)

        // 先横向平均每个像素周围的颜色，单独拆一遍可让模糊半径保持可控，避免直接二维卷积带来的额外计算量。
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                horizontal[rowStart + x] = averageHorizontalPixel(rowStart, x, width, radius)
            }
        }

        // 再纵向平均横向结果，两段一维模糊组合成稳定的背景柔化效果，足够覆盖 widget 尺寸下的封面细节。
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
