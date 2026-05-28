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
import com.viel.aplayer.media.BookPlaybackPlan
import kotlinx.coroutines.flow.Flow

/**
 * 新的媒体库高层业务门面（LibraryFacade）。
 *
 * 核心设计目标：
 * 1. 精细化高层网关：通过持有分域网关接口（BookQueryGateway、ProgressGateway、ScanScheduler），
 *    代替原先具有一千多行、承载各种不相干具体业务逻辑的 [LibraryRepository]。
 * 2. 支持平滑过渡：供上游高频调用的 ViewModel 及后台服务迁移，并且内部没有任何具体的数据库或文件IO逻辑，全部代理给各分域网关。
 */
class LibraryFacade(
    private val bookQueryGateway: BookQueryGateway,
    private val progressGateway: ProgressGateway,
    private val scanScheduler: ScanScheduler,
    @Suppress("DEPRECATION") private val legacyRepository: LibraryRepository
) : BookQueryGateway by bookQueryGateway,
    ProgressGateway by progressGateway,
    ScanScheduler by scanScheduler {

    /**
     * 过渡期方法：校验有声书在运行期的可达性，供 DetailViewModel 平滑过渡。
     * 后续将在 M5 补齐 CoverGateway 后，替换为对应的服务网关实现。
     */
    @Suppress("DEPRECATION")
    suspend fun checkDetailAvailability(bookId: String): Boolean =
        legacyRepository.checkDetailAvailability(bookId)

    /**
     * 过渡期方法：写回提取的自定义封面背景色缓存。
     * 后续将在 M5 补齐 CoverGateway 后，替换为对应的服务网关实现。
     */
    @Suppress("DEPRECATION")
    fun updateBackgroundColor(id: String, color: Int) =
        legacyRepository.updateBackgroundColor(id, color)

    /**
     * 过渡期方法：在底层虚拟文件系统（VFS）中物理校验主音轨文件是否存在，供 LibraryViewModel 平滑过渡。
     * 将在 M5 补齐后迁移至分域网关。
     */
    @Suppress("DEPRECATION")
    suspend fun checkPrimaryAudioFileExists(bookId: String): Boolean =
        legacyRepository.checkPrimaryAudioFileExists(bookId)

    /**
     * 过渡期方法：强制从音频轨中深度重建有声书的物理封面与全部结构化元数据，供 LibraryViewModel 平滑过渡。
     * 将在 M5 补齐后迁移至分域网关。
     */
    @Suppress("DEPRECATION")
    suspend fun forceRegenerateCoverAndMetadata(bookId: String) =
        legacyRepository.forceRegenerateCoverAndMetadata(bookId)

    /**
     * 过渡期方法：注册系统本地 SAF 授权的书库根目录并即刻发起物理文件增量同步，供 LibraryViewModel 平滑过渡。
     * 将在 M5 补齐后迁移至分域网关。
     */
    @Suppress("DEPRECATION")
    fun addLibraryRootAndScheduleSync(uri: android.net.Uri, trigger: String = "USER") =
        legacyRepository.addLibraryRootAndScheduleSync(uri, trigger)

    /**
     * 过渡期方法：安全清空全部的历史检索词清册，供 LibraryViewModel 平滑过渡。
     * 将在 M5 补齐后迁移至分域网关。
     */
    @Suppress("DEPRECATION")
    suspend fun clearHistory() =
        legacyRepository.clearHistory()

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
