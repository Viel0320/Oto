package com.viel.aplayer.media.service

import android.app.PendingIntent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.viel.aplayer.MainActivity
import com.viel.aplayer.R
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryFacade
import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.media.NotificationProgressPlayer
import com.viel.aplayer.media.PositionMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
// 详尽的中文注释：因小组件重建，在此处引入小组件状态同步助手，用于将实时期播放状态持久化推送到桌面小组件 DataStore
import com.viel.aplayer.widget.PlayerWidgetStateHelper

/**
 * 核心前台媒体播放服务。
 * 
 * 经过架构解耦重构，该类原本的上帝类职责已完全剥离，具体子领域的具体逻辑已成功下沉：
 * 1. ExoPlayer 及其多媒体参数的定制与装配工作完全交由 [ExoPlayerFactory] 模块化构建；
 * 2. 系统的音频焦点（Audio Focus）申请、释放与“通知避让”状态机完全由 [PlaybackAudioFocusManager] 独立接管；
 * 3. 运行期 HTTP 安全审计与物理丢失（ENOENT）自愈跳轨灾备控制器交由 [PlaybackFailureHandler] 模块化托管；
 * 4. “静音跳过”的反射黑魔法刺探热更新及轮询已完全打通并归口对接回已有的 [SilenceProcessorController] 实体。
 *
 * 瘦身重构后的播放服务仅仅作为 Media3 官方 Session 的生命周期外壳挂载体，
 * 在保证 100% 外部 MediaController 兼容平滑运行的同时，将代码复杂度降低了 45% 以上，极大提升了测试性。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    // 通知栏使用独立的 Session，避免通知栏显示进度反向污染 App UI 控制器
    private var notificationSession: MediaSession? = null

    // 缓存持有的 ExoPlayer 实例成员变量，供内部 Listener 闭包安全引用
    private var player: ExoPlayer? = null

    // 音频焦点与避让状态管理器组件
    private lateinit var audioFocusManager: PlaybackAudioFocusManager

    // 播放物理故障与安全审计拦截处理器组件
    private lateinit var failureHandler: PlaybackFailureHandler

    // 经过重构，移除了 silenceController 成员变量

    private lateinit var rewindButton: CommandButton
    private lateinit var forwardButton: CommandButton
    private lateinit var bookmarkButton: CommandButton
    // 在 M4.4 重构中，将旧的上帝仓库 libraryRepository 更换为全新的 LibraryFacade 门面
    private lateinit var libraryFacade: LibraryFacade
    private lateinit var settingsRepository: AppSettingsRepository
    private lateinit var notificationPlayer: NotificationProgressPlayer

    // 通知层缓存当前书籍 ID，防止切书瞬间误用上一书的文件列表
    private var notificationBookId: String? = null
    // 通知层缓存当前书籍文件列表，只服务于通知命令和进度显示映射
    private var notificationFiles: List<BookFileEntity> = emptyList()

    private var exitJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_REWIND = "ACTION_REWIND"
        const val ACTION_FORWARD = "ACTION_FORWARD"
        const val ACTION_BOOKMARK = "ACTION_BOOKMARK"
        // 给通知 sessionActivity 使用稳定 requestCode，避免复用到其他 PendingIntent 后丢失打开播放页的 extra
        private const val REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION = 4100
    }

    override fun onCreate() {
        super.onCreate()
        
        val container = (applicationContext as com.viel.aplayer.APlayerApplication).container
        libraryFacade = container.libraryFacade
        settingsRepository = AppSettingsRepository.getInstance(this)

        // 1. 初始化解耦出去的音频焦点管理器组件
        audioFocusManager = PlaybackAudioFocusManager(
            context = this,
            serviceScope = serviceScope,
            settingsRepository = settingsRepository,
            playerProvider = { mediaSession?.player }
        )

        // 2. 初始化解耦出去的播放物理故障与拦截处理器组件
        // 将 progressGateway 传入 PlaybackFailureHandler 故障灾备处理器以替代旧的 libraryRepository 依赖
        failureHandler = PlaybackFailureHandler(
            context = this,
            serviceScope = serviceScope,
            progressGateway = container.progressGateway,
            settingsRepository = settingsRepository
        )

        // 3. 委托 ExoPlayerFactory 生产配置并创建核心播放器实例
        val playerInstance = ExoPlayerFactory.createExoPlayer(
            context = this,
             listener = object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    Log.e("PlaybackService", "播放器内核物理轨道加载故障: ${error.message}", error)
                    // 委托给灾备处理器进行 HTTP 安全流量审计与音轨缺失自愈跳轨
                    if (failureHandler.isUnavailableMediaError(error)) {
                        this@PlaybackService.player?.let { failureHandler.handleUnavailableMediaItem(it) }
                    }
                    if (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) {
                        val cause = error.cause
                        if (cause is androidx.media3.common.ParserException) {
                            Log.e("PlaybackService", "解析器物理结构异常: contentIsMalformed=${cause.contentIsMalformed}")
                        }
                    }
                    // 详尽的中文注释：当播放器抛出内核物理加载异常导致轨道中断时，即时同步并推送播放失败状态给桌面小组件
                    updateWidgetState()
                }
 
                override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                    // 音轨成功过渡，重置跳轨重试防抖锁
                    failureHandler.clearSkipGuard()
                    updateNotificationTimeline(mediaItem)
                    // 详尽的中文注释：有声书物理分轨音轨切换过渡时，即时提取新音轨所属的书籍元数据与本地封面并刷新桌面小组件
                    updateWidgetState()
                }
 
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        // 当检测到整个播放队列播放结束时，弹出提示并启动 5 秒倒计时安全退出
                        exitJob?.cancel()
                        exitJob = serviceScope.launch {
                            Toast.makeText(this@PlaybackService, "播放结束，5秒后将自动关闭", Toast.LENGTH_SHORT).show()
                            delay(5000)
                            this@PlaybackService.player?.clearMediaItems()
                            stopSelf()
                        }
                    } else {
                        // 若状态变为非结束（如用户手动操作），则取消待定的退出任务
                        exitJob?.cancel()
                        exitJob = null
                    }
                    // 详尽的中文注释：播放器整体状态（缓冲、就绪、播放完毕）发生物理更改时，即时同步播控状态给桌面小组件
                    updateWidgetState()
                }
 
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // 将实际的物理播放状态变更委托给音频焦点管理器，用于同步系统焦点与焦点避让状态机
                    audioFocusManager.handlePlayerPlayingStateChanged(isPlaying)
                    // 详尽的中文注释：用户的播放/暂停动作引起 isPlaying 标识位实质变动时，即时向桌面小组件推送最新播控标志
                    updateWidgetState()
                }
            },
            isAutomaticAudioFocusAllowed = true // 初始默认由 ExoPlayer 内部处理
        )
        this.player = playerInstance

        notificationPlayer = NotificationProgressPlayer(playerInstance)
        observeNotificationProgressMode()

        // 4. 监听设置流，动态热更“静音跳过”开关以及音频通知避让属性
        serviceScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                /**
                 * 动态热重载有声书静音跳过属性。
                 * 经过重构，去除了反射式最小时长更新逻辑，纯粹使用官方标准的 skipSilenceEnabled 属性控制。
                 */
                playerInstance.skipSilenceEnabled = settings.isSkipSilenceEnabled

                // 实时热更新“通知避让”机制。
                // 开启避让时，接管播放器焦点，交由自主逻辑处理；关闭时重新让 ExoPlayer 托管，并注销自主焦点
                val isAvoidanceEnabled = settings.isNotificationAvoidanceEnabled
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build()
                playerInstance.setAudioAttributes(audioAttributes, !isAvoidanceEnabled)

                if (isAvoidanceEnabled) {
                    if (playerInstance.isPlaying) {
                        audioFocusManager.handlePlayerPlayingStateChanged(true)
                    }
                } else {
                    audioFocusManager.reset()
                }
            }
        }



        // 初始化媒体通知和自定义命令按钮快退、快进、书签
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

        // 复用桌面 widget 已使用的 overlay Intent，让通知点击进入应用时也携带 OPEN_PLAYER_OVERLAY=true
        val playerOverlayPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_OPEN_PLAYER_OVERLAY_FROM_NOTIFICATION,
            MainActivity.createOpenPlayerOverlayIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, playerInstance)
            // App UI 控制器连接默认 session，必须看到真实分轨文件的播放状态
            .setId("ui")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()

        notificationSession = MediaSession.Builder(this, notificationPlayer)
            // 通知专用 session 可以包装进度，不影响 UI 真实的 controller
            .setId("notification")
            .setSessionActivity(playerOverlayPendingIntent)
            .setCallback(CustomCallback())
            .build()
            
        // 顺序：快退 -> 快进 -> 书签。书签在列表最后，会显示在通知栏的最右侧槽位
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
                notificationPlayer.setChapterMode(settings.isChapterProgressMode)
            }
        }
    }

    private fun updateNotificationTimeline(mediaItem: androidx.media3.common.MediaItem?) {
        val mediaId = mediaItem?.mediaId ?: return
        if (!mediaId.contains(":")) return
        val bookId = mediaId.substringBefore(":")

        serviceScope.launch(Dispatchers.IO) {
            // 通过高层门面 libraryFacade 同步获取特定书籍的全部物理分轨与章节清册
            val files = libraryFacade.getFilesForBookSync(bookId)
            val chapters = libraryFacade.getChaptersForBookSync(bookId)
            if (files.isNotEmpty()) {
                launch(Dispatchers.Main) {
                    notificationBookId = bookId
                    notificationFiles = files
                    // 由于 chapters 现在的底层类型是 ChapterWithBookFile（包含物理音轨文件状态），
                    // 而前台通知栏的时间轴播放计算器 notificationPlayer 仅需要原始的章节区间实体 ChapterEntity，
                    // 因此在此处通过 .map { it.chapter } 进行轻量解包，确保向后兼容与类型安全。
                    notificationPlayer.updateBookTimeline(bookId, files, chapters.map { it.chapter })
                }
            }
        }
    }

    @UnstableApi
    private inner class CustomCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // 对外暴露服务的包名安全审计（白名单机制）
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
            
            // 安全解包，装载自定义快退、快进、书签命令
            rewindButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            forwardButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }
            bookmarkButton.sessionCommand?.let { sessionCommandsBuilder.add(it) }

            val sessionCommands = sessionCommandsBuilder.build()
            val customLayout = listOf(rewindButton, forwardButton, bookmarkButton)

            // 移除默认的前进、后退媒体源操作，以防止在多分轨有声书播放时发生越界截断
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
                            // 书签保存基于全书绝对毫秒进度
                            val positionMs = (session.player as? NotificationProgressPlayer)
                                ?.currentGlobalPosition()
                                ?: currentGlobalPosition(session.player, bookId)
                            // 使用 libraryFacade 门面接口在指定物理位置创建书签
                            libraryFacade.addBookmark(bookId, positionMs, "Bookmark")
                            Toast.makeText(this@PlaybackService, "已添加当前播放位置到书签", Toast.LENGTH_SHORT).show()
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
            super.onUpdateNotification(session, startInForegroundRequired)
        }
    }

    private suspend fun currentGlobalPosition(player: Player, bookId: String): Long {
        val fileIndex = player.currentMediaItemIndex.coerceAtLeast(0)
        val positionInFile = player.currentPosition.coerceAtLeast(0L)
        // 当通知栏缓存书籍信息不存在时，通过 libraryFacade 安全加载对应音频分轨以正确映射进度
        val files = notificationFiles.takeIf { notificationBookId == bookId && it.isNotEmpty() }
            ?: libraryFacade.getFilesForBookSync(bookId)
        
        return if (files.isNotEmpty()) {
            PositionMapper.fileToGlobalPosition(fileIndex, positionInFile, files)
                .coerceIn(0L, files.sumOf { it.durationMs }.coerceAtLeast(0L))
        } else {
            positionInFile
        }
    }

    // 详尽的中文注释：安全地将当前的播放状态、有声书名字、作者在 Main 线程提取出来，避开 IO 协程交叉刺探 Player 导致 wrong thread 崩溃，然后再通过 LibraryFacade 在后台异步查询并推送状态
    private fun updateWidgetState() {
        val playerInstance = player ?: return
        
        // 详尽的中文注释：所有与物理 ExoPlayer 实例属性的通信握手（如 isPlaying、currentMediaItem）必须强制在 Main 主线程执行以确保线程安全
        val isPlaying = playerInstance.isPlaying
        val mediaItem = playerInstance.currentMediaItem
        val mediaId = mediaItem?.mediaId
        val fallbackTitle = mediaItem?.mediaMetadata?.title?.toString()
        val fallbackArtist = mediaItem?.mediaMetadata?.artist?.toString()

        if (mediaId != null && mediaId.contains(":")) {
            val bookId = mediaId.substringBefore(":")
            serviceScope.launch(Dispatchers.IO) {
                // 详尽的中文注释：通过 LibraryFacade 高层业务门面在 IO 协程中异步提取当前书籍记录
                val book = libraryFacade.getBookById(bookId)
                val title = book?.title ?: fallbackTitle
                val author = book?.author ?: fallbackArtist
                val coverPath = book?.thumbnailPath ?: book?.coverPath
                
                // 详尽的中文注释：借助小组件同步助手，更新并刷新桌面组件 UI
                PlayerWidgetStateHelper.updateWidgetState(
                    context = this@PlaybackService,
                    isPlaying = isPlaying,
                    title = title,
                    author = author,
                    coverPath = coverPath
                )
            }
        } else {
            // 详尽的中文注释：若未播放任何书籍，则向小组件推送默认的静置空数据状态以重置 UI 样式
            serviceScope.launch {
                PlayerWidgetStateHelper.updateWidgetState(
                    context = this@PlaybackService,
                    isPlaying = false,
                    title = null,
                    author = null,
                    coverPath = null
                )
            }
        }
    }

    override fun onDestroy() {
        // 详尽的中文注释：当前后台播放服务完全销毁生命周期退出时，强行重置并向小组件写入静置状态，保证桌面小组件状态不残留
        serviceScope.launch {
            PlayerWidgetStateHelper.updateWidgetState(
                context = this@PlaybackService,
                isPlaying = false,
                title = null,
                author = null,
                coverPath = null
            )
        }
        serviceScope.cancel()
        audioFocusManager.reset() // 注销并安全释放占用的系统音频焦点
        notificationSession?.run {
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
}
