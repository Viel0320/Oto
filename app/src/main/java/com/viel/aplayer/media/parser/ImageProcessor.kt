package com.viel.aplayer.media.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 图像处理中心。
 *
 * 当前把封面相关的真正实现集中收口到这里，
 * 包括：
 * 1. 自定义封面保存
 * 2. 内嵌封面字节落盘
 * 3. sidecar 图片流处理
 * 4. 缩略图生成
 * 5. 主色提取
 * 6. Bitmap 基础工具
 *
 * `CoverExtractor` 现在只保留兼容外壳与结果类型，不再承载实现主体。
 */
object ImageProcessor {
    private const val TAG = "ImageProcessor"
    private const val DEFAULT_THUMBNAIL_MAX_SIZE = 300

    // 主色提取代价较高，这里保留路径级 LRU 缓存，避免同一张封面重复跑 Palette。
    private val colorCache = LruCache<String, Int>(100)

    // 默认背景色用于封面缺失、图片损坏或取色失败时的全局兜底。
    const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF1C1B1F.toInt()

    // -------------------------------------------------------------------------
    // 一、封面文件工作流
    // -------------------------------------------------------------------------

    /**
     * 保存用户手动选择的自定义封面。
     *
     * 
     * 1. 把编辑页生成的临时图片复制到私有 `cache/covers`
     * 2. 基于正式文件生成缩略图
     * 3. 提取主色并一起返回
     */
    suspend fun saveCustomCover(
        context: Context,
        bookId: String,
        tempCoverPath: String
    ): CoverExtractor.CoverResult = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(tempCoverPath)
            if (!tempFile.exists()) return@withContext CoverExtractor.CoverResult(null, null)

            val timestamp = System.currentTimeMillis()
            val originalFile = File(coverCacheDir(context), "${bookId.hashCode()}_custom_${timestamp}_orig.jpg")
            originalFile.parentFile?.mkdirs()
            tempFile.copyTo(originalFile, overwrite = true)

            val thumbPath = createThumbnailFromFile(originalFile, "${bookId}_custom_${timestamp}")
            val color = getDominantColor(thumbPath ?: originalFile.absolutePath)
            CoverExtractor.CoverResult(originalFile.absolutePath, thumbPath, color)
        } catch (e: Exception) {
            Log.e(TAG, "保存有声书 $bookId 的自定义封面失败", e)
            CoverExtractor.CoverResult(null, null)
        }
    }

    /**
     * 保存内嵌封面字节。
     *
     * 
     * 这里接住各格式 parser 统一返回的 `embeddedCover.bytes`，
     * 负责原图落地、缩略图生成和主色提取。
     */
    suspend fun saveEmbeddedImage(
        context: Context,
        sourceId: String,
        artBytes: ByteArray
    ): CoverExtractor.CoverResult = withContext(Dispatchers.IO) {
        try {
            if (artBytes.isEmpty()) return@withContext CoverExtractor.CoverResult(null, null)

            val originalFile = File(coverCacheDir(context), "${sourceId.hashCode()}_orig.jpg")
            originalFile.parentFile?.mkdirs()
            FileOutputStream(originalFile).use { output -> output.write(artBytes) }
            val originalPath = originalFile.absolutePath

            val thumbnailPath = createThumbnailFromBytes(context, artBytes, sourceId)
            val color = getDominantColor(thumbnailPath ?: originalPath)
            CoverExtractor.CoverResult(originalPath, thumbnailPath, color)
        } catch (e: Exception) {
            Log.e(TAG, "保存内嵌封面字节失败", e)
            CoverExtractor.CoverResult(null, null)
        }
    }

    /**
     * 处理外部 sidecar 图片流。
     *
     * 
     * 这里故意采用“先复制到私有缓存，再从缓存文件生成缩略图”的顺序，
     * 避免对大图直接 `readBytes()` 带来的瞬时内存峰值。
     */
    suspend fun processExternalImage(
        context: Context,
        sourceId: String,
        openStream: suspend () -> InputStream?
    ): CoverExtractor.CoverResult = withContext(Dispatchers.IO) {
        try {
            val originalFile = File(coverCacheDir(context), "${sourceId.hashCode()}_ext_orig.jpg")
            originalFile.parentFile?.mkdirs()
            openStream()?.use { input ->
                FileOutputStream(originalFile).use { output -> input.copyTo(output) }
            } ?: return@withContext CoverExtractor.CoverResult(null, null)

            val thumbPath = createThumbnailFromFile(originalFile, sourceId)
            val color = getDominantColor(thumbPath ?: originalFile.absolutePath)
            CoverExtractor.CoverResult(originalFile.absolutePath, thumbPath, color)
        } catch (e: Exception) {
            Log.e(TAG, "处理外部 sidecar 图片失败", e)
            CoverExtractor.CoverResult(null, null)
        }
    }

    // -------------------------------------------------------------------------
    // 二、颜色分析
    // -------------------------------------------------------------------------

    /**
     * 从图片路径提取主色。
     *
     * 
     * 这里只解码缩小后的 Bitmap 给 Palette，用最小成本换取足够稳定的背景色。
     */
    suspend fun getDominantColor(path: String?): Int = withContext(Dispatchers.IO) {
        if (path == null) return@withContext DEFAULT_BACKGROUND_ARGB

        colorCache[path]?.let { cached -> return@withContext cached }

        try {
            val file = File(path)
            if (!file.exists()) return@withContext DEFAULT_BACKGROUND_ARGB

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, 100, 100)
            }

            val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext DEFAULT_BACKGROUND_ARGB
            val color = Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
            bitmap.recycle()

            colorCache.put(path, color)
            return@withContext color
        } catch (e: Exception) {
            Log.e(TAG, "提取主色失败: $path", e)
            return@withContext DEFAULT_BACKGROUND_ARGB
        }
    }

    // -------------------------------------------------------------------------
    // 三、Bitmap 通用工具
    // -------------------------------------------------------------------------

    /**
     * 按最大边缩放 Bitmap，保持宽高比。
     */
    fun scaleBitmap(source: Bitmap, maxSize: Int): Bitmap {
        val width = source.width
        val height = source.height
        val ratio = width.toFloat() / height.toFloat()
        val newWidth: Int
        val newHeight: Int
        if (width > height) {
            newWidth = maxSize
            newHeight = (maxSize / ratio).toInt()
        } else {
            newHeight = maxSize
            newWidth = (maxSize * ratio).toInt()
        }
        return source.scale(newWidth, newHeight)
    }

    /**
     * 计算 BitmapFactory 采样倍率。
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if ((height > reqHeight) || (width > reqWidth)) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * 把 Bitmap 保存为 JPEG 文件。
     */
    fun saveBitmapToFile(bitmap: Bitmap, outputFile: File, quality: Int = 85): Boolean {
        return try {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存位图失败: ${outputFile.absolutePath}", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // 四、缩略图内部实现
    // -------------------------------------------------------------------------

    /**
     * 从内存字节生成缩略图。
     *
     * 
     * 适用于已经由 parser 解出的内嵌封面字节。
     */
    private fun createThumbnailFromBytes(
        context: Context,
        bytes: ByteArray,
        sourceId: String
    ): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            options.inSampleSize = calculateInSampleSize(
                options = options,
                reqWidth = DEFAULT_THUMBNAIL_MAX_SIZE,
                reqHeight = DEFAULT_THUMBNAIL_MAX_SIZE
            )
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scaledBitmap = if (bitmap.width > DEFAULT_THUMBNAIL_MAX_SIZE || bitmap.height > DEFAULT_THUMBNAIL_MAX_SIZE) {
                scaleBitmap(bitmap, DEFAULT_THUMBNAIL_MAX_SIZE)
            } else {
                bitmap
            }

            val thumbFile = File(coverCacheDir(context), "${sourceId.hashCode()}_thumb.jpg")
            thumbFile.parentFile?.mkdirs()
            saveBitmapToFile(scaledBitmap, thumbFile)

            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "从字节生成缩略图失败", e)
            null
        }
    }

    /**
     * 从已有文件生成缩略图。
     *
     * 
     * 适用于用户自定义封面和 sidecar 图片，不必先把文件整体再读进内存。
     */
    private fun createThumbnailFromFile(imageFile: File, sourceId: String): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)

            options.inSampleSize = calculateInSampleSize(
                options = options,
                reqWidth = DEFAULT_THUMBNAIL_MAX_SIZE,
                reqHeight = DEFAULT_THUMBNAIL_MAX_SIZE
            )
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: return null
            val scaledBitmap = if (bitmap.width > DEFAULT_THUMBNAIL_MAX_SIZE || bitmap.height > DEFAULT_THUMBNAIL_MAX_SIZE) {
                scaleBitmap(bitmap, DEFAULT_THUMBNAIL_MAX_SIZE)
            } else {
                bitmap
            }

            val thumbDir = imageFile.parentFile ?: return null
            val thumbFile = File(thumbDir, "${sourceId.hashCode()}_thumb.jpg")
            thumbFile.parentFile?.mkdirs()
            saveBitmapToFile(scaledBitmap, thumbFile)

            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "从文件生成缩略图失败", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // 五、目录辅助
    // -------------------------------------------------------------------------

    /**
     * 定位封面缓存目录。
     */
    private fun coverCacheDir(context: Context): File =
        File(context.cacheDir, "covers").also { it.mkdirs() }

}
