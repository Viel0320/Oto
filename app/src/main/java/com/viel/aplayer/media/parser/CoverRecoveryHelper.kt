package com.viel.aplayer.media.parse

import android.content.Context
import android.util.Log
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.vfs.VfsFileReader
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 负责有声书封面缓存丢失后的异步自愈。
 *
 * 详尽的中文注释：从这次收口开始，这个类不再直接调用任何格式专属的封面解析器。
 * 它只做三件事：
 * 1. 判断缓存是否丢失
 * 2. 按优先级尝试“内嵌封面 -> sidecar 图片 -> 其他音频内嵌封面”
 * 3. 把 parser 产出的封面字节落到本地缓存并回写数据库
 */
class CoverRecoveryHelper(
    private val context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val coverExtractor: CoverExtractor,
    private val scope: CoroutineScope
) {
    // 详尽的中文注释：封面重建会触发 VFS 读流、图片解码和主色提取，因此继续用信号量限制全局并发。
    private val regenerationSemaphore = Semaphore(MAX_CONCURRENT_COVER_REGENERATIONS)
    private val fileReader = VfsFileReader(context.applicationContext, libraryRootDao)
    // 详尽的中文注释：所有内嵌封面字节都统一通过 MetadataResolver 向各格式 parser 请求。
    private val MetadataResolver = MetadataResolver(context.applicationContext)

    private val pendingRegenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val alreadyAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun checkAndTriggerCoverRegeneration(book: BookEntity) {
        val bookId = book.id
        if (alreadyAttempted.contains(bookId)) return

        val isCoverLost = book.coverPath == null || !File(book.coverPath).exists()
        val isThumbnailLost = book.thumbnailPath == null || !File(book.thumbnailPath).exists()
        if (!isCoverLost && !isThumbnailLost) return

        if (pendingRegenerations.add(bookId)) {
            scope.launch(Dispatchers.IO) {
                var rebuiltCover = false
                try {
                    regenerationSemaphore.withPermit {
                        rebuiltCover = regenerateCoverForBook(bookId)
                    }
                } catch (error: Exception) {
                    Log.e("CoverRecoveryHelper", "后台重建有声书 $bookId 封面异常", error)
                } finally {
                    pendingRegenerations.remove(bookId)
                    if (rebuiltCover) {
                        alreadyAttempted.remove(bookId)
                    } else {
                        alreadyAttempted.add(bookId)
                    }
                }
            }
        }
    }

    suspend fun forceRegenerateCover(bookId: String): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            alreadyAttempted.remove(bookId)
            try {
                regenerationSemaphore.withPermit {
                    regenerateCoverForBook(bookId)
                }
            } catch (error: Exception) {
                Log.e("CoverRecoveryHelper", "强制重建有声书 $bookId 封面异常", error)
                false
            }
        }

    private suspend fun regenerateCoverForBook(bookId: String): Boolean {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) return false

        val primaryFile = files.firstOrNull { file -> file.status == AudiobookSchema.FileStatus.READY } ?: files.first()
        var finalCoverResult = tryExtractEmbeddedCover(bookId, primaryFile)

        if (!finalCoverResult.hasImage()) {
            finalCoverResult = tryExtractSidecarCover(primaryFile)
        }

        if (!finalCoverResult.hasImage()) {
            for (fallbackFile in files.drop(1)) {
                finalCoverResult = tryExtractEmbeddedCover(bookId, fallbackFile)
                if (finalCoverResult.hasImage()) break
            }
        }

        if (!finalCoverResult.hasImage()) return false

        // 详尽的中文注释：封面缓存一旦重建成功，就顺带更新 lastScannedAt，
        // 这样 Room Flow 会触发 UI 重新取图，不需要额外的手工刷新信号。
        bookDao.updateCoverPaths(
            id = bookId,
            coverPath = finalCoverResult.originalPath,
            thumbnailPath = finalCoverResult.thumbnailPath,
            backgroundColorArgb = finalCoverResult.backgroundColor,
            lastScannedAt = System.currentTimeMillis()
        )
        return true
    }

    private suspend fun tryExtractEmbeddedCover(
        bookId: String,
        file: BookFileEntity
    ): CoverExtractor.CoverResult =
        try {
            val embeddedCover = MetadataResolver.extractWithEmbeddedCover(file).embeddedCover
            if (embeddedCover == null || embeddedCover.bytes.isEmpty()) {
                CoverExtractor.CoverResult(null, null)
            } else {
                // 详尽的中文注释：这里不再区分 covr / APIC / PICTURE / METADATA_BLOCK_PICTURE，
                // 它们都已经在各自 parser 内部解析成统一的 `embeddedCover.bytes`。
                coverExtractor.saveEmbeddedImage(
                    "$bookId:${file.rootId}:${file.sourcePath}:embedded",
                    embeddedCover.bytes
                )
            }
        } catch (error: Exception) {
            Log.e("CoverRecoveryHelper", "解析有声书 $bookId 内嵌封面异常", error)
            CoverExtractor.CoverResult(null, null)
        }

    private suspend fun tryExtractSidecarCover(primaryFile: BookFileEntity): CoverExtractor.CoverResult =
        try {
            val sidecar = findSidecarImage(primaryFile) ?: return CoverExtractor.CoverResult(null, null)
            coverExtractor.processExternalImage("${sidecar.root.id}:${sidecar.metadata.sourcePath}") {
                fileReader.open(sidecar)
            }
        } catch (error: Exception) {
            Log.e("CoverRecoveryHelper", "解析 sidecar 封面异常", error)
            CoverExtractor.CoverResult(null, null)
        }

    private fun CoverExtractor.CoverResult.hasImage(): Boolean =
        originalPath != null || thumbnailPath != null

    private suspend fun findSidecarImage(file: BookFileEntity): com.viel.aplayer.library.vfs.VfsNode? {
        val parentPath = file.sourcePath.substringBeforeLast('/', missingDelimiterValue = "")
        val files = fileReader.listChildren(file.rootId, parentPath)
            .filter { node -> !node.metadata.isDirectory && isImage(node.metadata.displayName) }
        val priorityNames = listOf("cover", "folder", "artwork", "front")
        return files.firstOrNull { node ->
            val baseName = node.metadata.displayName.substringBeforeLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
            baseName in priorityNames
        } ?: files.firstOrNull()
    }

    private fun isImage(name: String): Boolean {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }

    private companion object {
        private const val MAX_CONCURRENT_COVER_REGENERATIONS = 2
    }
}
