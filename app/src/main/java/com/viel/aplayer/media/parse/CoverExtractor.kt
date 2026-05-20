package com.viel.aplayer.media.parse

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 专门负责封面图片提取、缩略图生成及主色调识别的组件。
 */
class CoverExtractor(private val context: Context) {
    // 详尽的中文注释：将封面图像和缩略图的保存目录从持久存储目录 context.filesDir 移至临时缓存目录 context.cacheDir
    // 如此设置当手机运行空间不足，或用户使用系统清理工具时，封面缓存可被安全回收以防长期耗用内存空间。
    private val coversDir = File(context.cacheDir, "covers").also { it.mkdirs() }

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
            // 详尽的中文注释：防御性强力建立父级目录防线。由于 cache 临时缓存目录随时可能在内存紧张或被安全工具扫描时物理删除，
            // 每次写入前必须确保 cache/covers 父目录 100% 存在，彻底免除 ENOENT (No such file or directory) 写文件错误。
            originalFile.parentFile?.mkdirs()
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
                // 详尽的中文注释：外置封面图片解析转存前，强力确保 coversDir 缓存目录 100% 存在，彻底规避 ENOENT 错误。
                originalFile.parentFile?.mkdirs()
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
            // 详尽的中文注释：生成并写入封面缩略图前，强力确保 coversDir 缓存目录 100% 存在，保证成功写入。
            thumbFile.parentFile?.mkdirs()
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