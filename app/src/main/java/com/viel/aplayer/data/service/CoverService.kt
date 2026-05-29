package com.viel.aplayer.data.service

import android.util.Log
import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.dao.ChapterDao
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.library.availability.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 封面、元数据物理提取与主色调更新应用服务（实现了 CoverGateway 网关）。
 *
 * 核心设计目标：
 * 1. 彻底解耦并消灭大仓库：在 M6e 阶段直接直连注入 BookDao, ChapterDao 以及精细化的物理处理组件，彻底废弃旧有 PhysicalFileResolver 仓库的直接委托。
 * 2. 完美平移元数据物理重扫与自愈：精心保留自定义封面保存写入 Room、主背景色异步写缓存以及深度的分轨物理元数据强制重扫和章节实体级联刷新自愈动作。
 */
class CoverService(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao,
    private val coverRecoveryHelper: CoverRecoveryHelper,
    private val coverExtractor: CoverExtractor,
    private val metadataResolver: MetadataResolver,
    private val subtitleResolver: SubtitleFileResolver,
    private val detailAvailabilityChecker: DetailAvailabilityChecker,
    private val availabilityChecker: AvailabilityChecker
) : CoverGateway {

    // 详尽的中文注释：异步写任务专属协程异常拦截器
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("CoverService", "协程在 CoverService 运行中捕获到未处理异常", exception)
    }

    // 详尽的中文注释：此服务内部维护的专属后台协程上下文作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    override suspend fun saveCustomCover(bookId: String, tempCoverPath: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        // 详尽的中文注释：首先委托封面图物理提取器在沙盒内执行自定义封面物理文件的拷贝与缩略图生成裁剪
        val result = coverExtractor.saveCustomCover(bookId, tempCoverPath)
        if (result.originalPath != null) {
            // 详尽的中文注释：物理安全清除磁盘上的旧封面文件以防存储空间的垃圾积累
            book.coverPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            // 详尽的中文注释：物理安全清除磁盘上的旧封面缩略图缓存文件
            book.thumbnailPath?.let { oldPath ->
                val oldFile = File(oldPath)
                if (oldFile.exists()) {
                    oldFile.delete()
                }
            }
            // 详尽的中文注释：清理编辑或裁剪时留在临时目录下的临时源文件
            val tempFile = File(tempCoverPath)
            if (tempFile.exists()) {
                tempFile.delete()
            }

            // 详尽的中文注释：随后同步安全将原图绝对路径、缩略图绝对路径、计算得到的主背景 ARGB 色更新回 Room
            bookDao.updateCoverPaths(
                id = bookId,
                coverPath = result.originalPath,
                thumbnailPath = result.thumbnailPath,
                backgroundColorArgb = result.backgroundColor,
                lastScannedAt = System.currentTimeMillis()
            )
        }
    }

    override suspend fun forceRegenerateCoverAndMetadata(bookId: String) = withContext(Dispatchers.IO) {
        // 详尽的中文注释：强行对该有声书的物理元数据标签及封面进行自愈检查和重扫
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext
            val files = bookDao.getFilesForBookList(bookId)
            if (files.isEmpty()) return@withContext

            // 1. 物理安全寻址首条可供读取且状态就绪的主音频物理分轨文件
            val primaryFile = files.firstOrNull { it.status == AudiobookSchema.FileStatus.READY } ?: files.first()
            
            // 2. 委托元数据解析工具深度提取音频的结构化标签信息
            val metadata = metadataResolver.extract(primaryFile)

            // 3. 将物理重新扫出来的唱片集/年份/讲述人/持续时长等详细元数据属性覆盖写回 Room
            bookDao.updateMetadata(
                id = bookId,
                title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                author = metadata.author,
                narrator = metadata.narrator,
                description = metadata.description,
                duration = metadata.durationMs.takeIf { it > 0 } ?: book.totalDurationMs
            )
            bookDao.updateBookDetails(
                id = bookId,
                title = metadata.album.trim().ifBlank { metadata.title.trim() }.ifBlank { book.title },
                author = metadata.author,
                narrator = metadata.narrator,
                description = metadata.description,
                year = metadata.year
            )

            // 4. 清理旧物理分轨对应的所有老章节记录，并批量重新持久化写入重扫出来的新章节集
            if (metadata.chapters.isNotEmpty()) {
                chapterDao.deleteChaptersForBook(bookId)
                val chaptersWithBookId = metadata.chapters.map { it.copy(bookId = bookId) }
                chapterDao.insertChapters(chaptersWithBookId)
            }

            // 5. 强刷底层磁盘中生成的封面物理缓存，强制封面加载系统感知最新自愈出的物理图片文件
            coverRecoveryHelper.forceRegenerateCover(bookId)
        } catch (e: Exception) {
            Log.e("CoverService", "物理强制重建有声书 $bookId 的封面与元数据发生异常", e)
        }
    }

    override fun updateBackgroundColor(id: String, color: Int) {
        // 详尽的中文注释：在专属后台协程中非阻塞异步地更新书籍详情的主背景色 ARGB 缓存值
        scope.launch {
            bookDao.updateBackgroundColor(id, color)
        }
    }

    override suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // 详尽的中文注释：通过详情页就绪可用状态校验器检测书籍物理上的存在性与授权可读性
        detailAvailabilityChecker.check(bookId).isAvailable
    }

    override suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean = withContext(Dispatchers.IO) {
        // 详尽的中文注释：首先获取当前书籍关联的所有音轨分轨列表以获取首个主物理音频轨
        val primaryFile = bookDao.getFilesForBookList(bookId).firstOrNull() ?: return@withContext false
        // 详尽的中文注释：随后委托物理就绪校验器在文件系统层面上核验其主音频文件的真实存在性
        availabilityChecker.checkBookFile(primaryFile).isAvailable
    }

    override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<com.viel.aplayer.ui.player.components.SubtitleLine> = withContext(Dispatchers.IO) {
        // 详尽的中文注释：委托外置字幕文件解析门面在 VFS 中流式定位并加载解析该音轨关联的字幕数据
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)
    }
}
