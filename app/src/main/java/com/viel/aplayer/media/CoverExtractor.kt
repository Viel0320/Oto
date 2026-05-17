package com.viel.aplayer.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.viel.aplayer.util.image.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 专门负责封面图片提取、缩略图生成及主色调识别的组件。
 */
class CoverExtractor(private val context: Context) {
    private val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }

    data class CoverResult(
        val originalPath: String?,
        val thumbnailPath: String?,
        val backgroundColor: Int? = null,
    )

    /**
     * 从目录中寻找封面图片并处理（生成缩略图及提取颜色）。
     */
    suspend fun extractFromDirectory(directory: DocumentFile): CoverResult = withContext(Dispatchers.IO) {
        val coverFile = findImageInDirectory(directory) ?: return@withContext CoverResult(null, null)
        processExternalImage(coverFile.uri)
    }

    /**
     * 从 MediaMetadataRetriever 中提取内嵌封面。
     */
    suspend fun extractFromRetriever(retriever: MediaMetadataRetriever, sourceId: String): CoverResult = withContext(Dispatchers.IO) {
        try {
            val artBytes = retriever.embeddedPicture ?: return@withContext CoverResult(null, null)
            
            // 1. 保存原图
            val originalFile = File(coversDir, "${sourceId.hashCode()}_orig.jpg")
            FileOutputStream(originalFile).use { it.write(artBytes) }
            val originalPath = originalFile.absolutePath

            // 2. 生成缩略图
            val thumbnailPath = createThumbnail(artBytes, sourceId)
            
            // 3. 提取主色调
            val color = ImageProcessor.getDominantColor(thumbnailPath ?: originalPath)

            CoverResult(originalPath, thumbnailPath, color)
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Error extracting from retriever", e)
            CoverResult(null, null)
        }
    }

    /**
     * 在目录中按优先级查找图片文件。
     */
    private fun findImageInDirectory(directory: DocumentFile): DocumentFile? {
        val imageExtensions = listOf("jpg", "jpeg", "png", "webp")
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        
        val files = directory.listFiles()
        // 1. 优先寻找特定命名的图片
        files.find { file ->
            val fullName = file.name?.lowercase() ?: ""
            val name = fullName.substringBeforeLast(".")
            val ext = fullName.substringAfterLast(".")
            priorityNames.contains(name) && imageExtensions.contains(ext)
        }?.let { return it }

        // 2. 兜底：返回目录下的第一张图片
        return files.find { file ->
            val ext = file.name?.lowercase()?.substringAfterLast(".") ?: ""
            imageExtensions.contains(ext)
        }
    }

    suspend fun processExternalImage(uri: Uri): CoverResult {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readBytes()
                val sourceId = uri.toString()
                
                val originalFile = File(coversDir, "${sourceId.hashCode()}_ext_orig.jpg")
                FileOutputStream(originalFile).use { it.write(bytes) }
                
                val thumbPath = createThumbnail(bytes, sourceId)
                val color = ImageProcessor.getDominantColor(thumbPath ?: originalFile.absolutePath)
                
                CoverResult(originalFile.absolutePath, thumbPath, color)
            } ?: CoverResult(null, null)
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Error processing external image", e)
            CoverResult(null, null)
        }
    }

    private fun createThumbnail(bytes: ByteArray, sourceId: String): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

            val maxSize = 300
            options.inSampleSize = ImageProcessor.calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            val scaledBitmap = if ((bitmap.width > maxSize) || (bitmap.height > maxSize)) {
                ImageProcessor.scaleBitmap(bitmap, maxSize)
            } else {
                bitmap
            }

            val thumbFile = File(coversDir, "${sourceId.hashCode()}_thumb.jpg")
            ImageProcessor.saveBitmapToFile(scaledBitmap, thumbFile)

            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()
            
            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Error generating thumbnail", e)
            null
        }
    }
}
