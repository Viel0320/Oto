package com.viel.aplayer.media.parse

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
     * 为每一次改动添加详尽的中文注释：
     * 保存用户手动上传的自定义封面。
     * 将已经按最短边居中裁剪为正方形的临时封面文件物理复制到正式的 covers 缓存目录下，
     * 重新为其生成配套的封面缩略图，并异步提取主色调背景色。
     * 采用时间戳命名以彻底打破 Coil 的缓存机制，实现即时、流畅刷新。
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String): CoverResult = withContext(Dispatchers.IO) {
        try {
            val tempFile = File(tempCoverPath)
            if (!tempFile.exists()) return@withContext CoverResult(null, null)

            val timestamp = System.currentTimeMillis()
            val originalFile = File(coversDir, "${bookId.hashCode()}_custom_${timestamp}_orig.jpg")
            // 为每一次改动添加详尽的中文注释：确保 cache/covers 目录 100% 存在，防止系统自动清理缓存导致目录缺失引发 ENOENT 异常
            originalFile.parentFile?.mkdirs()

            // 物理复制临时文件到正式封面存放路径
            tempFile.copyTo(originalFile, overwrite = true)

            // 利用 createThumbnailFromFile 异步生成 300x300 像素的高清缩略图
            val thumbPath = createThumbnailFromFile(originalFile, "${bookId}_custom_${timestamp}")
            // 提取该自定义封面图像的主色调，用于沉浸式模糊背景的渲染
            val color = ImageProcessor.getDominantColor(thumbPath ?: originalFile.absolutePath)

            CoverResult(originalFile.absolutePath, thumbPath, color)
        } catch (e: Exception) {
            Log.e("CoverExtractor", "保存有声书 $bookId 的自定义封面失败，原因: ", e)
            CoverResult(null, null)
        }
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

    suspend fun processExternalImage(sourceId: String, openStream: suspend () -> InputStream?): CoverResult = withContext(Dispatchers.IO) {
        try {
            val originalFile = File(coversDir, "${sourceId.hashCode()}_ext_orig.jpg")
            // 为每一次改动添加详尽的中文注释：VFS sidecar 图像通过流复制到缓存，不要求调用方提供 content Uri。
            originalFile.parentFile?.mkdirs()
            openStream()?.use { input ->
                FileOutputStream(originalFile).use { output -> input.copyTo(output) }
            } ?: return@withContext CoverResult(null, null)

            val thumbPath = createThumbnailFromFile(originalFile, sourceId)
            val color = ImageProcessor.getDominantColor(thumbPath ?: originalFile.absolutePath)

            CoverResult(originalFile.absolutePath, thumbPath, color)
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Error processing external image from VFS", e)
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

    private fun createThumbnailFromFile(imageFile: File, sourceId: String): String? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, options)

            val maxSize = 300
            options.inSampleSize = ImageProcessor.calculateInSampleSize(options, maxSize, maxSize)
            options.inJustDecodeBounds = false

            // 详尽的中文注释：从缓存文件按采样率解码 sidecar 原图，只把缩略图尺寸所需像素放进 Bitmap 内存。
            val bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, options) ?: return null
            val scaledBitmap = if ((bitmap.width > maxSize) || (bitmap.height > maxSize)) {
                ImageProcessor.scaleBitmap(bitmap, maxSize)
            } else {
                bitmap
            }

            val thumbFile = File(coversDir, "${sourceId.hashCode()}_thumb.jpg")
            // 详尽的中文注释：外置图缩略图写入前再次确保 cache/covers 目录存在，抵御系统清理缓存后的瞬时 ENOENT。
            thumbFile.parentFile?.mkdirs()
            ImageProcessor.saveBitmapToFile(scaledBitmap, thumbFile)

            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            bitmap.recycle()

            thumbFile.absolutePath
        } catch (e: Exception) {
            Log.e("CoverExtractor", "Error generating thumbnail from file", e)
            null
        }
    }
}
