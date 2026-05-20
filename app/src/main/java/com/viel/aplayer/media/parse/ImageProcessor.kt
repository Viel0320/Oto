package com.viel.aplayer.media.parse

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import androidx.palette.graphics.Palette
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ImageProcessor {
    private const val TAG = "ImageProcessor"
    
    // LRU Cache for dominant colors to avoid repeated Palette generation
    private val colorCache = LruCache<String, Int>(100)
    
    // Default background color (Dark Theme compatible)
    const val DEFAULT_BACKGROUND_ARGB: Int = 0xFF1C1B1F.toInt()

    /**
     * Extracts the dominant color from an image at the given path.
     * Uses LRU cache to avoid expensive operations for the same file.
     */
    suspend fun getDominantColor(path: String?): Int = withContext(Dispatchers.IO) {
        if (path == null) return@withContext DEFAULT_BACKGROUND_ARGB
        
        // 1. Check Cache
        colorCache[path]?.let { return@withContext it }
        
        // 2. Process Image
        try {
            val file = File(path)
            if (!file.exists()) return@withContext DEFAULT_BACKGROUND_ARGB
            
            // Palette only needs a small bitmap, so read bounds first and then downsample the decode.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateInSampleSize(bounds, 100, 100)
            }
            
            val bitmap = BitmapFactory.decodeFile(path, options) ?: return@withContext DEFAULT_BACKGROUND_ARGB
            val color = Palette.from(bitmap).generate().getDominantColor(DEFAULT_BACKGROUND_ARGB)
            
            bitmap.recycle()
            
            // 3. Update Cache
            colorCache.put(path, color)
            return@withContext color
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting dominant color from $path", e)
            return@withContext DEFAULT_BACKGROUND_ARGB
        }
    }

    /**
     * Scales a bitmap to a maximum size while preserving aspect ratio.
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
     * Calculates the sample size for BitmapFactory to downsample images.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if ((height > reqHeight) || (width > reqWidth)) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
    
    /**
     * Helper to save a bitmap to a file.
     */
    fun saveBitmapToFile(bitmap: Bitmap, outputFile: File, quality: Int = 85): Boolean {
        return try {
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap to ${outputFile.absolutePath}", e)
            false
        }
    }
}