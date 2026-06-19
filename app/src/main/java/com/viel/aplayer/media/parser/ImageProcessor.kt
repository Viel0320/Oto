package com.viel.aplayer.media.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
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
    // Cardgroup Thumbnail Size Alignment (Keep medium cover cards and generated thumbnails on the same 360px contract)
    // This lets Cardgroup surfaces prefer local thumbnails and matching Coil cache entries, reducing repeated full-size cover decoding.
    private const val DEFAULT_THUMBNAIL_MAX_SIZE = 360

    // 主色提取代价较高，这里保留路径级 LRU 缓存，避免同一张封面重复跑 Palette。
    private val colorCache = LruCache<String, Int>(100)

    // 默认背景色用于封面缺失、图片损坏或取色失败时的全局兜底。
    const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF1C1B1F.toInt()

    // -------------------------------------------------------------------------
    // 一、封面文件工作流
    // -------------------------------------------------------------------------

    /**
     * Crop, resize and save custom cover from URI (Perform crop and downscaling on cover stream in background)
     *
     * Resolves the target external content URI using VfsExternalInputReader, center-crops the bitmap to a square,
     * downscales it to 800x800 for storage efficiency, and commits the result to the cover cache directory.
     */
    suspend fun saveCustomCoverFromUri(
        context: Context,
        bookId: String,
        coverUriString: String
    ): CoverExtractor.CoverResult = withContext(Dispatchers.IO) {
        var inputStream: InputStream? = null
        try {
            val inputUri = coverUriString.toUri()
            val externalInputReader = com.viel.aplayer.library.vfs.VfsExternalInputReader(context)
            
            // Decode boundaries (Read dimensions first to configure subsampling scale and guard against heap OOM)
            inputStream = externalInputReader.openInputStream(inputUri) ?: return@withContext CoverExtractor.CoverResult(null, null)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Decode image pixel data (Allocate actual bitmap heap buffer using configured Config.RGB_565 configuration)
            inputStream = externalInputReader.openInputStream(inputUri) ?: return@withContext CoverExtractor.CoverResult(null, null)
            val maxDim = maxOf(options.outWidth, options.outHeight)
            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            if (maxDim > 2000) {
                decodeOptions.inSampleSize = 2
            }
            val bitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return@withContext CoverExtractor.CoverResult(null, null)
            inputStream.close()

            // Perform square crop centered relative to the shortest side
            val width = bitmap.width
            val height = bitmap.height
            val size = minOf(width, height)
            val x = (width - size) / 2
            val y = (height - size) / 2
            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, size, size)

            // Downscale resolution (Resize the cropped square to 1200x1200 pixels to optimize disk layout and memory profiles)
            val targetResolution = 1200
            val finalBitmap = if (size > targetResolution) {
                croppedBitmap.scale(targetResolution, targetResolution)
            } else {
                croppedBitmap
            }

            // Output file commit (Compress and write image block to target file using 90% JPEG quality)
            val timestamp = System.currentTimeMillis()
            val originalFile = File(coverCacheDir(context), "${bookId.hashCode()}_custom_${timestamp}_orig.jpg")
            originalFile.parentFile?.mkdirs()
            FileOutputStream(originalFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // Explicit recycling (Call recycle on temporary bitmaps to free graphics memory immediately)
            if (finalBitmap != croppedBitmap) {
                finalBitmap.recycle()
            }
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
            bitmap.recycle()

            // Generate thumbnail from the finalized custom cover image
            val thumbPath = createThumbnailFromFile(originalFile, "${bookId}_custom_${timestamp}")
            CoverExtractor.CoverResult(originalFile.absolutePath, thumbPath, null)
        } catch (e: Exception) {
            SecureLog.error(TAG, "从外部 URI 裁剪并保存有声书 $bookId 的自定义封面失败", e)
            try { inputStream?.close() } catch (_: Exception) {}
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
            val thumbnailPath = createThumbnailFromFile(originalFile, sourceId)
            CoverExtractor.CoverResult(originalPath, thumbnailPath, null)
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
            CoverExtractor.CoverResult(originalFile.absolutePath, thumbPath, null)
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
     * 从 Drawable 提取主色。
     *
     * 针对 Coil 的 Drawable 进行了深度优化，兼容各种包装类 (CrossfadeDrawable 等) 以及硬件位图 (Hardware Bitmap)。
     */
    fun getDominantColor(drawable: Drawable?): Int {
        // Safe Drawable Extraction: Unpack wrappers and restrict bounds to 100x100 ARGB_8888 to guarantee Palette performance and prevent OOM or Hardware Bitmap crashes.
        if (drawable == null) return DEFAULT_BACKGROUND_ARGB
        return try {
            val unwrapped = unwrapDrawable(drawable) ?: drawable
            Log.d(TAG, "getDominantColor: unwrappedClass=${unwrapped.javaClass.name}")

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

            // RGB_565 Drawing Execution: Safely unpack BitmapDrawable and copy hardware bitmap if needed to avoid Software Canvas Hardware Bitmap rendering exceptions.
            val bitmap = try {
                if (unwrapped is BitmapDrawable) {
                    val origBitmap = unwrapped.bitmap
                    if (origBitmap != null) {
                        // Hardware Copy Fallback Warning (Safeguard against unexpected hardware bitmaps while expecting software RGB_565 configurations from upstream loaders)
                        // This serves only as a safety fallback since upstream Coil requests are pre-configured with allowHardware = false and RGB_565 config when dominant color extraction is needed.
                        val isHardware = origBitmap.config == Bitmap.Config.HARDWARE
                        val softwareBitmap = if (isHardware) {
                            origBitmap.copy(Bitmap.Config.RGB_565, false)
                        } else {
                            origBitmap
                        }

                        if (softwareBitmap != null) {
                            if (softwareBitmap.width > targetWidth || softwareBitmap.height > targetHeight) {
                                val scaled = softwareBitmap.scale(targetWidth, targetHeight)
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
                    // Force RGB_565 for Drawables: Convert the drawable to RGB_565 configuration to optimize memory footprint during dominant color extraction.
                    unwrapped.toBitmap(
                        width = targetWidth,
                        height = targetHeight,
                        config = Bitmap.Config.RGB_565
                    )
                }
            } catch (e: Exception) {
                // Release Error Boundary (Sanitize drawable conversion failures)
                // Drawable wrappers can originate from file or network loaders, so retained errors must use SecureLog.
                SecureLog.error(TAG, "Drawable 转换 Bitmap 异常", e)
                null
            } ?: return DEFAULT_BACKGROUND_ARGB

            val color = Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
            Log.d(TAG, "getDominantColor: extractedColor=${Integer.toHexString(color)}")

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
                curr is android.graphics.drawable.DrawableWrapper -> {
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
                    } catch (_: Exception) {
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
                    } catch (_: Exception) {
                        break
                    }
                }
            }
        }
        return current
    }

    /**
     * Stores a dominant cover color using a versioned key so same-path cover replacements do not reuse stale colors.
     */
    fun putColorToCache(path: String?, lastUpdated: Long, color: Int) {
        val cacheKey = colorCacheKey(path, lastUpdated) ?: return
        Log.d(TAG, "putColorToCache: key=$cacheKey, color=${Integer.toHexString(color)}")
        colorCache.put(cacheKey, color)
    }

    /**
     * Reads a dominant cover color with the same versioned key used by CoverBackground color production.
     */
    fun getCachedColor(path: String?, lastUpdated: Long): Int? {
        val cacheKey = colorCacheKey(path, lastUpdated) ?: return null
        val cached = colorCache.get(cacheKey)
        Log.d(TAG, "getCachedColor: key=$cacheKey, result=${cached?.let { Integer.toHexString(it) }}")
        return cached
    }

    /**
     * Builds the in-memory color cache key from the displayed source and its invalidation timestamp.
     */
    private fun colorCacheKey(path: String?, lastUpdated: Long): String? =
        path?.let { "$it:$lastUpdated" }

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
            // Force RGB_565 Config: Force decode to RGB_565 configuration to minimize memory usage when generating thumbnail bitmaps from file.
            options.inPreferredConfig = Bitmap.Config.RGB_565

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

