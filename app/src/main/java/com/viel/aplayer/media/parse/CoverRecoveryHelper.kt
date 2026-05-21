package com.viel.aplayer.media.parse

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity

/**
 * 详尽 of 中文注释：专门负责有声书封面缓存丢失非阻塞检测、异步提取生成与自愈更新的主控辅助类。
 * 本组件从原 LibraryRepository 彻底解耦，持有专用的协程作用域 scope 并单向依赖 CoverExtractor 与 BookDao，
 * 避免了 Repository 本身由于频繁的 File I/O 检查和复杂的漏斗提取算法造成臃肿。
 */
class CoverRecoveryHelper(
    private val context: Context,
    private val bookDao: BookDao,
    private val libraryRootDao: LibraryRootDao,
    private val coverExtractor: CoverExtractor,
    private val scope: CoroutineScope
) {
    // 详尽的中文注释：封面重建会同时触发 MediaMetadataRetriever、Bitmap 解码和 Palette 取色，限制全局并发避免大量新书入库后后台解码挤爆内存与 Binder I/O。
    private val regenerationSemaphore = Semaphore(MAX_CONCURRENT_COVER_REGENERATIONS)

    // 详尽的中文注释：并发安全的去重 HashSet，用来记录当前正在异步重建提取封面的 bookId，
    // 保证对同一本有声书在多条数据流被 UI 同时收集观察时，有且仅有一个提取协程在工作，防御性规避并发解码 OOM 崩内存。
    private val pendingRegenerations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 详尽的中文注释：并发安全的去重 HashSet，用来记录当前 APP 进程生命周期中已经尝试提取重建封面但未成功的书籍 ID，
    // 规避对于确实无封面的有声书在每次流观察时都重复触发后台协程扫描，节约系统 CPU 耗能。
    private val alreadyAttempted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 详尽的中文注释：对指定书籍进行轻量、零延迟非阻塞的封面物理完整性校验。
     * 若数据库里指向的封面/缩略图路径对应的物理文件实际在 cache 里不存在了（比如缓存被清理），或者之前因为权限等问题封面为 null，
     * 则将该书 ID 加入去重集，并在 Dispatchers.IO 上异步派发重建封面协程。
     */
    fun checkAndTriggerCoverRegeneration(book: BookEntity) {
        val bookId = book.id

        // 详尽的中文注释：如果当前 APP 进程生命周期中已经尝试过提取重建封面但未成功，直接拦截，不再重复开协程扫描，规避开销
        if (alreadyAttempted.contains(bookId)) return

        // 详尽的中文注释：快速且非阻塞地执行物理文件 File.exists() 检查。
        // 如果原本就没有封面文件（即 coverPath 或 thumbnailPath 为 null），或者路径对应的物理文件实际丢失，均判定为需要自愈重建
        val isCoverLost = book.coverPath == null || !File(book.coverPath).exists()
        val isThumbnailLost = book.thumbnailPath == null || !File(book.thumbnailPath).exists()

        if (isCoverLost || isThumbnailLost) {
            if (pendingRegenerations.add(bookId)) {
                Log.i("CoverRecoveryHelper", "检测到有声书封面缓存丢失或为空，已安排后台协程重建封面，书籍ID: $bookId")
                scope.launch(Dispatchers.IO) {
                    // 详尽的中文注释：记录本次重建是否真的写回了封面缓存，成功时不能加入失败去重集，否则同进程内缓存再次被系统清理后会失去自愈机会。
                    var rebuiltCover = false
                    try {
                        regenerationSemaphore.withPermit {
                            rebuiltCover = regenerateCoverForBook(bookId)
                        }
                    } catch (e: Exception) {
                        Log.e("CoverRecoveryHelper", "后台重新提取有声书 $bookId 封面发生异常，详情: ", e)
                    } finally {
                        // 无论成功还是失败，均从去重集合中清除该 ID，恢复其可用性
                        pendingRegenerations.remove(bookId)
                        // 详尽的中文注释：只有失败或确实找不到封面时才加入失败去重集；成功写回后清理旧失败标记，允许未来缓存物理丢失时再次自愈。
                        if (rebuiltCover) {
                            alreadyAttempted.remove(bookId)
                        } else {
                            alreadyAttempted.add(bookId)
                        }
                    }
                }
            }
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：强行且物理地重新生成指定有声书的封面缓存，不管当前是否存在。
     * 本方法会物理清除 alreadyAttempted 的失败去重标记拦截器，并在 Dispatchers.IO 上以高优先级信号量限制并发提取，
     * 完美保证强制更新封面图像和背景调色板颜色重新注入数据库，并自动触发 Flow 刷新重绘。
     */
    suspend fun forceRegenerateCover(bookId: String): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        alreadyAttempted.remove(bookId)
        try {
            regenerationSemaphore.withPermit {
                regenerateCoverForBook(bookId)
            }
        } catch (e: Exception) {
            Log.e("CoverRecoveryHelper", "强制后台重新提取有声书 $bookId 封面发生异常，详情: ", e)
            false
        }
    }

    /**
     * 详尽的中文注释：在后台协程中深度重新提取特定有声书封面图像的主逻辑。
     * 本方法严格遵循「1. 首个 READY 音频的内嵌封面 -> 2. 父目录 Sidecar 外部图像 -> 3. 备用音频内嵌封面」三重漏斗型高优先级覆盖提取算法。
     * 提取成功后，通过 BookDao 的 updateCoverPaths 局部写入最新绝对路径和调色板主色，触发 Room 的 Flow 物理重发，进而让 UI 丝滑自动重绘刷新。
     */
    private suspend fun regenerateCoverForBook(bookId: String): Boolean {
        val files = bookDao.getFilesForBookList(bookId)
        if (files.isEmpty()) {
            Log.w("CoverRecoveryHelper", "有声书 $bookId 在数据库中无可关联的物理音频文件，放弃重建封面。")
            return false
        }

        var finalCoverResult = CoverExtractor.CoverResult(null, null)
        // 详尽的中文注释：优先挑选出非 MISSING（状态为 READY）的首个音频物理对象。若没有，则取首个元素兜底
        val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
        // 详尽的中文注释：主文件的 SAF 定位与父目录定位共用同一次 root/relativePath 遍历，避免内嵌封面失败后再为 sidecar 查找重复访问数据库和 SAF 树。
        val primaryResolved = resolveBookFile(primaryFile, includeParentDirectory = true)

        // 1. 漏斗第一层：尝试从主要音频文件内嵌流中解码封面数据。
        // 详尽的中文注释：由于 Scoped Storage 限制，后台读取具体音频子文件 content URI 在 Native 容易无权。
        // 我们这里优先通过 findReadableFile 方法获取 100% 具备合法 SAF 访问权的 DocumentFile 实体。
        // 如果是 content 协议，打开只读 ParcelFileDescriptor 并将 FileDescriptor 桥接设置给 MediaMetadataRetriever，彻底绕过 JNI 的 URI 直接权限校验阻碍。
        val coverResult = try {
            val primaryDocFile = primaryResolved.documentFile
            val retriever = MediaMetadataRetriever()
            var localPfd: ParcelFileDescriptor? = null
            try {
                if (primaryDocFile != null) {
                    if (primaryDocFile.uri.scheme == "content") {
                        // 详尽的中文注释：对 content 类型的 SAF 实体，通过 ContentResolver 开启物理只读文件描述符
                        localPfd = context.contentResolver.openFileDescriptor(primaryDocFile.uri, "r")
                        if (localPfd != null) {
                            retriever.setDataSource(localPfd.fileDescriptor)
                        } else {
                            retriever.setDataSource(context, primaryDocFile.uri)
                        }
                    } else {
                        retriever.setDataSource(context, primaryDocFile.uri)
                    }
                } else {
                    // 详尽的中文注释：防守性兼容，若定位失败，退化为使用原 URI 尝试读取，规避异常
                    retriever.setDataSource(context, Uri.parse(primaryFile.uri))
                }
                coverExtractor.extractFromRetriever(retriever, bookId)
            } finally {
                retriever.release()
                try {
                    localPfd?.close()
                } catch (e: Exception) {
                    // 详尽的中文注释：防守性关闭异常，不作处理
                }
            }
        } catch (e: Exception) {
            Log.e("CoverRecoveryHelper", "第一层漏斗从主音频内嵌流提取封面异常: ", e)
            CoverExtractor.CoverResult(null, null)
        }

        if (coverResult.originalPath != null || coverResult.thumbnailPath != null) {
            finalCoverResult = coverResult
        } else {
            // 2. 漏斗第二层：若无内嵌封面，则通过主要音频物理文件记录寻找并解析其父目录，以此在 SAF 授权内检索 Sidecar 外部海报图（如 cover.jpg/folder.png 等）
            try {
                val parentDir = primaryResolved.parentDirectory
                if (parentDir != null) {
                    finalCoverResult = coverExtractor.extractFromDirectory(parentDir)
                } else {
                    Log.w("CoverRecoveryHelper", "有声书 $bookId 的主要音频文件父目录获取失败，跳过外部海报提取。")
                }
            } catch (e: Exception) {
                Log.e("CoverRecoveryHelper", "从同级目录 Sidecar 提取有声书 $bookId 封面海报时发生异常: ", e)
            }
        }

        // 3. 漏斗第三层：如果前两者仍然未找到，遍历尝试从其余关联音频文件中提取内嵌封面进行物理兜底。
        // 详尽的中文注释：对备用音频文件也同步加固，采用 findReadableFile 定位，并利用 PFD 物理桥接只读。
        if (finalCoverResult.originalPath == null && finalCoverResult.thumbnailPath == null) {
            for (fallbackFile in files.drop(1)) {
                val fallbackCover = try {
                    val fallbackDocFile = findReadableFile(fallbackFile)
                    val fallbackRetriever = MediaMetadataRetriever()
                    var fallbackPfd: ParcelFileDescriptor? = null
                    try {
                        if (fallbackDocFile != null) {
                            if (fallbackDocFile.uri.scheme == "content") {
                                fallbackPfd = context.contentResolver.openFileDescriptor(fallbackDocFile.uri, "r")
                                if (fallbackPfd != null) {
                                    fallbackRetriever.setDataSource(fallbackPfd.fileDescriptor)
                                } else {
                                    fallbackRetriever.setDataSource(context, fallbackDocFile.uri)
                                }
                            } else {
                                fallbackRetriever.setDataSource(context, fallbackDocFile.uri)
                            }
                        } else {
                            // 详尽的中文注释：防守性退化兼容，保证旧逻辑仍能触达
                            fallbackRetriever.setDataSource(context, Uri.parse(fallbackFile.uri))
                        }
                        coverExtractor.extractFromRetriever(fallbackRetriever, bookId)
                    } finally {
                        fallbackRetriever.release()
                        try {
                            fallbackPfd?.close()
                        } catch (e: Exception) {
                            // 详尽的中文注释：防守性忽略关闭异常
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoverRecoveryHelper", "第三层漏斗从备份音频文件 ${fallbackFile.id} 内嵌流提取封面异常: ", e)
                    CoverExtractor.CoverResult(null, null)
                }
                if (fallbackCover.originalPath != null || fallbackCover.thumbnailPath != null) {
                    finalCoverResult = fallbackCover
                    break
                }
            }
        }

        // 4. 重建成功后，局部更新数据库，利用 Room Flow 响应式触发 UI 流刷新
        if (finalCoverResult.originalPath != null || finalCoverResult.thumbnailPath != null) {
            // 详尽的中文注释：在调用 updateCoverPaths 时额外传入当前系统时间戳以更新 lastScannedAt 字段。
            // 这能够强制使 BookEntity 对象发生属性状态变更，从而推动 Room 的监听 Flow 即刻向 UI 线程发送数据重绘通知，完美联动 UI 实现自动刷新。
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = finalCoverResult.originalPath,
                thumbnailPath = finalCoverResult.thumbnailPath,
                backgroundColorArgb = finalCoverResult.backgroundColor,
                lastScannedAt = System.currentTimeMillis()
            )
            // 详尽的中文注释：依指示直接移除原本此处的 Log.i 刷新通知日志，规避控制台在高频自愈重建中打印不必要的冗余日志
            return true
        } else {
            Log.w("CoverRecoveryHelper", "未能在任何物理源中找到封面资产，放弃为有声书 $bookId 重建。")
            return false
        }
    }

    /**
     * 详尽的中文注释：根据书籍的物理音频文件记录，安全地还原其父目录 DocumentFile。
     * 首先会利用扫描导入时记录的 rootId 和 relativePath 进行两级定位（SAF Tree 模式），以规避 DocumentFile.fromSingleUri 检索父目录始终返回 null 的系统限制；
     * 如果解析失败，则根据协议分别进行 local file 或是 content URI 单文件解析的兜底。
     */
    private suspend fun getParentDirectory(file: BookFileEntity): DocumentFile? {
        // 详尽的中文注释：保留旧入口给局部调用兜底使用，实际定位委托给统一解析器，避免父目录和文件定位逻辑继续分叉。
        return resolveBookFile(file, includeParentDirectory = true).parentDirectory
    }

    /**
     * 详尽的中文注释：统一还原 BookFile 的可读 DocumentFile 与可选父目录，主流程可一次拿到两者，减少重复 Room 查询、DocumentFile.fromTreeUri 与逐级 findFile。
     */
    private suspend fun resolveBookFile(file: BookFileEntity, includeParentDirectory: Boolean): ResolvedBookFile {
        try {
            val root = libraryRootDao.getRootById(file.rootId)
            val rootDoc = root?.treeUri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
            val fileUri = Uri.parse(file.uri)

            // 详尽的中文注释：优先使用 SAF Tree 授权的相对路径逐级定位音频文件，并在遍历到最后一级前顺手保存父目录。
            if (rootDoc != null && file.relativePath.isNotBlank()) {
                val segments = file.relativePath.split('/').filter { it.isNotBlank() }
                var current: DocumentFile? = rootDoc
                var parentDirectory: DocumentFile? = if (segments.isEmpty()) rootDoc else null
                for ((index, segment) in segments.withIndex()) {
                    if (includeParentDirectory && index == segments.lastIndex) {
                        parentDirectory = current
                    }
                    current = current?.findFile(segment)
                    if (current == null) break
                }
                val documentFile = current?.takeIf { it.isFile }
                val usableParent = parentDirectory?.takeIf { includeParentDirectory && it.isDirectory }
                if (documentFile != null || usableParent != null) {
                    return ResolvedBookFile(documentFile = documentFile, parentDirectory = usableParent)
                }
            }

            // 详尽的中文注释：兜底方案一，如果是 file 协议，直接用物理 java.io.File 同时还原文件和父目录。
            if (fileUri.scheme == "file") {
                val f = File(fileUri.path ?: "")
                val documentFile = f.takeIf { it.exists() && it.isFile }?.let(DocumentFile::fromFile)
                val parentDirectory = if (includeParentDirectory) {
                    f.parentFile?.takeIf { it.exists() && it.isDirectory }?.let(DocumentFile::fromFile)
                } else {
                    null
                }
                if (documentFile != null || parentDirectory != null) {
                    return ResolvedBookFile(documentFile = documentFile, parentDirectory = parentDirectory)
                }
            }

            // 详尽的中文注释：兜底方案二，如果是单文件 content URI，退化到 DocumentFile.fromSingleUri；parentFile 只作最后兼容路径使用。
            if (fileUri.scheme == "content") {
                val documentFile = DocumentFile.fromSingleUri(context, fileUri)?.takeIf { it.exists() && it.isFile }
                val parentDirectory = if (includeParentDirectory) {
                    documentFile?.parentFile
                } else {
                    null
                }
                if (documentFile != null) {
                    return ResolvedBookFile(documentFile = documentFile, parentDirectory = parentDirectory)
                }
            }
        } catch (e: Exception) {
            Log.e("CoverRecoveryHelper", "解析音频文件 ${file.id} 的可读文件或父目录发生异常: ", e)
        }
        return ResolvedBookFile(documentFile = null, parentDirectory = null)
    }

    /**
     * 详尽的中文注释：根据书籍的物理音频文件记录，安全、深度定位地还原其对应的 DocumentFile 物理文件实体。
     * 首先利用扫描导入时记录的 rootId 和 relativePath 进行多级路径递归定位（SAF Tree 模式），以确保其 100% 具备当前应用会话的完整读写访问权限；
     * 如果 SAF Tree 路径寻址因为目录结构变更或未找到而失败，则分别根据协议类型（本地 file 协议或普通的单文件 content URI）执行安全且防御性的兜底解析。
     */
    private suspend fun findReadableFile(file: BookFileEntity): DocumentFile? {
        // 详尽的中文注释：保留旧入口给备用音频漏斗调用，实际文件定位统一走 resolveBookFile，避免两套 SAF 路径遍历逻辑长期漂移。
        return resolveBookFile(file, includeParentDirectory = false).documentFile
    }

    // 详尽的中文注释：内部承载一次 BookFile 定位结果，让主文件封面提取和同目录 sidecar 查找共享同一次解析输出。
    private data class ResolvedBookFile(
        val documentFile: DocumentFile?,
        val parentDirectory: DocumentFile?
    )

    private companion object {
        // 详尽的中文注释：封面后台重建默认只允许两个重任务同时执行，给导入 metadata 与 UI 线程保留资源余量。
        private const val MAX_CONCURRENT_COVER_REGENERATIONS = 2
    }
}
