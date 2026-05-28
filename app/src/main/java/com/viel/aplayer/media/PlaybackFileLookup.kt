package com.viel.aplayer.media

import com.viel.aplayer.data.dao.BookDao
import com.viel.aplayer.data.entity.BookFileEntity

/**
 * 播放音频文件检索接口。
 *
 * 核心设计目标：
 * 1. 消除播放层对整个 AppDatabase 的重量级硬编码依赖。
 * 2. 促进依赖倒置，允许播放组件在独立的单单元测试中通过 Mock 注入进行行为校验。
 */
interface PlaybackFileLookup {
    /**
     * 根据音频分轨的唯一物理 ID 异步检索对应的书籍文件记录。
     */
    suspend fun getBookFileById(bookFileId: String): BookFileEntity?
}

/**
 * 基于 Room BookDao 实现的默认播放音频检索器。
 */
class DefaultPlaybackFileLookup(
    private val bookDao: BookDao
) : PlaybackFileLookup {
    
    override suspend fun getBookFileById(bookFileId: String): BookFileEntity? {
        // 直接代理至低侵入的 Room DAO 层查询书籍文件
        return bookDao.getBookFileById(bookFileId)
    }
}
