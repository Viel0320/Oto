package com.viel.aplayer.media.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import com.viel.aplayer.logger.SecureLog
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
    // cardgroup Thumbnail Size Alignment (Keep medium cover cards and generated thumbnails on the same 360px contract)
    // This lets cardgroup surfaces prefer local thumbnails and matching Coil cache entries, reducing repeated full-size cover decoding.
    private const val DEFAULT_THUMBNAIL_MAX_SIZE = 360

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
            // Release Error Boundary (Sanitize custom cover persistence failures)
            // Cover save failures can expose cache paths through Throwable text, so retained errors use SecureLog.
            SecureLog.error(TAG, "保存有声书 $bookId 的自定义封面失败", e)
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

            // 详尽的中文注释：此处的 artBytes 字节数组在直接物理落盘保存到原图 originalFile 后，其在内存中的主要生命周期即应宣告结束。
            // 我们绝对不再将其重新作为大字节数组传入 createThumbnailFromBytes 去做内存级别的重复解码；
            // 而是统一复用基于物理文件的、具备 inSampleSize 下采样功能的 createThumbnailFromFile 提取缩略图，
            // 从而瞬间清空大位图同步解析时的堆内存瞬时暴涨开销，从源头彻底防御了 OOM 溢出风险。
            val thumbnailPath = createThumbnailFromFile(originalFile, sourceId)
            val color = getDominantColor(thumbnailPath ?: originalPath)
            CoverExtractor.CoverResult(originalPath, thumbnailPath, color)
        } catch (e: Exception) {
            // Release Error Boundary (Sanitize embedded cover persistence failures)
            // Embedded artwork exceptions may include decoder or output path details, so release-retained errors use SecureLog.
            SecureLog.error(TAG, "保存内嵌封面字节失败", e)
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
            // Release Error Boundary (Sanitize sidecar image processing failures)
            // Sidecar file processing crosses user storage, so retained error diagnostics must scrub paths and exception text.
            SecureLog.error(TAG, "处理外部 sidecar 图片失败", e)
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
            // Release Error Boundary (Sanitize dominant-color path failures)
            // The input path can be a local cover cache or user file path, so SecureLog removes it before Logcat output.
            SecureLog.error(TAG, "提取主色失败: $path", e)
            return@withContext DEFAULT_BACKGROUND_ARGB
        }
    }

    /**
     * 将主色存入内存缓存。
     */
    fun putColorToCache(path: String?, color: Int) {
        // Cache Dominant Color: Store calculated color into LRU cache for high-speed synchronous retrieval on subsequent composition rounds.
        if (path != null) {
            Log.d(TAG, "putColorToCache: path=$path, color=${Integer.toHexString(color)}")
            colorCache.put(path, color)
        }
    }

    /**
     * 从内存缓存中同步获取主色。
     */
    fun getCachedColor(path: String?): Int? {
        // Query Cached Color: Retrieve cached color synchronously to prevent layout jump or color flashing during initial composable creation.
        if (path == null) return null
        val cached = colorCache.get(path)
        Log.d(TAG, "getCachedColor: path=$path, result=${cached?.let { Integer.toHexString(it) }}")
        return cached
    }

    /**
     * 从内存 Bitmap 提取主色。
     */
    fun getDominantColorFromBitmap(bitmap: Bitmap?): Int {
        // Add getDominantColorFromBitmap (Extract dominant color directly from memory-based Bitmap) Enable immediate, sync color calculations bypassing disk reads.
        if (bitmap == null) return DEFAULT_BACKGROUND_ARGB
        return try {
            Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
        } catch (e: Exception) {
            // Release Error Boundary (Sanitize bitmap color extraction failures)
            // Palette exceptions are retained only after SecureLog removes any sensitive nested diagnostic text.
            SecureLog.error(TAG, "从 Bitmap 提取主色失败", e)
            DEFAULT_BACKGROUND_ARGB
        }
    }

    /**
     * 从 Drawable 提取主色。
     *
     * 针对 Coil 的 Drawable 进行了深度优化，兼容各种包装类 (CrossfadeDrawable 等) 以及硬件位图 (Hardware Bitmap)。
     */
    fun getDominantColorFromDrawable(drawable: Drawable?): Int {
        // Safe Drawable Extraction: Unpack wrappers and restrict bounds to 100x100 ARGB_8888 to guarantee Palette performance and prevent OOM or Hardware Bitmap crashes.
        if (drawable == null) return DEFAULT_BACKGROUND_ARGB
        return try {
            val unwrapped = unwrapDrawable(drawable) ?: drawable
            Log.d(TAG, "getDominantColorFromDrawable: unwrappedClass=${unwrapped.javaClass.name}")

            // Target Dimensions Calculation: Scale the target width and height to a max of 100x100 to optimize execution times and guard against 0 or negative dimensions.
            var targetWidth = unwrapped.intrinsicWidth
            var targetHeight = unwrapped.intrinsicHeight
            if (targetWidth <= 0 || targetHeight <= 0) {
                targetWidth = 100
                targetHeight = 100
            } else {
                val maxSide = 100
                if (targetWidth > maxSide || targetHeight > maxSide) {
                    val ratio = targetWidth.toFloat() / targetHeight.toFloat()
                    if (targetWidth > targetHeight) {
                        targetWidth = maxSide
                        targetHeight = (maxSide / ratio).toInt().coerceAtLeast(1)
                    } else {
                        targetHeight = maxSide
                        targetWidth = (maxSide * ratio).toInt().coerceAtLeast(1)
                    }
                }
            }

            // ARGB_8888 Drawing Execution: Safely unpack BitmapDrawable and copy hardware bitmap if needed to avoid Software Canvas Hardware Bitmap rendering exceptions.
            val bitmap = try {
                if (unwrapped is BitmapDrawable) {
                    val origBitmap = unwrapped.bitmap
                    if (origBitmap != null) {
                        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                origBitmap.config == Bitmap.Config.HARDWARE
                        val softwareBitmap = if (isHardware) {
                            origBitmap.copy(Bitmap.Config.ARGB_8888, false)
                        } else {
                            origBitmap
                        }

                        if (softwareBitmap != null) {
                            if (softwareBitmap.width > targetWidth || softwareBitmap.height > targetHeight) {
                                val scaled = Bitmap.createScaledBitmap(softwareBitmap, targetWidth, targetHeight, true)
                                if (scaled != softwareBitmap && softwareBitmap != origBitmap) {
                                    softwareBitmap.recycle()
                                }
                                scaled
                            } else {
                                softwareBitmap
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                } else {
                    unwrapped.toBitmap(
                        width = targetWidth,
                        height = targetHeight,
                        config = Bitmap.Config.ARGB_8888
                    )
                }
            } catch (e: Exception) {
                // Release Error Boundary (Sanitize drawable conversion failures)
                // Drawable wrappers can originate from file or network loaders, so retained errors must use SecureLog.
                SecureLog.error(TAG, "Drawable 转换 Bitmap 异常", e)
                null
            } ?: return DEFAULT_BACKGROUND_ARGB

            val color = Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
            Log.d(TAG, "getDominantColorFromDrawable: extractedColor=${Integer.toHexString(color)}")

            // Safe Bitmap Recycling: Only recycle the bitmap if it is a newly allocated instance and not the backing instance of the underlying BitmapDrawable.
            val originalBitmap = (unwrapped as? BitmapDrawable)?.bitmap
            if (bitmap != originalBitmap) {
                bitmap.recycle()
            }

            color
        } catch (e: Exception) {
            // Release Error Boundary (Sanitize drawable color extraction failures)
            // The final color fallback keeps UI stable while SecureLog prevents retained exception text from leaking source data.
            SecureLog.error(TAG, "从 Drawable 提取主色失败", e)
            DEFAULT_BACKGROUND_ARGB
        }
    }

    /**
     * 递归解包包装的 Drawable，如 Coil 的 CrossfadeDrawable 或 LayerDrawable。
     */
    private fun unwrapDrawable(drawable: Drawable?): Drawable? {
        // Recursive Wrapper Unwrapping: Drill down into LayerDrawable, SDK DrawableWrapper, Coil CrossfadeDrawable, or support-library wrappers using a local immutable reference to guarantee smart casts.
        if (drawable == null) return null
        var current = drawable
        while (true) {
            val curr = current ?: break
            when {
                curr is BitmapDrawable -> {
                    return curr
                }
                curr is LayerDrawable -> {
                    if (curr.numberOfLayers > 0) {
                        current = curr.getDrawable(curr.numberOfLayers - 1)
                    } else {
                        break
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && curr is android.graphics.drawable.DrawableWrapper -> {
                    current = curr.drawable
                }
                curr.javaClass.name == "coil.drawable.CrossfadeDrawable" -> {
                    try {
                        val getEndMethod = curr.javaClass.getMethod("getEnd")
                        val endDrawable = getEndMethod.invoke(curr) as? Drawable
                        if (endDrawable != null) {
                            current = endDrawable
                        } else {
                            val endField = curr.javaClass.getDeclaredField("end")
                            endField.isAccessible = true
                            val endDrawableField = endField.get(curr) as? Drawable
                            if (endDrawableField != null) {
                                current = endDrawableField
                            } else {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
                else -> {
                    try {
                        val getWrappedDrawableMethod = curr.javaClass.getMethod("getWrappedDrawable")
                        val wrapped = getWrappedDrawableMethod.invoke(curr) as? Drawable
                        if (wrapped != null) {
                            current = wrapped
                        } else {
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
        }
        return current
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
            // Release Error Boundary (Sanitize bitmap output file failures)
            // The absolute output path is user/device-specific, so SecureLog strips it from release-retained diagnostics.
            SecureLog.error(TAG, "保存位图失败: ${outputFile.absolutePath}", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // 四、缩略图内部实现
    // -------------------------------------------------------------------------

    // 详尽的中文注释：废弃并移除已不再被调用的私有内存字节缩略图提取函数 createThumbnailFromBytes，
    // 所有内嵌封面与自定义、Sidecar 图片的处理现在均统一收归至 createThumbnailFromFile 物理文件下采样链路，
    // 贯彻了高解耦、高聚合的整洁架构原则，并进一步防范死代码或冗余逻辑带来的维护负担。

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
            // Release Error Boundary (Sanitize thumbnail generation failures)
            // Thumbnail creation touches cache files, so retained errors must scrub any filesystem detail in Throwable text.
            SecureLog.error(TAG, "从文件生成缩略图失败", e)
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
