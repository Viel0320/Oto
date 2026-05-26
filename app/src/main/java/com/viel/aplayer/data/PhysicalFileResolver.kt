package com.viel.aplayer.data

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.DetailAvailabilityChecker
import com.viel.aplayer.library.availability.AvailabilityChecker
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.media.parser.CoverRecoveryHelper
import com.viel.aplayer.media.parser.MetadataResolver
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import com.viel.aplayer.ui.player.components.SubtitleLine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 物理文件解析器，负责应用中所有与本地物理文件系统相关的交互。
 * 包含外置字幕的检索与解析、封面缓存文件的提取和强刷落盘、以及虚拟文件系统（VFS）的授权与存在性校验。
 * 遵循单一职责与数据层隔离原则，将繁重的磁盘 I/O 和多媒体元数据物理提取逻辑从主仓库中剥离。
 */
@OptIn(UnstableApi::class)
class PhysicalFileResolver private constructor(context: Context) {
    // 使用 applicationContext 防止 Activity 级别的内存泄漏
    private val context = context.applicationContext
    
    // 获取全局唯一的 Room 数据库实例及对应的 DAO 接口
    private val database = AppDatabase.getInstance(this.context)
    private val bookDao = database.bookDao()
    private val libraryRootDao = database.libraryRootDao()

    // 独立协程异常处理器，用于拦截和记录封面异步恢复等后台任务执行过程中的突发异常
    private val exceptionHandler = CoroutineExceptionHandler { _, exception ->
        Log.e("PhysicalFileResolver", "协程在 PhysicalFileResolver 运行中捕获到未处理异常", exception)
    }
    
    // 该解析器专属的协程作用域，运行在 IO 线程池中，生命周期随应用全局单例存活
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + exceptionHandler)

    // 实例化物理文件可用性与详情页可达性校验组件
    private val availabilityChecker = DetailAvailabilityChecker(this.context)
    private val fileAvailabilityChecker = AvailabilityChecker(this.context)
    
    // 实例化物理媒体解析与封面图提取工具
    private val coverExtractor = CoverExtractor(this.context)
    private val metadataResolver = MetadataResolver(this.context)

    // 字幕路径定位与流式解析门面，直连 VFS 读流
    private val subtitleResolver = SubtitleFileResolver(this.context, bookDao, libraryRootDao)

    // 封面恢复助手，负责在封面物理文件丢失时自动从音频原文件中非阻塞重构并重建缓存
    val coverRecoveryHelper = CoverRecoveryHelper(
        this.context,
        bookDao,
        libraryRootDao,
        coverExtractor,
        scope
    )

    /**
     * 加载并解析指定书籍音频文件的外置字幕。
     *
     * @param bookFileId 关联的 BookFileEntity 唯一标识
     * @return 解析完成的字幕行列表
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> =
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)

    /**
     * 保存手动上传的自定义封面原图，并自动计算主题色与生成缩略图。
     * 在物理层面执行旧封面以及临时文件的安全清理，防止沙盒存储空间的无限膨胀。
     *
     * @param bookId 书籍 ID
     * @param tempCoverPath 临时裁剪封面文件在本地沙盒的路径
     * @return 封面提取与裁剪计算的物理保存结果，若提取失败则返回 null
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String): CoverExtractor.CoverResult? = withContext(Dispatchers.IO) {
        try {
            val book = bookDao.getBookById(bookId) ?: return@withContext null

            // 1. 调用提取器将临时封面物理拷贝至标准缓存区，并自动缩放渲染出对应的缩略图
            val result = coverExtractor.saveCustomCover(bookId, tempCoverPath)
            if (result.originalPath != null) {
                // 2. 清理书籍原先关联的封面原图缓存文件，确保无垃圾残留
                book.coverPath?.let { oldPath ->
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }
                
                // 3. 清理书籍原先关联的缩略图缓存文件，释放磁盘物理空间
                book.thumbnailPath?.let { oldPath ->
                    val oldFile = File(oldPath)
                    if (oldFile.exists()) {
                        oldFile.delete()
                    }
                }

                // 4. 清理编辑或裁剪时留在临时目录下的残留源文件
                val tempFile = File(tempCoverPath)
                if (tempFile.exists()) {
                    tempFile.delete()
                }
                return@withContext result
            }
            null
        } catch (e: Exception) {
            Log.e("PhysicalFileResolver", "执行自定义封面物理缓存保存时发生磁盘 I/O 异常: ", e)
            null
        }
    }

    /**
     * 检测主音频文件在虚拟文件系统（VFS，如 SAF 授权或 WebDAV 挂载）中的物理可达性。
     *
     * @param primaryFile 关联的音频文件实体
     * @return 如果物理文件存在且能够读取则返回 true，否则返回 false
     */
    suspend fun checkPrimaryAudioFileExists(primaryFile: BookFileEntity): Boolean = withContext(Dispatchers.IO) {
        fileAvailabilityChecker.checkBookFile(primaryFile).isAvailable
    }

    /**
     * 校验书籍的详情页可用性。
     * 检测其包含的任一有效音频轨是否在文件系统上真实存在且被授权访问。
     *
     * @param bookId 书籍 ID
     * @return 详情页可达性校验结果
     */
    suspend fun checkDetailAvailability(bookId: String): Boolean = withContext(Dispatchers.IO) {
        availabilityChecker.check(bookId).isAvailable
    }

    /**
     * 从给定的主要音频实体中物理提取多媒体元数据（如唱片集、音轨、流持续时长、年份及内置章节）。
     *
     * @param primaryFile 用于解析物理信息的音频文件实体
     * @return 包含提取到的结构化元数据对象
     */
    suspend fun extractMetadata(primaryFile: BookFileEntity) = withContext(Dispatchers.IO) {
        metadataResolver.extract(primaryFile)
    }

    companion object {
        @Volatile
        private var INSTANCE: PhysicalFileResolver? = null

        /**
         * 获取物理文件解析器的双检锁线程安全单例。
         */
        fun getInstance(context: Context): PhysicalFileResolver {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhysicalFileResolver(context).also { INSTANCE = it }
            }
        }
    }
}
