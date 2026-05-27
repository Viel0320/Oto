package com.viel.aplayer.media.service

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 播放故障安全及跳轨灾备自愈处理器。
 * 专门负责在前台播放期间，网络流明文传输的安全合规检测与拦截、运行期音频物理丢失引发的 I/O 异常捕捉，
 * 以及多音轨下自动标记损坏分轨并动态自愈起播下一个就绪（READY）可用轨道。
 * 物理隔离了网络/存储底层容灾复杂逻辑，净化了播放器核心服务的业务环境。
 */
@UnstableApi
class PlaybackFailureHandler(
    context: Context,
    private val serviceScope: CoroutineScope,
    private val libraryRepository: LibraryRepository,
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
     * 包含明文 HTTP 网络源的拦截审计、故障分轨标记及自动定位检索下一个 READY 音轨起播的完整容灾链路。
     *
     * @param player 核心 ExoPlayer 播放器内核句柄
     */
    fun handleUnavailableMediaItem(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        
        // 校验是否为合规的 APlayer 播放实体标识 (包含 bookId:bookFileId)
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")
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
            libraryRepository.markPlaybackFileUnavailable(bookId, queueIndex)
            Toast.makeText(appContext, "文件不可用，正在自动寻找下一就绪分轨", Toast.LENGTH_SHORT).show()
            com.viel.aplayer.logger.PlaybackFailureLogger.logTrackMarkedUnavailable(skipKey)

            // 3. 在书籍分轨清册中动态寻找检索下一个可用（READY）音频轨执行自愈
            val next = libraryRepository.findNextAvailablePlaybackFile(bookId, queueIndex)
            if (next != null) {
                val (nextIndex, _) = next
                com.viel.aplayer.logger.PlaybackFailureLogger.logSelfHealSuccess(nextIndex)
                player.seekTo(nextIndex, 0L)
                player.prepare()
                player.play()
            } else {
                // 若找不到后续任何可以无缝接档起播的正常音频，则强制停止，杜绝循环重复加载
                Log.w("FailureHandler", "未找到后续任何就绪可播音频分轨，终止播放队列")
                player.pause()
                player.stop()
            }
        }
    }

    /**
     * 清理防抖拦截状态。当播放器正常过渡切换到新分轨时，由外部调用此函数重置防抖锁。
     */
    fun clearSkipGuard() {
        unavailableSkipKey = null
    }
}
