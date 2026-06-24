package com.viel.oto.media.parser

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
import com.viel.oto.logger.SecureLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Central image-processing boundary for cover persistence, thumbnail generation, and color extraction.
 *
 * [CoverExtractor] remains as a compatibility shell, while this object owns the actual
 * implementation for custom covers, embedded artwork, sidecar images, thumbnails, and bitmap helpers.
 */
object ImageProcessor {
    private const val TAG = "ImageProcessor"

    /**
     * Keeps medium cover cards and generated thumbnails on the same 360px cache contract.
     */
    private const val DEFAULT_THUMBNAIL_MAX_SIZE = 360

    /**
     * Caches dominant colors per cover path so repeated palette extraction does not re-decode the same image.
     */
    private val colorCache = LruCache<String, Int>(100)

    /**
     * Global fallback color used when covers are missing, corrupt, or unavailable for palette extraction.
     */
    const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF1C1B1F.toInt()

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
            val externalInputReader = com.viel.oto.library.vfs.VfsExternalInputReader(context)

            inputStream = externalInputReader.openInputStream(inputUri) ?: return@withContext CoverExtractor.CoverResult(null, null)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

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

            val width = bitmap.width
            val height = bitmap.height
            val size = minOf(width, height)
            val x = (width - size) / 2
            val y = (height - size) / 2
            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, size, size)

            val targetResolution = 1600
            val finalBitmap = if (size > targetResolution) {
                croppedBitmap.scale(targetResolution, targetResolution)
            } else {
                croppedBitmap
            }

            val timestamp = System.currentTimeMillis()
            val originalFile = File(coverCacheDir(context), "${bookId.hashCode()}_custom_${timestamp}_orig.jpg")
            originalFile.parentFile?.mkdirs()
            FileOutputStream(originalFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            if (finalBitmap != croppedBitmap) {
                finalBitmap.recycle()
            }
            if (croppedBitmap != bitmap) {
                croppedBitmap.recycle()
            }
            bitmap.recycle()

            val thumbPath = createThumbnailFromFile(originalFile, "${bookId}_custom_${timestamp}")
            CoverExtractor.CoverResult(originalFile.absolutePath, thumbPath, null)
        } catch (e: Exception) {
            SecureLog.error(TAG, "从外部 URI 裁剪并保存有声书 $bookId 的自定义封面失败", e)
            try { inputStream?.close() } catch (_: Exception) {}
            CoverExtractor.CoverResult(null, null)
        }
    }

    /**
     * Persists embedded artwork bytes returned by format parsers.
     *
     * The parser boundary already normalizes the image payload, so this function only commits the
     * original bytes and derives the thumbnail path used by cover surfaces.
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
            SecureLog.error(TAG, "保存内嵌封面字节失败", e)
            CoverExtractor.CoverResult(null, null)
        }
    }

    /**
     * Processes an external sidecar image stream through the same file-backed thumbnail path.
     *
     * The stream is copied to private cache before thumbnailing to avoid loading large sidecar
     * images into one transient byte array.
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
            SecureLog.error(TAG, "处理外部 sidecar 图片失败", e)
            CoverExtractor.CoverResult(null, null)
        }
    }

    /**
     * Extracts a dominant color from a drawable.
     *
     * Handles common drawable wrappers and hardware bitmaps before Palette sampling.
     */
    fun getDominantColor(drawable: Drawable?): Int {
        if (drawable == null) return DEFAULT_BACKGROUND_ARGB
        return try {
            val unwrapped = unwrapDrawable(drawable) ?: drawable
            Log.d(TAG, "getDominantColor: unwrappedClass=${unwrapped.javaClass.name}")

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

            val bitmap = try {
                if (unwrapped is BitmapDrawable) {
                    val origBitmap = unwrapped.bitmap
                    if (origBitmap != null) {
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
                    unwrapped.toBitmap(
                        width = targetWidth,
                        height = targetHeight,
                        config = Bitmap.Config.RGB_565
                    )
                }
            } catch (e: Exception) {
                SecureLog.error(TAG, "Drawable 转换 Bitmap 异常", e)
                null
            } ?: return DEFAULT_BACKGROUND_ARGB

            val color = Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
            Log.d(TAG, "getDominantColor: extractedColor=${Integer.toHexString(color)}")

            val originalBitmap = (unwrapped as? BitmapDrawable)?.bitmap
            if (bitmap != originalBitmap) {
                bitmap.recycle()
            }

            color
        } catch (e: Exception) {
            SecureLog.error(TAG, "从 Drawable 提取主色失败", e)
            DEFAULT_BACKGROUND_ARGB
        }
    }

    /**
     * Recursively unwraps common Drawable containers before color extraction.
     *
     * Only public Android drawable APIs are used here so cover color extraction never probes hidden
     * framework methods through reflection on real devices.
     */
    private fun unwrapDrawable(drawable: Drawable?): Drawable? {
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
                else -> {
                    break
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

    /**
     * Scales a bitmap so its longest side matches [maxSize] while preserving aspect ratio.
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
     * Calculates the power-of-two sampling factor used by [BitmapFactory].
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
     * Saves a bitmap as a JPEG file using the provided quality.
     */
    fun saveBitmapToFile(bitmap: Bitmap, outputFile: File, quality: Int = 85): Boolean {
        return try {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            true
        } catch (e: Exception) {
            SecureLog.error(TAG, "保存位图失败: ${outputFile.absolutePath}", e)
            false
        }
    }

    /**
     * Generates a thumbnail from an existing file-backed cover image.
     *
     * This shared path keeps custom, embedded, and sidecar artwork on one physical downsampling flow
     * without reading the whole source image into memory.
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
            SecureLog.error(TAG, "从文件生成缩略图失败", e)
            null
        }
    }

    /**
     * Resolves the private cover cache directory and creates it on demand.
     */
    private fun coverCacheDir(context: Context): File =
        File(context.cacheDir, "covers").also { it.mkdirs() }

}
