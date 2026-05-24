package com.viel.aplayer.media.service

import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PositionMapper
import com.viel.aplayer.media.SubtitleFileResolver
import com.viel.aplayer.media.VfsPlaybackDataSource

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    // 通知栏使用独立 session，避免通知显示进度反向污染 App/UI controller。
    private var notificationSession: MediaSession? = null

    // 为每一次改动添加详尽的中文注释：物理记录是否由于外部被动抢占丢失音频焦点导致暂停了播放，用于在重获音频焦点时完美进行自适应恢复播放。
    private var isPausedByLossOfFocus = false

    // 为每一次改动添加详尽的中文注释：缓存 API 26 (Android 8.0) 及以上版本的高级 AudioFocusRequest 物理请求描述实体，方便动态跨生命周期销毁与绑定。
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    // 为每一次改动添加详尽的中文注释：自定义的音频焦点变化监听器。
    // 当“通知避让”开启时，如果接收到 transient（如通知、来电铃声等）临时丢失焦点信号：
    // 首先在前台将 ignoreNextAutoRewind 设为 true 强力拦截自动回退，随后主动暂停播放器，重获焦点时自动恢复播放且绝不回退。
    private val audioFocusChangeListener = android.media.AudioManager.OnAudioFocusChangeListener { focusChange ->
        val player = mediaSession?.player ?: return@OnAudioFocusChangeListener
        serviceScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            if (settings.isNotificationAvoidanceEnabled) {
                when (focusChange) {
                    android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                        // 详尽的中文注释：永久失去焦点（例如被其它播放器强制中断占用），不属于临时丢失，重设状态并暂停。
                        isPausedByLossOfFocus = false
                        player.pause()
                    }
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // 详尽的中文注释：被动临时失去焦点。只有当播放器正在播放时，才进行暂停并设定标志。
                        // 在暂停前，通知 PlaybackManager 忽略下一次由于状态变动触发的自动回退逻辑，保障进度完美连续。
                        if (player.isPlaying) {
                            isPausedByLossOfFocus = true
                            com.viel.aplayer.media.PlaybackManager.getInstance(applicationContext).ignoreNextAutoRewind = true
                            player.pause()
                        }
                    }
                    android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                        // 详尽的中文注释：重新获取到系统完整的音频焦点。如果先前由于焦点原因被迫暂停，则立即拉起播放恢复，并重置被动状态。
                        if (isPausedByLossOfFocus) {
                            isPausedByLossOfFocus = false
                            player.play()
                        }
                    }
                }
            }
        }
    }
    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var notificationPlayer: NotificationProgressPlayer
    // 通知层缓存当前书籍 ID，用来防止切书瞬间误用上一书的文件列表。
    private var notificationBookId: String? = null
    // 通知层缓存当前书籍文件列表，只服务于通知命令和进度显示映射。
    private var notificationFiles: List<BookFileEntity> = emptyList()
    // ExoPlayer may report the same failing item more than once; keep one skip job per queue item.
    private var unavailableSkipKey: String? = null
    private var exitJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 为每一次改动添加详尽的中文注释：缓存自定义的静音跳过处理器，以便轮询其 skippedFrames 计数器
    private var customSilenceProcessor: androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor? = null
    // 为每一次改动添加详尽的中文注释：缓存倍速处理器，以在自定义 AudioSink 时保持倍速播放功能正常运作。使用正确的 common.audio 包路径。
    private var sonicAudioProcessor: androidx.media3.common.audio.SonicAudioProcessor? = null
    // 为每一次改动添加详尽的中文注释：缓存底层的 AudioSink 实例，供提取内部静音跳过处理器时使用。
    private var localSink: androidx.media3.exoplayer.audio.AudioSink? = null

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        // 为本次媒体通知点击修复添加注释：给通知 sessionActivity 使用稳定 requestCode，避免复用到其他 PendingIntent 后丢失打开播放 overlay 的 extra。
        private const val REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION = 4100
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        
        libraryRepository = LibraryRepository.getInstance(this)
        settingsRepository = AppSettingsRepository.getInstance(this)

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30000, // Min buffer 30s
                30000, // Max buffer 30s
                1000,  // Buffer for playback 1s
                2000   // Buffer for playback after rebuffer 2s
            )
            .build()

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this@PlaybackService) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink? {
                // 为每一次改动添加详尽的中文注释：
                // 1. 创建用于调节播放倍速的 SonicAudioProcessor。使用正确的 common.audio 包路径。
                // 必须在 AudioSink 中显式组合传入它，否则变速功能将彻底瘫痪。
                val sonicProcessor = androidx.media3.common.audio.SonicAudioProcessor()
                sonicAudioProcessor = sonicProcessor
                
                // 2. 构建默认的 DefaultAudioSink。
                // 我们在此不再手动注入外部创建的 SilenceSkippingAudioProcessor，以规避 API 编译错误。
                val sink = androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(sonicProcessor))
                    .build()
                localSink = sink
                
                // 3. 立即通过高安全性反射，将 ExoPlayer 内部自动创建并持有的真实 SilenceSkippingAudioProcessor 提取出来
                findSilenceProcessorFromSink(sink)
                
                // 4. 提取成功后，同步初始化设置项中保存的最小时长
                serviceScope.launch {
                    val settings = settingsRepository.settingsFlow.first()
                    updateSilenceProcessorDurationHot(settings.skipSilenceDurationThreshold)
                }
                
                return sink
            }
        }.apply {
            // 允许 Media3 在硬件解码器失败时尝试备用解码器（甚至是软件解码器），增加稳定性。
            setEnableDecoderFallback(true)
        }

        // 详尽的中文注释：利用常量字面值定义直接读取采样表标志（1 shl 2，即十进制 4），物理防范编译期类符号未解析缺陷。
        val flagReadSampleTableDirectly = 1 shl 2
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            .setAdtsExtractorFlags(AdtsExtractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING)
            // 详尽的中文注释：极长 M4B 有声书内存映射优化。
            // 传入直接读取采样表标志数值，规避在加载超长（数十小时）M4B 文件时在 JVM 堆中展开数百万个 Sample 对象而招致 OOM 的隐患。
            .setMp4ExtractorFlags(flagReadSampleTableDirectly)

        // 为每一次改动添加详尽的中文注释：播放器媒体源只接入 VFS DataSource，避免 ExoPlayer 继续从 BookFileEntity.uri 直接读取 SAF/远程原始地址。
        val mediaSourceFactory = DefaultMediaSourceFactory(VfsPlaybackDataSource.Factory(this), extractorsFactory)

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(30000)
            .build()

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlaybackService", "Player error: ${error.message}", error)
                if (isUnavailableMediaError(error)) {
                    handleUnavailableMediaItem(player)
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                    val cause = error.cause
                    if (cause is androidx.media3.common.ParserException) {
                        Log.e("PlaybackService", "Parser exception: contentIsMalformed=${cause.contentIsMalformed}, dataType=${cause.dataType}")
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                // A successful transition clears the previous failed-item guard.
                unavailableSkipKey = null
                updateNotificationTimeline(mediaItem)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    // 详尽的中文注释：当检测到整个播放队列播放结束（STATE_ENDED）时，弹出提示并启动 5 秒倒计时。
                    // 倒计时结束后，清空播放队列并销毁服务。
                    exitJob?.cancel()
                    exitJob = serviceScope.launch {
                        Toast.makeText(this@PlaybackService, "播放结束，5秒后将自动关闭", Toast.LENGTH_SHORT).show()
                        delay(5000)
                        player.clearMediaItems()
                        stopSelf()
                    }
                } else {
                    // 详尽的中文注释：若状态变为非结束（如用户手动操作），则取消待定的退出任务。
                    exitJob?.cancel()
                    exitJob = null
                }
            }

            // 为每一次改动添加详尽的中文注释：重写 onIsPlayingChanged 监听底层实际的物理播放状态。
            // 当“通知避让”开启时，如果播放器切入正在播放状态（isPlaying = true），我们首先重置被动焦点丢失状态并向系统申请音频焦点；
            // 如果播放器进入暂停状态，且此时并不是由于系统临时音频焦点丢失（如通知）造成的被迫暂停，我们就主动放弃我们所持有的系统音频焦点。
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                serviceScope.launch {
                    val settings = settingsRepository.settingsFlow.first()
                    if (settings.isNotificationAvoidanceEnabled) {
                        if (isPlaying) {
                            isPausedByLossOfFocus = false
                            requestMyAudioFocus()
                        } else {
                            if (!isPausedByLossOfFocus) {
                                abandonMyAudioFocus()
                            }
                        }
                    }
                }
            }
        })
        notificationPlayer = NotificationProgressPlayer(player)
        observeNotificationProgressMode()

        // 为每一次改动添加详尽的中文注释：订阅 AppSettings 配置流，实现“自动跳过静音期”全局开关的热应用与最小时长的动态热更新，并实时热应用“通知避让”音频焦点模式。
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                player.skipSilenceEnabled = settings.isSkipSilenceEnabled
                // 实时热更新底层判定时长参数，实现拖动滑块即时生效
                updateSilenceProcessorDurationHot(settings.skipSilenceDurationThreshold)

                // 为每一次改动添加详尽的中文注释：实时响应“通知避让”机制的开关变动。
                // 1. 若开启通知避让：我们将底层 ExoPlayer 的自动音频焦点处理设为 false（即接管权交出），由我们自己通过 audioFocusChangeListener 进行精密接管。
                //    若此时播放器正处于播放中，则立即自主申请音频焦点，确保焦点状态与物理播放状态强力同步。
                // 2. 若关闭通知避让：我们将底层 ExoPlayer 的自动音频焦点处理设为 true，重新交回给 Media3 默认逻辑处理。
                //    同时，重置 isPausedByLossOfFocus 临时状态，并主动放弃我们自己所申请的任何系统音频焦点。
                val isAvoidanceEnabled = settings.isNotificationAvoidanceEnabled
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                player.setAudioAttributes(audioAttributes, !isAvoidanceEnabled)

                if (isAvoidanceEnabled) {
                    if (player.isPlaying) {
                        requestMyAudioFocus()
                    }
                } else {
                    if (isPausedByLossOfFocus) {
                        isPausedByLossOfFocus = false
                    }
                    abandonMyAudioFocus()
                }
            }
        }

        // 为每一次改动添加详尽的中文注释：
        // 启动 1000ms 间隔的极轻量后台轮询协程，用于监测静音跳过事件并执行 10s CD 冷却防抖的 Toast 提示
        var lastSkippedFrames = 0L
        var lastNotificationTime = 0L
        serviceScope.launch {
            while (true) {
                delay(1000)
                // 仅在播放器处于正在播放状态，且跳过静音开启时进行轮询探测
                if (player.isPlaying && player.skipSilenceEnabled) {
                    val processor = customSilenceProcessor
                    if (processor != null) {
                        val currentSkipped = processor.skippedFrames
                        if (currentSkipped > lastSkippedFrames) {
                            lastSkippedFrames = currentSkipped
                            
                            // 读取用户最新的通知开关状态
                            val settings = settingsRepository.settingsFlow.first()
                            if (settings.isSkipSilenceNotificationEnabled) {
                                val now = System.currentTimeMillis()
                                // 10秒硬性冷却防抖 CD，防止频繁打扰
                                if (now - lastNotificationTime >= 10000L) {
                                    lastNotificationTime = now
                                    // 为每一次改动添加详尽的中文注释：
                                    // 彻底废除在后台 Service 内部直接弹窗展示 Toast 的粗糙行为。
                                    // 重构为向所有已建立连接的 MediaController 广播发送自定义 Session 命令 EVENT_SKIP_SILENCE，
                                    // 使用 broadcastCustomCommand 接口，将控制权和展现形式完全交回前台 Composable 宿主层，确保单向数据流与 MVI 架构设计规范的闭环。
                                    mediaSession?.broadcastCustomCommand(
                                        androidx.media3.session.SessionCommand("EVENT_SKIP_SILENCE", android.os.Bundle.EMPTY),
                                        android.os.Bundle.EMPTY
                                    )
                                }
                            }
                        } else if (currentSkipped < lastSkippedFrames) {
                            // 处理器被重置（例如切歌或重新初始化轨道）
                            lastSkippedFrames = currentSkipped
                        }
                    }
                }
            }
        }

        rewindButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("快退10秒")
            .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_replay_10)
            .setEnabled(true)
            .build()

        forwardButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("快进30秒")
            .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_forward_30)
            .setEnabled(true)
            .build()

        bookmarkButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setDisplayName("添加书签")
            .setSessionCommand(SessionCommand(ACTION_BOOKMARK, Bundle.EMPTY))
            .setCustomIconResId(R.drawable.ic_bookmark_add)
            .setEnabled(true)
            .build()

        // 为本次媒体通知点击修复添加注释：复用桌面 widget 已使用的 overlay Intent，让通知点击进入应用时也携带 OPEN_PLAYER_OVERLAY=true。
        val playerOverlayPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION,
            MainActivity.createOpenPlayerOverlayIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            // App/UI controller 连接默认 session，必须看到真实文件级播放状态。
            .setId("ui")
            // 为本次媒体通知点击修复添加注释：UI session 也绑定同一播放页入口，避免外部媒体控制器点击会话时只回到首页。
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()

        notificationSession = MediaSession.Builder(this, notificationPlayer)
            // 通知专用 session 可以包装进度，不影响 App/UI 的真实 controller。
            .setId("notification")
            // 为本次媒体通知点击修复添加注释：系统媒体通知实际由 notificationSession 生成，因此这里必须显式设置点击通知后的播放页 overlay 入口。
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()
            
        // 顺序：快退 -> 快进 -> 书签。书签在列表最后，会显示在通知栏的最右侧槽位。
        mediaSession?.let {
            it.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
            addSession(it)
        }
        notificationSession?.let {
            it.setCustomLayout(listOf(rewindButton, forwardButton, bookmarkButton))
            addSession(it)
        }
    }

    private fun observeNotificationProgressMode() {
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                // Keep MediaSession progress mode in sync even when the full player UI is closed.
                notificationPlayer.setChapterMode(settings.isChapterProgressMode)
            }
        }
    }

    private fun updateNotificationTimeline(mediaItem: androidx.media3.common.MediaItem?) {
        val mediaId = mediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")

        serviceScope.launch(Dispatchers.IO) {
            val files = libraryRepository.getFilesForBookSync(bookId)
            val chapters = libraryRepository.getChaptersForBookSync(bookId)
            if (files.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    // A book can be one file with many chapters or many files as one book; both are mapped through global positions.
                    notificationBookId = bookId
                    notificationFiles = files
                    notificationPlayer.updateBookTimeline(bookId, files, chapters)
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun isUnavailableMediaError(error: PlaybackException): Boolean {
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
        // Parser errors mean the file was opened but malformed; keep that separate from missing/unavailable media.
        return isIoError && error.cause !is androidx.media3.common.ParserException
    }

    private fun handleUnavailableMediaItem(player: Player) {
        val mediaItem = player.currentMediaItem ?: return
        val mediaId = mediaItem.mediaId
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")
        val queueIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val skipKey = "$bookId:$queueIndex"
        if (unavailableSkipKey == skipKey) return
        unavailableSkipKey = skipKey

        serviceScope.launch {
            // 详尽的中文注释：网络音频源加载拦截安全守门狗。
            // 如果音频源属于明文 http 连接且用户尚未在设置中显式授予明文允许，直接弹出安全拦截 Toast 并暂停终止播放，保障系统高度安全。
            val currentUri = mediaItem.localConfiguration?.uri?.toString() ?: ""
            if (currentUri.startsWith("http://")) {
                val isAllowed = settingsRepository.settingsFlow.first().isCleartextTrafficAllowed
                if (!isAllowed) {
                    Toast.makeText(this@PlaybackService, "安全拦截：明文 HTTP 播放未授权。请在设置中允许。", Toast.LENGTH_LONG).show()
                    player.pause()
                    player.stop()
                    return@launch
                }
            }

            // The service owns playback failures: mark the bad file, notify once, then continue if possible.
            libraryRepository.markPlaybackFileUnavailable(bookId, queueIndex)
            Toast.makeText(this@PlaybackService, "文件不可用", Toast.LENGTH_SHORT).show()

            val next = libraryRepository.findNextAvailablePlaybackFile(bookId, queueIndex)
            if (next != null) {
                val (nextIndex, _) = next
                player.seekTo(nextIndex, 0L)
                player.prepare()
                player.play()
            } else {
                // If there is no later available file, stop instead of looping on the same broken item.
                player.pause()
                player.stop()
            }
        }
    }

    @UnstableApi
    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // 详尽的中文注释：对外暴露服务的动态包名安全审计（白名单机制）。
            // 仅对当前应用自身、系统 UI（通知栏）、Android Auto、以及系统搜索/语音助手允许绑定建立连接；非可信包名予以拦截拒绝，防范未授权的外部客户端劫持播放器服务。
            val callingPackage = controller.packageName
            val allowedPackages = listOf(
                packageName,
                "com.android.systemui",
                "com.google.android.projection.gearhead",
                "com.google.android.googlequicksearchbox"
            )
            if (callingPackage !in allowedPackages) {
                return MediaSession.ConnectionResult.reject()
            }

            val sessionCommandsBuilder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            
            // 详尽的中文注释：移除高风险强制非空解包，利用安全的 let 块和条件调用逐个装载自定义快退、快进、书签命令。
            rewindButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            forwardButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            bookmarkButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }

            val sessionCommands = sessionCommandsBuilder.build()
            val customLayout = listOf(rewindButton, forwardButton, bookmarkButton)

            val playerCommands = Player.Commands.Builder()
                .addAllCommands()
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayout)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_REWIND -> session.player.seekBack()
                ACTION_FORWARD -> session.player.seekForward()
                ACTION_BOOKMARK -> {
                    val player = session.player
                    val mediaId = player.currentMediaItem?.mediaId
                    if (mediaId != null && mediaId.contains(":")) {
                        val bookId = mediaId.substringBefore(":")

                        serviceScope.launch {
                            // 书签命令来自真实或通知 session 时都保存全书位置，避免保存章节相对位置。
                            val positionMs = (session.player as? NotificationProgressPlayer)
                                ?.currentGlobalPosition()
                                ?: currentGlobalPosition(session.player, bookId)
                            libraryRepository.addBookmark(bookId, positionMs, "Bookmark")
                            Toast.makeText(this@PlaybackService, "Bookmark saved", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session == notificationSession) {
            // 系统通知只使用通知专用 session；真实 UI session 不再生成第二套通知或虚拟进度。
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private suspend fun currentGlobalPosition(player: Player, bookId: String): Long {
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = player.currentPosition.coerceAtLeast(0L)
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: libraryRepository.getFilesForBookSync(bookId)
        // 通知以外的命令也用同一套真实文件到全书位置映射。
        return if (files.isNotEmpty()) {
            PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
        } else {
            positionInFile
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        notificationSession?.run {
            // 通知 session 只包装同一个 ExoPlayer，先释放 session，避免重复释放 player。
            release()
            notificationSession = null
        }
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }

    // 为每一次改动添加详尽的中文注释：
    // 通过 JVM 高级反射技术，绕过 Media3 管道的 private/final 限制，
    // 在用户拖动设置页 Slider 时瞬间、零延迟、无感地热更新底层 SilenceSkippingAudioProcessor 的最小时长和判定帧数。
    private fun updateSilenceProcessorDurationHot(newDurationSeconds: Float) {
        val processor = customSilenceProcessor ?: return
        try {
            val newDurationUs = (newDurationSeconds * 1_000_000L).toLong()
            
            // 1. 反射修改 minimumSilenceDurationUs 属性（核心最小时长基础字段，附带独立的 try-catch 保护）
            try {
                val durationField = processor.javaClass.getDeclaredField("minimumSilenceDurationUs")
                durationField.isAccessible = true
                durationField.setLong(processor, newDurationUs)
                android.util.Log.d("PlaybackService", "Successfully hot-updated minimumSilenceDurationUs to $newDurationUs Us")
            } catch (e: NoSuchFieldException) {
                android.util.Log.w("PlaybackService", "Field minimumSilenceDurationUs not found in SilenceSkippingAudioProcessor")
            } catch (e: Exception) {
                android.util.Log.e("PlaybackService", "Failed to write minimumSilenceDurationUs: ${e.message}")
            }
            
            // 2. 反射读取 BaseAudioProcessor（父类）中的 inputAudioFormat，以便获取当前真实工作采样率
            val superclass = processor.javaClass.superclass
            var audioFormat: Any? = null
            if (superclass != null) {
                try {
                    val formatField = superclass.getDeclaredField("inputAudioFormat")
                    formatField.isAccessible = true
                    audioFormat = formatField.get(processor)
                } catch (e: NoSuchFieldException) {
                    android.util.Log.w("PlaybackService", "Field inputAudioFormat not found in BaseAudioProcessor")
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Failed to read inputAudioFormat: ${e.message}")
                }
            }
            
            if (audioFormat != null) {
                // 3. 读取当前工作音频格式的采样率（sampleRate）与单帧字节数（bytesPerFrame）
                var sampleRate = 0
                var bytesPerFrame = 4 // 默认为双声道 16-bit PCM 字节大小 (2声道 * 2字节)
                
                try {
                    val sampleRateField = audioFormat.javaClass.getDeclaredField("sampleRate")
                    sampleRateField.isAccessible = true
                    sampleRate = sampleRateField.getInt(audioFormat)
                } catch (e: NoSuchFieldException) {
                    android.util.Log.w("PlaybackService", "Field sampleRate not found in AudioFormat")
                } catch (e: Exception) {
                    android.util.Log.e("PlaybackService", "Failed to read sampleRate: ${e.message}")
                }
                
                try {
                    val bytesPerFrameField = audioFormat.javaClass.getDeclaredField("bytesPerFrame")
                    bytesPerFrameField.isAccessible = true
                    bytesPerFrame = bytesPerFrameField.getInt(audioFormat)
                } catch (e: Exception) {
                    // 字段不存在时采用默认值 4，不抛出异常
                }
                
                if (sampleRate > 0) {
                    // 4. 计算判定时长对应的新判定帧数
                    val newSilenceFrames = ((sampleRate.toLong() * newDurationUs) / 1_000_000L).toInt()
                    
                    // 5. 尝试写入 minimumSilenceFrames（若在此 Media3 版本中存在）
                    try {
                        val framesField = processor.javaClass.getDeclaredField("minimumSilenceFrames")
                        framesField.isAccessible = true
                        framesField.setInt(processor, newSilenceFrames)
                        android.util.Log.d("PlaybackService", "Successfully hot-updated minimumSilenceFrames to $newSilenceFrames")
                    } catch (e: NoSuchFieldException) {
                        // 静默忽略：当前 Media3 版本不包含 minimumSilenceFrames 字段
                    } catch (e: Exception) {
                        android.util.Log.e("PlaybackService", "Failed to write minimumSilenceFrames: ${e.message}")
                    }
                    
                    // 6. 尝试重新计算并写入 maybeSilenceBufferSize 字段值（当前 Media3 版本内存判定容量的核心）
                    if (bytesPerFrame > 0) {
                        val newBufferSize = newSilenceFrames * bytesPerFrame
                        try {
                            val bufferSizeField = processor.javaClass.getDeclaredField("maybeSilenceBufferSize")
                            bufferSizeField.isAccessible = true
                            bufferSizeField.setInt(processor, newBufferSize)
                            android.util.Log.d("PlaybackService", "Successfully hot-updated maybeSilenceBufferSize to $newBufferSize")
                        } catch (e: NoSuchFieldException) {
                            // 静默忽略：当前 Media3 版本不包含 maybeSilenceBufferSize 字段
                        } catch (e: Exception) {
                            android.util.Log.e("PlaybackService", "Failed to write maybeSilenceBufferSize: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 用最外层 try-catch 兜底强力保护，确保即便反射发生灾难性未知异常，也绝不会让 App 崩溃，确保了极致的系统稳定性
            android.util.Log.e("PlaybackService", "Failed to hot-update silence processor duration: ${e.message}", e)
        }
    }

    // 为每一次改动添加详尽的中文注释：
    // 通过极致鲁棒的反射查找逻辑，在 DefaultAudioSink 创建出来后，对其自身所有字段及包含的子对象（例如处理器链、处理器数组）进行广度优先式的类型扫描，
    // 自动寻找并提取其中类型为 SilenceSkippingAudioProcessor 且真正工作的内部实例，完美赋给 customSilenceProcessor。
    // 这消除了通过显式 API 注入带来的 Unresolved reference 编译兼容风险，同时完整保留了官方 Skip Silence 的完美逻辑控制与实时 Toast 监听。
    private fun findSilenceProcessorFromSink(sink: androidx.media3.exoplayer.audio.AudioSink?) {
        if (sink == null) return
        try {
            // 1. 尝试直接在第一层声明的字段中寻找
            val fields = sink.javaClass.declaredFields
            for (field in fields) {
                if (field.type == androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor::class.java) {
                    field.isAccessible = true
                    val processor = field.get(sink) as? androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
                    if (processor != null) {
                        customSilenceProcessor = processor
                        android.util.Log.d("PlaybackService", "Successfully extracted internal SilenceSkippingAudioProcessor from sink directly: ${field.name}")
                        return
                    }
                }
            }
            
            // 2. 如果第一层未寻得，遍历类中持有的非基础类型复杂对象（如 DefaultAudioProcessorChain 等）
            for (field in fields) {
                if (field.type.isPrimitive || field.type.name.startsWith("java.") || field.type.name.startsWith("android.")) {
                    continue
                }
                field.isAccessible = true
                val obj = field.get(sink) ?: continue
                
                // 若该字段为处理器数组，遍历查找
                if (obj is Array<*>) {
                    for (element in obj) {
                        if (element is androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor) {
                            customSilenceProcessor = element
                            android.util.Log.d("PlaybackService", "Successfully extracted internal SilenceSkippingAudioProcessor from array field: ${field.name}")
                            return
                        }
                    }
                }
                
                // 深度遍历子对象中的所有声明字段
                val subFields = obj.javaClass.declaredFields
                for (subField in subFields) {
                    if (subField.type == androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor::class.java) {
                        subField.isAccessible = true
                        val processor = subField.get(obj) as? androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
                        if (processor != null) {
                            customSilenceProcessor = processor
                            android.util.Log.d("PlaybackService", "Successfully extracted internal SilenceSkippingAudioProcessor from sub-object ${field.name} -> ${subField.name}")
                            return
                        }
                    }
                }
            }
            android.util.Log.w("PlaybackService", "No internal SilenceSkippingAudioProcessor found in AudioSink via reflection scan.")
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "Failed to extract internal SilenceSkippingAudioProcessor: ${e.message}", e)
        }
    }

    // 为每一次改动添加详尽的中文注释：
    // 使用现代 Android 8.0+ 的 AudioFocusRequest 物理请求机制申请全局音频焦点，彻底废除对老旧过时 API 的冗余兼容，保持代码精简健壮。
    private fun requestMyAudioFocus(): Boolean {
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return false
        val request = audioFocusRequest ?: android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build().also { audioFocusRequest = it }
        return audioManager.requestAudioFocus(request) == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // 为每一次改动添加详尽的中文注释：
    // 使用现代 Android 8.0+ 的 abandonAudioFocusRequest 物理请求机制放弃并释放已持有的音频焦点，剔除冗余的老旧 API 兼容路径。
    private fun abandonMyAudioFocus() {
        val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }
    }
}
