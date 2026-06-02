package com.viel.aplayer.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/**
 * 播放计划传输转换工厂（PlaybackPlanBuilder）。
 * 专门用于将业务领域的播放计划（BookPlaybackPlan）转换为 Media3 底层 ExoPlayer 能够直接解析和调度的传输实体（MediaItem 列表）。
 * 通过将原本耦合在 PlaybackManager 中的复杂 MediaItem、MediaMetadata 构建流程物理抽取到此工厂类中，
 * 极大地简化了前后台桥接器的业务边界，消除了桥接层直接感知多音轨组装细节的架构弊端。
 */
object PlaybackPlanBuilder {

    /**
     * 将给定的书籍播放计划（BookPlaybackPlan）安全转换为对应的 MediaItem 列表。
     * 每个 MediaItem 均会共享相同的封面 URI，以减小跨进程会话传输时的复制成本，并自动挂载内部 VFS URI 以供播放。
     *
     * @param plan 书籍播放计划实体
     * @return 返回构建完毕的 ExoPlayer 媒体流播放项列表
     */
    fun buildMediaItems(plan: BookPlaybackPlan): List<MediaItem> {
        return plan.files.map { file ->
            // 构建元数据，每个分轨均只共享同一个封面 URI，以防将原图字节重复挂载导致 IPC 传输成本剧增
            val metadata = MediaMetadata.Builder()
                .setTitle(plan.title)
                .setArtist(plan.author)
                .setAlbumTitle(plan.title)
                .setArtworkUri(plan.artworkUri)
                .build()

            // 将 mediaId 构造成 "bookId:fileId" 的复合结构，以便字幕与进度持久化能够稳定地反查到具体的数据库记录
            MediaItem.Builder()
                .setMediaId(PlaybackMediaId.compose(plan.bookId, file.id))
                .setUri(VfsPlaybackUri.fromBookFile(file))
                .setMediaMetadata(metadata)
                .build()
        }
    }
}
