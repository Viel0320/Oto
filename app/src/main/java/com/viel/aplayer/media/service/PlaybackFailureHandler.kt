package com.viel.aplayer.media.service

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.gateway.ProgressGateway
import com.viel.aplayer.media.PlaybackMediaId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放故障安全及跳轨灾备自愈处理器。
 * 专门负责在前台播放期间，网络流明文传输的安全合规检测与拦截、运行期音频物理丢失引发的 I/O 异常捕捉，
 * 并在分轨发生物理不可用时挂载拦截，通知 UI 弹窗交由用户进行决定是否跨越坏轨跳转。
 * 物理隔离了网络/存储底层容灾复杂逻辑，净化了播放器核心服务的业务环境。
 * 
 * 在 M4.4 重构中，将旧的 LibraryRepository 替换为更精确 of ProgressGateway 接口，
 * 遵循单一职责和最小知识原则，提高架构组件的解耦纯净度。
 */
@UnstableApi
class PlaybackFailureHandler(
    context: Context,
    private val serviceScope: CoroutineScope,
    private val progressGateway: ProgressGateway,
    private val settingsRepository: AppSettingsRepository
) {
    // 避免 Context 内存泄露，获取应用级别的唯一 Context 句柄
    private val appContext = context.applicationContext

    // 缓存当前正在执行跳轨重试的防抖键值 (格式为 bookId:queueIndex)
    // 防止同一个故障媒体项连续抛出多次 IO 回调导致无限死循环 skip 调度
    private var unavailableSkipKey: String? = null

    /**
     * 判断播放器异常是否属于音频文件物理不存在、无网络或权限丢失等物理加载不可用异常。
     * 排除由于解析器（ParserException）引起的文件损坏等内部逻辑问题。
     *
     * @param error 播放器抛出的底层 PlaybackException 异常
     * @return 如果属于物理加载错误则返回 true，否则返回 false
     */
    fun isUnavailableMediaError(error: PlaybackException): Boolean {
        val isIoError = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> true
            else -> false
        }
        // 解析异常表示文件被寻址并成功打开但因为内容损坏无法解码，将其和文件丢失/读取失败物理区分开
        return isIoError && error.cause !is androidx.media3.common.ParserException
    }

    /**
     * 处理发生物理加载故障的媒体节点。
     * 停止当前播放并弹窗/Toast 通知用户，将事件传递给前台控制器，由用户在弹窗中选择是否执行跳轨，防止打乱收听进度。
     *
     * @param player 核心 ExoPlayer 播放器内核句柄
     * @param mediaSession 核心媒体会话实例，用于向订阅的 MediaController 发送指令
     */
    fun handleUnavailableMediaItem(player: Player, mediaSession: MediaSession?) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaParts = PlaybackMediaId.parse(mediaItem.mediaId) ?: return
        val bookId = mediaParts.bookId
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        
        // 防抖校验：同一首有声书分轨只执行一次跳轨重试，避免 ExoPlayer 频繁回调发生卡死
        val skipKey = "$bookId:$queueIndex"
        if (unavailableSkipKey == skipKey) return
        unavailableSkipKey = skipKey

        serviceScope.launch {
            // 1. 网络音频安全审计拦截守护
            val currentUri = mediaItem.localConfiguration?.uri?.toString() ?: ""
            if (currentUri.startsWith("http://")) {
                val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                if (!isAllowed) {
                    Toast.makeText(appContext, "安全拦截：明文 HTTP 播放未授权。请在设置中允许。", Toast.LENGTH_LONG).show()
                    player.pause()
                    player.stop()
                    Log.w("FailureHandler", "安全拦截：用户未授权播放明文 HTTP 协议音频流")
                    return@launch
                }
            }

            // 2. 标记当前的物理分轨文件在数据库中为物理丢失 (UNAVAILABLE)
            // 使用 progressGateway 的 markPlaybackFileUnavailable 方法将故障音频轨在数据库中标记为不可读，保持数据完整性
            progressGateway.markPlaybackFileUnavailable(bookId, queueIndex)
            
            // 详尽的中文注释：在此处将原本直接自愈跳轨的行为拦截，立即将播放器暂停并终止，避免底层流尝试循环重新加载导致假死
            player.pause()
            player.stop()
            
            // 详尽的中文注释：Toast 提示当前轨道由于物理不可用无法继续收听，让用户有所感知，并引导用户关注接下来的弹窗操作
            Toast.makeText(appContext, "当前分轨文件不可用，请确认是否跳轨收听", Toast.LENGTH_LONG).show()
            com.viel.aplayer.logger.PlaybackFailureLogger.logTrackMarkedUnavailable(skipKey)

            // 3. 详尽的中文注释：通过 MediaSession 向所有连接的外部 MediaController 广播物理轨道不可用的自定义消息。
            // 并在 Bundle 参数中封装当前书籍的 ID 与出错的分轨序列号，用以前台 UI 异步消费，拉起二次确认跳轨弹窗。
            val args = Bundle().apply {
                putString("bookId", bookId)
                putInt("queueIndex", queueIndex)
            }
            mediaSession?.broadcastCustomCommand(SessionCommand("EVENT_TRACK_UNAVAILABLE", Bundle.EMPTY), args)
        }
    }

    /**
     * 清理防抖拦截状态。当播放器正常过渡切换到新分轨时，由外部调用此函数重置防抖锁。
     */
    fun clearSkipGuard() {
        unavailableSkipKey = null
    }
}
