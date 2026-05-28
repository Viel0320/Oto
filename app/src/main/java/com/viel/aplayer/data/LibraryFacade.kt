package com.viel.aplayer.data

import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.data.entity.BookProgressEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.BookmarkEntity
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.entity.ChapterWithBookFile
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.data.gateway.BookQueryGateway
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.CoverGateway
import com.viel.aplayer.data.gateway.SearchHistoryGateway
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.flow.Flow

/**
 * 新的媒体库高层业务门面（LibraryFacade）。
 *
 * 核心设计目标：
 * 1. 精细化高层网关：通过持有分域网关接口，代替原先具有一千多行、承载各种不相干具体业务逻辑的 [LibraryRepository]。
 * 2. 完美的类委托：利用 Kotlin 委托机制，一行代码将所有分域 Gateway 接口的方法自动路由给对应的实现服务，实现极高的模块内聚和解耦。
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler,
    private val libraryRootGateway: LibraryRootGateway,
    private val coverGateway: CoverGateway,
    private val searchHistoryGateway: SearchHistoryGateway,
    @Suppress("DEPRECATION") private val legacyRepository: LibraryRepository
) : BookQueryGateway by bookQueryGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler,
    LibraryRootGateway by libraryRootGateway,
    CoverGateway by coverGateway,
    SearchHistoryGateway by searchHistoryGateway {

    /**
     * 过渡期方法：根据指定音频分轨物理 ID 加载并流式解析外置字幕，供 PlayerViewModel 平滑过渡。
     * 将在 M5 补齐后迁移至分域网关。
     */
    @Suppress("DEPRECATION")
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<com.viel.aplayer.ui.player.components.SubtitleLine> =
        legacyRepository.loadSubtitlesForBookFile(bookFileId)

    /**
     * 详尽的中文注释：
     * 覆盖重写 ProgressGateway 接口中的 checkCurrentPlaybackFileAvailability 方法。
     * 在过渡期间，暂时通过 legacyRepository 代理物理检查当前有声书播放文件是否可读就绪。
     * 后续将在 M5 彻底对齐 Gateway 接口后清除此处的 Deprecated 仓库引用。
     */
    @Suppress("DEPRECATION")
    override suspend fun checkCurrentPlaybackFileAvailability(bookId: String): Boolean =
        legacyRepository.checkCurrentPlaybackFileAvailability(bookId)
}
