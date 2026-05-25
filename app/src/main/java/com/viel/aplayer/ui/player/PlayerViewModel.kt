package com.viel.aplayer.ui.player

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.LibraryRepository
import com.viel.aplayer.data.entity.BookmarkEntity
import kotlinx.coroutines.flow.update
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.media.parser.ImageProcessor
import com.viel.aplayer.ui.bookmarks.BookmarkManager
import com.viel.aplayer.ui.settings.PlayerSettingsManager
import com.viel.aplayer.ui.settings.PlayerSettingsState

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerViewModel : ViewModel() {
    companion object {
        private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        private val SLEEP_TIMER_OPTIONS = listOf(0, -1, -2, 15, 30, 60)
    }

    private var playbackManager: PlaybackManager? = null
    private var libraryRepository: LibraryRepository? = null
    private var settingsRepository: AppSettingsRepository? = null
    private var getRelatedBooksUseCase: GetRelatedBooksUseCase? = null
    private var audioManager: AudioManager? = null
    
    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    // 为每一次改动添加详尽的中文注释：
    // 新增播放器链路级一次性 UI 反馈共享流 uiEvents（采用 SharedFlow 保证一次性事件的可靠消费）。
    // 专门用来对外广播由底层 PlaybackManager 捕获并转发的 UI 反馈事件（如静音跳过提示等），
    // 遵循单向数据流与 MVI 架构设计，杜绝在 ViewModel 或后台服务中直接持有 UI 组件。
    private val _uiEvents = kotlinx.coroutines.flow.MutableSharedFlow<com.viel.aplayer.ui.common.UiEvent>(extraBufferCapacity = 1)
    val uiEvents = _uiEvents.asSharedFlow()

    private val _currentSubtitles = MutableStateFlow<List<com.viel.aplayer.ui.player.components.SubtitleLine>>(emptyList())

    // 详尽的中文注释：用于控制内置歌词与外置字幕异步加载生命周期的 Job。每次切歌或销毁时进行物理取消，防止多协程并发竞争。
    private var subtitleLoadJob: kotlinx.coroutines.Job? = null

    // 详尽的中文注释：内置歌词检索激活状态闸门。若为 true，说明当前处于 500ms 等待内置歌词的窗口期；
    // 一旦超时回退或成功匹配到内置歌词，立即物理置为 false，从而无条件过滤并丢弃任何底层延迟到达 of onMetadata 内置字幕回调。
    private var isEmbeddedSearchActive = false

    private var bookmarkManager: BookmarkManager? = null
    private var playbackDelegate: MediaPlaybackDelegate? = null
    // 为每一次改动添加详尽的中文注释：新增持有的 appContext 对象，在 initialize 时进行安全赋值，仅用于构造 lambda 桥接以规避内存泄露风险。
    private var appContext: Context? = null
    private val settingsManager: PlayerSettingsManager = PlayerSettingsManager(
        scope = viewModelScope,
        playbackManager = { playbackManager },
        audioManager = { audioManager },
        contextProvider = { appContext }
    )

    // =====================================================================
    // 详尽中文注释：M-16 修复 — 书签对话框状态上提到 ViewModel
    // 将删除/编辑对话框的业务状态从叶子 Composable 中彻底移除，
    // 改由 ViewModel 持有 StateFlow，防止配置变更（旋转/深色模式切换）时
    // 用户正在编辑的内容丢失，同时使状态可被单元测试覆盖。
    // =====================================================================

    /** 书签对话框的复合状态，涵盖待删除/待编辑条目以及编辑中的标题文本 */
    data class BookmarkDialogsState(
        val toDelete: BookmarkEntity? = null,
        val toEdit: BookmarkEntity? = null,
        val editTitle: String = ""
    )

    private val _bookmarkDialogs = MutableStateFlow(BookmarkDialogsState())
    /** 外部 Composable 通过此 StateFlow 观察对话框状态 */
    val bookmarkDialogs: StateFlow<BookmarkDialogsState> = _bookmarkDialogs.asStateFlow()

    /** 触发删除确认对话框 */
    fun requestDeleteBookmark(b: BookmarkEntity) {
        _bookmarkDialogs.update { it.copy(toDelete = b) }
    }

    /** 触发编辑对话框，同步回填当前标题 */
    fun requestEditBookmark(b: BookmarkEntity) {
        _bookmarkDialogs.update { it.copy(toEdit = b, editTitle = b.title) }
    }

    /** 用户实时修改编辑框内容时更新标题 */
    fun onBookmarkEditTitleChange(t: String) {
        _bookmarkDialogs.update { it.copy(editTitle = t) }
    }

    /** 关闭所有书签对话框并清空状态 */
    fun dismissBookmarkDialogs() {
        _bookmarkDialogs.value = BookmarkDialogsState()
    }

    private var _lastDominantColor = ImageProcessor.DEFAULT_BACKGROUND_ARGB

    val settingsState: StateFlow<PlayerSettingsState> = settingsManager.settingsState
    val sleepTimerMillis: StateFlow<Long> = settingsManager.sleepTimerMillis

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val metadataState: StateFlow<BookMetadataState> = _currentBookId
        .flatMapLatest { id ->
            val repo = libraryRepository ?: return@flatMapLatest flowOf(BookMetadataState())
            if (id == null) return@flatMapLatest flowOf(BookMetadataState())

            combine(
                repo.observeBookById(id),
                repo.getChapters(id),
                repo.getBookmarks(id),
                _currentSubtitles
            ) { entity: com.viel.aplayer.data.entity.BookEntity?, chapters: List<com.viel.aplayer.data.entity.ChapterEntity>, bookmarks: List<com.viel.aplayer.data.entity.BookmarkEntity>, subtitles: List<com.viel.aplayer.ui.player.components.SubtitleLine> ->
                BookMetadataState(
                    id = id,
                    title = entity?.title ?: "",
                    author = entity?.author ?: "",
                    narrator = entity?.narrator ?: "",
                    coverPath = entity?.coverPath,
                    thumbnailPath = entity?.thumbnailPath,
                    // 详尽的中文注释：将数据库中书籍实体的 lastScannedAt 映射作为 coverLastUpdated，从而在封面缓存重建完成后，促使数据流重发新状态包，迫使页面进行画面重绘
                    coverLastUpdated = entity?.lastScannedAt ?: 0L,
                    chapters = chapters,
                    bookmarks = bookmarks,
                    subtitles = subtitles,
                    backgroundColorArgb = entity?.backgroundColorArgb ?: _lastDominantColor
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookMetadataState())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val _relatedData = metadataState
        .flatMapLatest { meta ->
            val id = meta.id
            if (id.isBlank() || id == "Unknown") {
                return@flatMapLatest flowOf(RelatedData(emptyList(), emptyList(), emptyList(), emptyList()))
            }
            
            val author = meta.author
            val narrator = meta.narrator
            // 为每一次改动添加详尽的中文注释：
            // 将推荐数据源绑定到 metadataState 响应式流上。
            // 这样，只要元数据从 Room 成功加载，或者用户在信息修改器中对属性进行了保存，
            // 都会触发 flatMapLatest 自动以正确的实机属性调用 usecase，彻底修复了原先 _currentBookId 刚更新时
            // 静态读取 metadataState.value 快照所导致的全空 Bug！
            getRelatedBooksUseCase?.invoke(id, author, narrator) 
                ?: flowOf(RelatedData(emptyList(), emptyList(), emptyList(), emptyList()))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), RelatedData(emptyList(), emptyList(), emptyList(), emptyList()))

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val playbackState: StateFlow<PlaybackState> = _currentBookId
        .flatMapLatest { _ ->
            playbackManager?.let { manager ->
                combine(
                    manager.isPlaying,
                    manager.playbackState,
                    manager.currentPosition,
                    manager.duration,
                    manager.playbackSpeed
                ) { isPlaying, _, pos, dur, speed ->
                    PlaybackState(
                        isPlaying = isPlaying,
                        currentPosition = pos,
                        duration = dur,
                        playbackSpeed = speed,
                        playWhenReady = isPlaying
                    )
                }
            } ?: flowOf(PlaybackState())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackState())

    // 详尽的中文注释：定义精细化局部的进度状态大实体，专门承载 elapsedMs、durationMs 以在局部微观范围响应重组
    data class PlaybackProgressViewState(
        val elapsedMs: Long = 0L,
        val durationMs: Long = 0L,
        val isChapterProgressMode: Boolean = false
    )

    // 详尽的中文注释：新增细粒度进度高频 StateFlow 通道。
    // 使用 distinctUntilChanged() 对各个变量进行锁死拦截，只在高频通道中发射这三个变量，确保不污染其他全局组件。
    val playbackProgressState: StateFlow<PlaybackProgressViewState> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        playbackState.map { it.duration }.distinctUntilChanged(),
        settingsState.map { it.isChapterProgressMode }.distinctUntilChanged()
    ) { pos, dur, mode ->
        PlaybackProgressViewState(pos, dur, mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackProgressViewState())

    // 详尽的中文注释：新增极其低频的章节边界流通道。
    // 仅在进度跨越章节临界点或者书籍章节列表改变时才射出新章节，实现章节标题组件 ChapterDisplay 的绝对低频重组。
    val currentChapterState: StateFlow<com.viel.aplayer.data.entity.ChapterEntity?> = combine(
        playbackState.map { it.currentPosition }.distinctUntilChanged(),
        metadataState.map { it.chapters }.distinctUntilChanged()
    ) { pos, chapters ->
        com.viel.aplayer.media.ChapterTimeline.currentChapter(chapters, pos)
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val playbackControlState: StateFlow<PlaybackControlState> = playbackState
        .map { 
            PlaybackControlState(it.isPlaying, it.playbackSpeed, it.isSpeedManualMode)
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackControlState(false, 1.0f, false))

    // 详尽的中文注释：当前播放进度百分比 (0-100)，从真实的 playbackState 高频流派生。
    // 供 DetailOverlay 在展示当前正在播放的书籍详情时，实时同步进度百分比，
    // 避免在 Composable 中内联数学运算，遵循 ViewModel 单向数据流原则。
    val currentPlaybackProgressPercent: StateFlow<Int> = playbackState
        .map { state ->
            if (state.duration > 0) {
                kotlin.math.ceil(
                    state.currentPosition.toDouble() / state.duration.toDouble() * 100
                ).toInt().coerceIn(0, 100)
            } else 0
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // 详尽的中文注释：迷你播放器显示进度 (0.0f - 1.0f)。
    // 根据用户是否开启章节进度模式 (isChapterProgressMode)，自动选择：
    // - 章节模式：计算当前章节内的局部进度比例（调用 ChapterTimeline）
    // - 全局模式：使用 currentPosition / duration 的全局进度
    // 此前该计算散落在 PlayerOverlay 的 MiniPlayerContent Composable 中，
    // 现集中到 ViewModel 的 Flow 管道，UI 层只需订阅并渲染。
    val miniPlayerProgress: StateFlow<Float> = combine(
        playbackState,
        metadataState.map { it.chapters }.distinctUntilChanged(),
        settingsState.map { it.isChapterProgressMode }.distinctUntilChanged()
    ) { state, chapters, isChapterMode ->
        if (isChapterMode && chapters.isNotEmpty()) {
            val currentChapter = ChapterTimeline.currentChapter(chapters, state.currentPosition)
            val posInChapter = ChapterTimeline.positionInChapter(
                chapters, currentChapter, state.currentPosition, state.duration
            )
            val chapterDuration = ChapterTimeline.duration(
                chapters, currentChapter, state.duration
            )
            if (chapterDuration > 0) posInChapter.toFloat() / chapterDuration.toFloat() else 0f
        } else {
            state.progress
        }
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0f)


    // 详尽的中文注释：重构后的低频全局 uiState 流。
    // 核心重构设计在于对 playback 字段进行彻底的进度“脱水”（将 currentPosition 和 duration 强制清零），
    // 这样全局 uiState 便能对每 500 毫秒一次的高频进度时间完全免疫，只有在播放控制改变、切换书籍或设置更新时才极低频重组。
    val uiState: StateFlow<PlayerUiState> = combine(
        metadataState,
        playbackControlState,
        settingsManager.settingsState,
        _relatedData
    ) { metadata, control, settings, related ->
        PlayerUiState(
            metadata = metadata,
            playback = PlaybackState(
                isPlaying = control.isPlaying,
                currentPosition = 0L, // 进行彻底的“进度脱水”，切断高频重组
                duration = 0L,        // 进度脱水
                playbackSpeed = control.playbackSpeed,
                playWhenReady = control.isPlaying
            ),
            settings = settings,
            relatedAuthorSections = related.authorSections,
            relatedNarratorSections = related.narratorSections,
            recentlyAddedBooks = related.recentlyAdded,
            // 为每一次改动添加详尽的中文注释：映射注入启发式推荐数据到全局 uiState 流，实现端到端数据传输
            heuristicRecommendedBooks = related.heuristicRecommended
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    data class PlaybackControlState(
        val isPlaying: Boolean,
        val playbackSpeed: Float,
        val isSpeedManualMode: Boolean
    )

    private var lastSeekPosition = 0L
    private var undoJob: kotlinx.coroutines.Job? = null
    // Prevents repeated app-level initialize calls from reloading the compact player over the current session.
    private var hasRestoredLastPlayedBook = false

    fun initialize(context: Context) {
        if (playbackManager != null) return
        val appContext = context.applicationContext
        this.appContext = appContext
        val container = (appContext as APlayerApplication).container
        // 为每一次改动添加详尽的中文注释：使用局部作用域变量分配，彻底物理规避连续多次 !! 解包引发的 NPE 风险 (H-11)
        val repo = container.libraryRepository
        libraryRepository = repo
        settingsRepository = container.settingsRepository
        
        getRelatedBooksUseCase = GetRelatedBooksUseCase(repo)
        audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        playbackManager = PlaybackManager.getInstance(appContext)

        bookmarkManager = BookmarkManager(repo, viewModelScope)
        playbackDelegate = MediaPlaybackDelegate(
            playbackManager = { playbackManager },
            repository = repo,
            scope = viewModelScope
        )

        observePlaybackManager()
        observeSettings()
        restoreLastPlayedBookToCompactPlayer()
    }

    private fun restoreLastPlayedBookToCompactPlayer() {
        if (hasRestoredLastPlayedBook) return
        hasRestoredLastPlayedBook = true

        viewModelScope.launch {
            // Cold start only prepares the latest saved book/progress for the compact player; it must not autoplay.
            val lastProgress = libraryRepository?.getLastPlayedProgressSync() ?: return@launch
            if (_currentBookId.value == null) {
                loadBook(lastProgress.bookId, playWhenReady = false)
                // 为本次桌面 widget 改动添加注释：如果外部入口已经请求播放页 overlay，冷启动恢复最近播放书籍时不再强制收回到迷你播放器。
                if (!settingsState.value.isFullPlayerVisible) {
                    settingsManager.setFullPlayerVisible(false)
                }
                settingsManager.setMiniPlayerHidden(false)
            }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository?.settingsFlow?.collect { settings ->
                if (settings.isChapterProgressMode != settingsState.value.isChapterProgressMode) {
                    settingsManager.setChapterProgressMode(settings.isChapterProgressMode)
                }
                // 为每一次改动添加详尽的中文注释：实时同步持久化配置中的睡眠渐隐开关状态至 PlayerSettingsManager 内部。
                settingsManager.isSleepFadeOutEnabled = settings.isSleepFadeOutEnabled
                // 为每一次改动添加详尽的中文注释：实时同步持久化配置中的摇晃重置开关状态至 PlayerSettingsManager 内部。
                settingsManager.isShakeToResetEnabled = settings.isShakeToResetEnabled
                // 为每一次改动添加详尽的中文注释：实时同步持久化配置中的睡眠模式状态至 PlayerSettingsManager 内部，实现三态计时的底层业务流转。
                settingsManager.sleepMode = settings.sleepMode
            }
        }
    }

    private fun observePlaybackManager() {
        val manager = playbackManager ?: return

        viewModelScope.launch {
            // 为每一次改动添加详尽的中文注释：
            // 监听并订阅底层单例 PlaybackManager 广播的全局一次性 UI 事件共享流 uiEvents。
            // 当接收到事件（如静音跳过 EVENT_SKIP_SILENCE 触发的 UiEvent.ShowToast）时，
            // 立即将其向下层转发至当前 PlayerViewModel 持有的 uiEvents 流中，
            // 从而被正在活动的 Composable 宿主（APlayerApp）所收集并渲染展示，完成完整的事件响应环。
            manager.uiEvents.collect { event ->
                _uiEvents.emit(event)
            }
        }

        viewModelScope.launch {
            manager.currentMediaItem.collectLatest { mediaItem ->
                if (mediaItem != null) {
                    val mediaId = mediaItem.mediaId
                    if (mediaId.contains(":")) {
                        val bookId = mediaId.substringBefore(":")
                        // 为每一次改动添加详尽的中文注释：冒号后半段现在是稳定的 BookFileEntity.id，用于字幕 VFS 定位，不再依赖播放器的真实播放 URI。
                        val bookFileId = mediaId.substringAfter(":")
                        _currentBookId.value = bookId
                        settingsManager.setMiniPlayerHidden(false)

                        // 详尽的中文注释：切歌时，物理重置并取消上一个章节/分轨的字幕加载协程，清空上一音轨的字幕缓存
                        subtitleLoadJob?.cancel()
                        isEmbeddedSearchActive = false
                        _currentSubtitles.value = emptyList()

                        // 详尽的中文注释：启动全新的字幕加载与 Fallback 竞争协程。
                        // 优先通过 500ms 的 withTimeoutOrNull 监听来自底层 Media3 播放器提取抛出的内置 embeddedSubtitles；
                        // 只要有任何合法非空的内置歌词到来，且窗口期处于激活状态，直接装载并拉闸锁定，杜绝外置检索；
                        // 若超时触发（返回 null）或者被动异常，拉闸锁定内置状态，回退检索物理同级外置字幕文件，实现平滑降级。
                        subtitleLoadJob = viewModelScope.launch {
                            isEmbeddedSearchActive = true
                            val embeddedSubs = try {
                                withTimeoutOrNull(500) {
                                    manager.embeddedSubtitles.first { it.isNotEmpty() }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PlayerViewModel", "等待内置字幕时抛出异常", e)
                                null
                            }

                            if (embeddedSubs != null && isEmbeddedSearchActive) {
                                // 详尽的中文注释：在 500ms 窗口期内拿到了合法的内置歌词，立即拉闸锁死状态，并装载内置歌词
                                isEmbeddedSearchActive = false
                                _currentSubtitles.value = embeddedSubs
                            } else {
                                // 为每一次改动添加详尽的中文注释：外置字幕回退通过 bookFileId 查入库文件并走 VFS 同级目录，不再从 MediaItem.uri 反查。
                                isEmbeddedSearchActive = false
                                val subs = libraryRepository?.loadSubtitlesForBookFile(bookFileId) ?: emptyList()
                                _currentSubtitles.value = subs
                            }
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            // 详尽的中文注释：监听播放状态变化。
            // 当检测到播放结束（STATE_ENDED）时，启动一个 5 秒的延迟任务来自动关闭播放界面。
            // 这样能与 PlaybackService 的自动停止逻辑保持同步，提供一致的退出体验。
            manager.playbackState.collectLatest { state ->
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    delay(5000)
                    // 再次检查状态，确保在延迟期间没有发生新的播放操作。
                    // 只要状态仍为 ENDED 或变为 IDLE（Service 可能已清空队列并停止），就关闭界面。
                    val currentState = manager.playbackState.value
                    if (currentState == androidx.media3.common.Player.STATE_ENDED || 
                        currentState == androidx.media3.common.Player.STATE_IDLE) {
                        closeCurrentPlayback()
                    }
                }
            }
        }
    }

    fun loadBook(id: String, playWhenReady: Boolean = true) {
        // 为每一次改动添加详尽的中文注释：
        // 如果当前请求加载的音频书籍 ID 与当前正在播放的音频书籍 ID 相同，则无需重新加载该书。
        // 这可以防止因为重复加载媒体播放计划（loadBook）而打断当前的连续播放状态，提升播放体验的连贯性。
        if (_currentBookId.value == id) {
            // 如果外部传入期望在就绪后立即播放，且目前底层播放器实际处于暂停/非播放状态，则只需直接恢复播放即可，无需执行重载打断当前会话。
            if (playWhenReady && !playbackState.value.isPlaying) {
                play()
            }
            return
        }

        // 详尽的中文注释：加载新书时，必须彻底取消并物理回收正在运行的上一本书的字幕加载协程，重置闸门以杜绝残留回调污染新会话
        subtitleLoadJob?.cancel()
        isEmbeddedSearchActive = false
        _currentBookId.value = id
        _currentSubtitles.value = emptyList() // 重置上一本书的字幕
        settingsManager.setUndoSeekVisible(false)
        settingsManager.dismissChapterList()
        settingsManager.dismissBookmarkDialog()

        viewModelScope.launch {
            val plan = libraryRepository?.getPlaybackPlan(id)
            if (plan != null) {
                playbackDelegate?.loadBook(plan, playWhenReady) { updateCoverPath(it) }
            }
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntity) = bookmarkManager?.deleteBookmark(bookmark)
    fun updateBookmark(bookmark: BookmarkEntity, newTitle: String) = bookmarkManager?.updateBookmark(bookmark, newTitle)
    fun addBookmark(title: String) {
        val id = _currentBookId.value ?: return
        bookmarkManager?.addBookmark(id, playbackState.value.currentPosition, title)
    }

    /**
     * 停止播放并清理当前书籍状态（主要用于删除书籍时）。
     */
    fun closePlayback(bookId: String) {
        if (_currentBookId.value == bookId) {
            // 详尽的中文注释：停止播放新书或关闭物理会话时，必须物理取消正在运行的字幕检索协程并彻底闭锁状态阀门
            subtitleLoadJob?.cancel()
            isEmbeddedSearchActive = false
            _currentBookId.value = null
            _currentSubtitles.value = emptyList()
            playbackManager?.pause()
            settingsManager.setFullPlayerVisible(false)
            settingsManager.setMiniPlayerHidden(true)
        }
    }

    fun closeCurrentPlayback() {
        // Compact player can request a self-exit when its restored media is no longer available.
        _currentBookId.value?.let(::closePlayback)
    }

    fun togglePlayPause() = if (playbackState.value.isPlaying) pause() else play()
    fun play() = playbackDelegate?.play()
    fun pause() = playbackDelegate?.pause()

    fun seekTo(positionMs: Long, allowUndo: Boolean = false) {
        if (allowUndo) {
            lastSeekPosition = playbackState.value.currentPosition
            settingsManager.setUndoSeekVisible(true)
            undoJob?.cancel()
            undoJob = viewModelScope.launch {
                delay(3000)
                settingsManager.setUndoSeekVisible(false)
            }
        } else {
            settingsManager.setUndoSeekVisible(false)
            undoJob?.cancel()
        }
        playbackDelegate?.seekTo(positionMs)
    }

    fun undoSeek() {
        if (settingsState.value.showUndoSeek) {
            seekTo(lastSeekPosition, allowUndo = false)
            settingsManager.setUndoSeekVisible(false)
        }
    }

    fun skipForward() = seekTo((playbackState.value.currentPosition + 30000).coerceAtMost(playbackState.value.duration))
    fun skipBackward() = seekTo((playbackState.value.currentPosition - 10000).coerceAtLeast(0L))

    fun setPlaybackSpeed(speed: Float) = playbackDelegate?.setPlaybackSpeed(speed)
    fun cyclePlaybackSpeed() {
        val speed = playbackState.value.playbackSpeed
        val nextIndex = (PLAYBACK_SPEEDS.indexOf(speed).coerceAtLeast(0) + 1) % PLAYBACK_SPEEDS.size
        setPlaybackSpeed(PLAYBACK_SPEEDS[nextIndex])
    }
    fun resetPlaybackSpeed() = setPlaybackSpeed(1.0f)

    fun cycleSleepTimer() {
        val options = SLEEP_TIMER_OPTIONS
        val nextIndex = (options.indexOf(settingsState.value.selectedSleepTimer).coerceAtLeast(0) + 1) % options.size
        setSleepTimer(options[nextIndex])
    }

    fun setSleepTimer(minutes: Int) = settingsManager.setSleepTimer(minutes, { playbackState.value }, { metadataState.value })
    fun adjustVolume(delta: Float) = settingsManager.adjustVolume(delta)
    
    fun showChapterList() = settingsManager.showChapterList()
    fun dismissChapterList() = settingsManager.dismissChapterList()
    fun showBookmarkDialog() = settingsManager.showBookmarkDialog()
    fun dismissBookmarkDialog() = settingsManager.dismissBookmarkDialog()
    fun updateBookmarkTitle(title: String) = settingsManager.updateBookmarkTitle(title)
    fun saveBookmarkFromDialog() {
        addBookmark(settingsState.value.bookmarkTitle.ifBlank { "Bookmark" })
        dismissBookmarkDialog()
    }
    fun setSelectedContentTab(tab: Int) = settingsManager.setSelectedContentTab(tab)
    fun setFullPlayerVisible(visible: Boolean) {
        settingsManager.setFullPlayerVisible(visible)
        if (visible) {
            // 为每一次改动添加详尽的中文注释：
            // 当进入全屏播放器界面时（visible 为 true），自动重置并解除迷你播放器的隐藏状态（设置为 false）。
            // 从而保证当用户后续关闭/收起全屏播放器时，底部的迷你播放器能够自动重新显示，不会因为之前的 hide 状态而消失。
            settingsManager.setMiniPlayerHidden(false)
        }
    }
    fun setMiniPlayerHidden(hidden: Boolean) = settingsManager.setMiniPlayerHidden(hidden)

    fun currentBookAvailability(bookId: String): kotlinx.coroutines.flow.Flow<Boolean> = flow {
        // Empty metadata means the compact player is not attached to a real restored book yet.
        if (bookId.isBlank()) {
            emit(true)
            return@flow
        }
        emit(libraryRepository?.checkCurrentPlaybackFileAvailability(bookId) ?: false)
    }
    
    fun toggleProgressMode() {
        viewModelScope.launch {
            val nextMode = !settingsState.value.isChapterProgressMode
            settingsRepository?.updateChapterProgressMode(nextMode)
        }
    }
    fun onRouteChanged() = settingsManager.setMiniPlayerHidden(false)

    fun skipToNextChapter() = playbackDelegate?.skipToNextChapter(metadataState.value.chapters, playbackState.value.currentPosition)
    fun skipToPreviousChapter() = playbackDelegate?.skipToPreviousChapter(metadataState.value.chapters, playbackState.value.currentPosition)

    fun updateCoverPath(path: String?) {
        val id = _currentBookId.value ?: return
        path?.let { p ->
            viewModelScope.launch(Dispatchers.Default) {
                val entity = libraryRepository?.getBookById(id)
                if (entity?.backgroundColorArgb != null) {
                    _lastDominantColor = entity.backgroundColorArgb
                } else {
                    val color = ImageProcessor.getDominantColor(p)
                    _lastDominantColor = color
                    libraryRepository?.updateBackgroundColor(id, color)
                }
                settingsManager.setSelectedContentTab(settingsState.value.selectedContentTab)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // H-21: 不再释放进程级单例 PlaybackManager，避免破坏其他持有者（迷你播放器、Service）的会话。
        // PlaybackManager 的生命周期由进程管理，不应由单个 ViewModel 控制。
    }
}
