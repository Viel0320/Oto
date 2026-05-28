package com.viel.aplayer.data.gateway

/**
 * 领域解耦的网关接口：专注于有声书物理封面文件管理、元数据提取与主色调计算更新。
 *
 * 核心设计目标：
 * 1. 消除上帝类依赖：为上游详情页 ViewModel、扫描器等暴露专门的封面与元数据相关只读与只写逻辑。
 * 2. 促进依赖倒置：隔离封面自愈、音频物理提取等繁重底层 I/O 细节。
 */
interface CoverGateway {

    /**
     * 保存并覆盖用户的自定义书籍封面物理文件。
     *
     * @param bookId 目标书籍 ID
     * @param tempCoverPath 临时新封面文件的本地路径
     */
    suspend fun saveCustomCover(bookId: String, tempCoverPath: String)

    /**
     * 强行从音频轨道物理重扫并深度重建有声书的封面与全部结构化元数据。
     */
    suspend fun forceRegenerateCoverAndMetadata(bookId: String)

    /**
     * 更新并在数据库中物理写回当前书籍的主背景适配色 ARGB 缓存值。
     */
    fun updateBackgroundColor(id: String, color: Int)

    /**
     * 校验书籍详情的可达性。物理扫描检测主音频文件和封面缓存是否存在，
     * 用于详情页起播按钮置灰或显示不可用状态。
     */
    suspend fun checkDetailAvailability(bookId: String): Boolean

    /**
     * 在底层物理文件系统（VFS）中校验该书籍的主音轨文件是否在物理上真实可读存在。
     */
    suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean

    /**
     * 根据音频物理分轨文件的唯一 ID，异步在底层 VFS 中检索并加载解析其关联的外置字幕。
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<com.viel.aplayer.ui.player.components.SubtitleLine>
}
