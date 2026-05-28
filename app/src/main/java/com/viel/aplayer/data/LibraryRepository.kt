package com.viel.aplayer.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.store.SearchHistoryEntry
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.ui.player.components.SubtitleLine
import kotlinx.coroutines.flow.Flow

/**
 * 媒体库核心门面仓库（Facade Pattern），对外提供统一的数据及物理操作网关入口。
 * 
 * 经过架构解耦拆分重构，该类原本的上帝类职责已被完全剥离，具体业务逻辑下沉至三个独立领域的子功能仓库：
 * 1. [PhysicalFileResolver]：专门处理本地文件 I/O 交互（字幕解析、封面物理缓存清理/保存及 VFS 检测）。
 * 2. [PlaybackHistoryRepository]：专门处理播放进度映射落库、自动阅读状态转换及运行期音频就绪自愈。
 * 3. [BookLibraryRepository]：专注有声书实体、章节、书签的 Room DB 交互、书库同步以及扫描清册事务管理。
 *
 * 门面类不仅使核心业务能够高度自治与低耦合，同时在完全不改动外部公开方法签名的情况下实现了平滑重构，
 * 保障了上游视图模型（ViewModel）和前台服务（PlaybackService）的向后兼容，真正做到了“零编译破坏”。
 */
@OptIn(UnstableApi::class)
class LibraryRepository private constructor(
    context: Context,
    vfsFileInterface: com.viel.aplayer.library.vfs.VfsFileInterface? = null
) {

    // 实例化三大子领域仓库实例，在 PhysicalFileResolver 处注入共享的 VFS 读取单例
    private val bookLibrary = BookLibraryRepository.getInstance(context)
    private val playbackHistory = PlaybackHistoryRepository.getInstance(context)
    private val physicalFileResolver = PhysicalFileResolver.getInstance(context, vfsFileInterface)

    // ==========================================
    // 1. 物理文件系统及字幕交互（委托给 PhysicalFileResolver）
    // ==========================================

    /**
     * 根据音频书籍文件唯一标识，加载并以流式解析外置字幕。
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> =
        physicalFileResolver.loadSubtitlesForBookFile(bookFileId)

    /**
     * 底层虚拟文件系统（VFS）校验主音频轨文件是否真实存在且可供流式读取。
     */
    suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean {
        // 首先从 Room 库获取该书籍的标准分轨列表
        val primaryFile = bookLibrary.getFilesForBookSync(bookId).firstOrNull() ?: return false
        // 委托给物理文件解析器进行文件的安全校验
        return physicalFileResolver.checkPrimaryAudioFileExists(primaryFile)
    }

    /**
     * 校验有声书详情页在运行期的物理可达性与外部存储权限可用性。
     */
    suspend fun checkDetailAvailability(bookId: String): Boolean =
        physicalFileResolver.checkDetailAvailability(bookId)

    // ==========================================
    // 2. 播放历史与进度管理（委托给 PlaybackHistoryRepository）
    // ==========================================

    /**
     * 高频更新播放位置。
     * 将全局的绝对毫秒进度映射为对应音频分轨内的相对偏移并异步执行落库更新，同步转换阅读状态。
     */
    fun updateProgress(bookId: String, position: Long) =
        playbackHistory.updateProgress(bookId, position)

    /**
     * 强刷并保存进度实体，自动强制转换到 IO 线程安全异步落库。
     */
    suspend fun saveProgress(progress: BookProgressEntity) =
        playbackHistory.saveProgress(progress)

    /**
     * 同步获取最近一次播放进度，用于冷启动紧凑播放器的重载。
     */
    suspend fun getLastPlayedProgressSync(): BookProgressEntity? =
        playbackHistory.getLastPlayedProgressSync()

    /**
     * 恢复前台播放进度时的音频就绪可用性判定。
     */
    suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean = 
        playbackHistory.checkCurrentPlaybackFileAvailability(bookId)

    /**
     * 运行期发现音频不可读时，将其标记为物理丢失不可用。
     */
    suspend fun markPlaybackFileUnavailable(bookId: String, queueIndex: Int) = 
        playbackHistory.markPlaybackFileUnavailable(bookId, queueIndex)

    /**
     * 当前音轨丢失时，在分轨清册中寻找检索下一个就绪的音频轨。
     */
    suspend fun findNextAvailablePlaybackFile(bookId: String, afterQueueIndex: Int): Pair<Int, BookFileEntity>? = 
        playbackHistory.findNextAvailablePlaybackFile(bookId, afterQueueIndex)

    // ==========================================
    // 3. 有声书与书签 DB 交互（委托给 BookLibraryRepository）
    // ==========================================

    /**
     * 内存中根目录的最新缓存快照，用于消除冷启动阶段的 UI 大幅摆动。
     */
    fun getCachedLibraryRoots(): List<LibraryRootEntity> =
        bookLibrary.getCachedLibraryRoots()

    /**
     * 观察响应式搜索历史流。
     */
    val searchHistory: Flow<List<SearchHistoryEntry>> get() = bookLibrary.searchHistory

    /**
     * 新增搜索记录。
     */
    suspend fun addToHistory(query: String) = bookLibrary.addToHistory(query)

    /**
     * 删除指定的搜索记录。
     */
    suspend fun deleteFromHistory(history: SearchHistoryEntry) = bookLibrary.deleteFromHistory(history)

    /**
     * 清空所有的搜索记录。
     */
    suspend fun clearHistory() = bookLibrary.clearHistory()

    /**
     * 配置添加本地 SAF 授权的书库根目录。
     */
    suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = bookLibrary.setLibraryRoot(uri)

    /**
     * 配置添加 WebDAV 书库根目录。
     */
    suspend fun addWebDavLibraryRoot(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String
    ): LibraryRootEntity = bookLibrary.addWebDavLibraryRoot(url, username, password, displayName, basePath)

    /**
     * 添加 SAF 本地目录并异步触发后台库重扫。
     */
    fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String = "USER") =
        bookLibrary.addLibraryRootAndScheduleSync(uri, trigger)

    /**
     * 添加 WebDAV 远端目录并异步触发后台库重扫。
     */
    fun addWebDavLibraryRootAndScheduleSync(
        url: String,
        username: String,
        password: String,
        displayName: String,
        basePath: String,
        trigger: String = "USER"
    ) = bookLibrary.addWebDavLibraryRootAndScheduleSync(url, username, password, displayName, basePath, trigger)

    /**
     * 刷新所有书库的系统级读取授权状态。
     */
    suspend fun refreshLibraryRootStatuses() = bookLibrary.refreshLibraryRootStatuses()

    /**
     * 在当前挂载的根清册中执行前台库同步重扫。
     */
    suspend fun syncLibrary(trigger: String = "USER") = bookLibrary.syncLibrary(trigger)

    /**
     * 发起并向专属后台协程队列中派发库重扫任务。
     */
    fun scheduleLibrarySync(trigger: String = "USER") = bookLibrary.scheduleLibrarySync(trigger)

    /**
     * 响应式观察所有有声书（包含封面物理缓存的自动异步自愈）。
     */
    val audiobooks: Flow<List<BookWithProgress>> get() = bookLibrary.audiobooks

    /**
     * 根据关键字检索有声书列表。
     */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>> = bookLibrary.searchAudiobooks(query)

    /**
     * 按年份范围筛选。
     */
    fun filterByYear(year: String): Flow<List<BookWithProgress>> = bookLibrary.filterByYear(year)
    
    /**
     * 按作者筛选。
     */
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>> = bookLibrary.filterByAuthor(author)
    
    /**
     * 按作者筛选（进行分页限制并自动排除当前所读）。
     */
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookLibrary.filterByAuthorLimited(author, excludeId, limit)
    
    /**
     * 按讲述人筛选。
     */
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>> = bookLibrary.filterByNarrator(narrator)

    /**
     * 按讲述人筛选（进行分页限制并自动排除当前所读）。
     */
    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>> = 
        bookLibrary.filterByNarratorLimited(narrator, excludeId, limit)

    /**
     * 获取最近入库的书籍列表。
     */
    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>> = bookLibrary.getRecentlyAdded(limit)

    /**
     * 获取除自身外的最近推荐书籍。
     */
    fun getRecentlyAddedExclusive(currentId: String, authors: List<String>, narrators: List<String>, limit: Int): Flow<List<BookWithProgress>> = 
        bookLibrary.getRecentlyAddedExclusive(currentId, authors, narrators, limit)

    /**
     * 根据主键 ID 获取指定的有声书实体（包含封面物理自愈）。
     */
    suspend fun getBookById(id: String): BookEntity? = bookLibrary.getBookById(id)

    /**
     * 响应式订阅观察特定 ID 的书籍数据变化。
     */
    fun observeBookById(id: String): Flow<BookEntity?> = bookLibrary.observeBookById(id)

    /**
     * 逻辑软删除指定书籍。
     */
    suspend fun deleteBook(bookId: String) = bookLibrary.deleteBook(bookId)

    /**
     * 手动更新有声书的阅读状态。
     */
    suspend fun updateBookReadStatus(bookId: String, readStatus: String) = 
        bookLibrary.updateBookReadStatus(bookId, readStatus)

    /**
     * 强行从音频物理分轨中重新解析章节、基本元数据并强刷物理封面缓存。
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String) =
        bookLibrary.forceRegenerateCoverAndMetadata(bookId)

    /**
     * 异步更新书籍自定义主色调（计算自提取后的封面大图）。
     */
    fun updateBackgroundColor(id: String, color: Int) = bookLibrary.updateBackgroundColor(id, color)

    /**
     * 在元数据编辑面板手动覆盖保存书籍信息并触发 UI 重刷。
     */
    suspend fun updateBookDetails(id: String, title: String, author: String, narrator: String, description: String, year: String) =
        bookLibrary.updateBookDetails(id, title, author, narrator, description, year)

    /**
     * 手动上传自定义封面路径，重新解析缩略图并彻底清除原物理缓存残留。
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String) =
        bookLibrary.saveCustomCover(bookId, tempCoverPath)

    /**
     * 同步获取书籍分轨文件清册。
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity> =
        bookLibrary.getFilesForBookSync(bookId)

    /**
     * 同步获取全部的映射文件（包含音频及 XML 媒体清单）。
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity> =
        bookLibrary.getAllFilesForBookSync(bookId)

    /**
     * 订阅观察最新的一次重扫扫描历史会话。
     */
    fun observeLatestScanSession(): Flow<ScanSessionEntity?> = bookLibrary.observeLatestScanSession()

    /**
     * 订阅观察媒体库中所有注册的书库根节点。
     */
    fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> = bookLibrary.observeLibraryRoots()

    /**
     * 为播放器构建启动阶段的极轻量级播放计划（预置封面物理自愈）。
     */
    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan? = bookLibrary.getPlaybackPlan(bookId)

    /**
     * 为有声书静默覆写更新提取出来的容器标签元数据（如唱片名、讲述人、时长）。
     */
    fun updateMetadata(bookId: String, title: String?, author: String?, narrator: String?, description: String?, duration: Long) =
        bookLibrary.updateMetadata(bookId, title, author, narrator, description, duration)

    /**
     * 响应式观察书籍物理章节列表。
     */
    fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>> = bookLibrary.getChapters(bookId)

    /**
     * 同步获取指定书籍的物理章节快照。
     */
    suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile> =
        bookLibrary.getChaptersForBookSync(bookId)

    /**
     * 强制覆写写入物理章节。
     */
    fun saveChapters(bookId: String, chapters: List<ChapterEntity>) =
        bookLibrary.saveChapters(bookId, chapters)

    /**
     * 响应式订阅观察书籍书签。
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>> = bookLibrary.getBookmarks(bookId)

    /**
     * 写入新书签并附带指纹锚点。
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String) =
        bookLibrary.addBookmark(bookId, position, title)

    /**
     * 覆盖修改单条书签记录。
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity) = bookLibrary.updateBookmark(bookmark)

    /**
     * 删除指定的书签记录。
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity) = bookLibrary.deleteBookmark(bookmark)

    /**
     * 仅清理与书库根相关的底层数据（包括物理封面、缩略图清理，系统 SAF 权限释放/WebDAV 凭证删除，Room 数据库记录删除）。
     * 此操作不与播放层 PlaybackManager 进行任何直接交互，以解耦数据层与播放控制。
     */
    suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit =
        bookLibrary.deleteLibraryRootDataOnly(root)

    /**
     * 移除特定书库根目录。
     * @deprecated 请改用 [DeleteLibraryRootUseCase] 进行播放状态与数据清理的流程编排，以消除门面仓库层对媒体播放器的硬编码依赖。
     */
    @Deprecated("请改用 DeleteLibraryRootUseCase，以规避门面仓库直接依赖播放层的问题")
    suspend fun deleteLibraryRoot(root: LibraryRootEntity): Boolean = bookLibrary.deleteLibraryRoot(root)

    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: LibraryRepository? = null

        /**
         * 获取媒体库门面仓库的双检锁线程安全单例。
         * 支持在创建单例时接收共享的 VfsFileInterface 读取单例并传递给物理文件解析器，以确保全应用统一。
         */
        fun getInstance(
            context: Context,
            vfsFileInterface: com.viel.aplayer.library.vfs.VfsFileInterface? = null
        ): LibraryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LibraryRepository(context, vfsFileInterface).also { INSTANCE = it }
            }
        }
    }
}
