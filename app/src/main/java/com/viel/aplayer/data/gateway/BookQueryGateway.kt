package com.viel.aplayer.data.gateway

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.flow.Flow

/**
 * 领域解耦的网关接口：专注于有声书基本信息检索、元数据、书签和章节的库表查询与维护。
 *
 * 核心设计目标：
 * 1. 消除上帝类依赖：为上游 ViewModel 及后台服务暴露狭窄且边界清晰的只读与只写逻辑，不掺杂任何播放进度保存、物理设备扫描触发等不相干职责。
 * 2. 促进依赖倒置：为彻底停用庞大臃肿的 LibraryRepository 提供前置抽象。
 */
interface BookQueryGateway {

    /**
     * 响应式观察媒体库内所有的有声书。
     */
    val audiobooks: Flow<List<BookWithProgress>>

    /**
     * 根据书籍的唯一主键 ID 同步获取对应的有声书实体。
     */
    suspend fun getBookById(id: String): BookEntity?

    /**
     * 响应式观察特定 ID 的书籍变化状态。
     */
    fun observeBookById(id: String): Flow<BookEntity?>

    /**
     * 根据关键字，模糊检索有声书。
     */
    fun searchAudiobooks(query: String): Flow<List<BookWithProgress>>

    /**
     * 按年份过滤有声书列表。
     */
    fun filterByYear(year: String): Flow<List<BookWithProgress>>

    /**
     * 按作者精确匹配过滤有声书列表。
     */
    fun filterByAuthor(author: String): Flow<List<BookWithProgress>>

    /**
     * 按作者精确匹配过滤有声书列表，带有返回数量限制并过滤掉当前正在阅读的书籍（用于个性化推荐模块）。
     */
    fun filterByAuthorLimited(author: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * 按讲述人精确过滤有声书。
     */
    fun filterByNarrator(narrator: String): Flow<List<BookWithProgress>>

    /**
     * 按讲述人精确过滤有声书，带有返回数量限制并过滤掉当前正在阅读的书籍（用于个性化推荐模块）。
     */
    fun filterByNarratorLimited(narrator: String, excludeId: String, limit: Int): Flow<List<BookWithProgress>>

    /**
     * 获取最近添加/导入的有声书列表，带有数量上限。
     */
    fun getRecentlyAdded(limit: Int): Flow<List<BookWithProgress>>

    /**
     * 获取除指定书籍本身外，与指定作者、讲述人都不重合的最近导入有声书（用于智能填充推荐槽位）。
     */
    fun getRecentlyAddedExclusive(
        currentId: String,
        authors: List<String>,
        narrators: List<String>,
        limit: Int
    ): Flow<List<BookWithProgress>>

    /**
     * 逻辑删除指定书籍记录。
     */
    suspend fun deleteBook(bookId: String)

    /**
     * 手动更新有声书的阅读状态。
     */
    suspend fun updateBookReadStatus(bookId: String, readStatus: String)

    /**
     * 在元数据面板上手动覆盖并保存书籍的文字元数据详情。
     */
    suspend fun updateBookDetails(
        id: String,
        title: String,
        author: String,
        narrator: String,
        description: String,
        year: String
    )

    /**
     * 同步获取指定有声书所包含的物理分轨音频文件清册。
     */
    suspend fun getFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * 同步获取指定有声书包含的映射物理文件清单（包含音频以及 XML 元数据清单）。
     */
    suspend fun getAllFilesForBookSync(bookId: String): List<BookFileEntity>

    /**
     * 响应式观察最后一次扫描会话的状态结果。
     */
    fun observeLatestScanSession(): Flow<ScanSessionEntity?>

    /**
     * 构建并同步获取书籍的轻量级播放计划（包含分轨、段落索引及历史记忆位置）。
     */
    suspend fun getPlaybackPlan(bookId: String): BookPlaybackPlan?

    /**
     * 供后台扫描静默覆盖写入书籍的标签提取元数据信息。
     */
    fun updateMetadata(
        bookId: String,
        title: String?,
        author: String?,
        narrator: String?,
        description: String?,
        duration: Long
    )

    /**
     * 响应式订阅观察指定有声书的章节列表。
     */
    fun getChapters(bookId: String): Flow<List<ChapterWithBookFile>>

    /**
     * 同步获取指定有声书的章节实体列表。
     */
    suspend fun getChaptersForBookSync(bookId: String): List<ChapterWithBookFile>

    /**
     * 强制覆盖或批量保存提取得到的章节数据。
     */
    fun saveChapters(bookId: String, chapters: List<ChapterEntity>)

    /**
     * 响应式订阅观察指定书籍的用户书签标记。
     */
    fun getBookmarks(bookId: String): Flow<List<BookmarkEntity>>

    /**
     * 向有声书中追加一个新的书签标记。
     */
    suspend fun addBookmark(bookId: String, position: Long, title: String)

    /**
     * 覆写更新单个书签实体的信息。
     */
    suspend fun updateBookmark(bookmark: BookmarkEntity)

    /**
     * 删除特定的书签记录。
     */
    suspend fun deleteBookmark(bookmark: BookmarkEntity)
}
