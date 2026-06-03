package com.viel.aplayer.ui.player.layouts

// 导入 Jetpack Compose 布局、手势和动画相关的依赖包
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BottomNavTabs
// 详尽的中文注释：在此移除了冗余的旧 LocalWindowClass 导入，统一使用 theme 包下的最新 WindowClass 统一自适应代理。
import com.viel.aplayer.ui.common.PlayerCover
import com.viel.aplayer.ui.common.theme.LocalWindowClass
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.player.PlayerUiState
import com.viel.aplayer.ui.player.components.PlayerControlPanel
import com.viel.aplayer.ui.player.components.PlayerLandscapeHeader
import com.viel.aplayer.ui.player.components.RelatedBooksView
import com.viel.aplayer.ui.player.components.SubtitlesView
import com.viel.aplayer.ui.player.components.bookmarks.BookmarkListView
import top.yukonga.miuix.kmp.blur.LayerBackdrop

/**
 * 手机横屏播放器布局组件 (PlayerLandscapePhone)。
 * 专门针对普通智能手机横屏状态下“垂直高度极窄、水平面积极宽”的场景做视觉深度定制优化。
 * 左右双栏对称铺开，并将控制条沉底压实。
 *
 * 已经过重构彻底消除了对 PlayerViewModel 及其内部 State 类型的任何依赖，
 * 达成了 100% 纯无状态 L3 布局组件的高标准，符合 Compose 架构分层设计。
 *
 * @param currentPosition 播放器当前物理播放进度（毫秒）
 * @param totalDuration 播放器当前物理总时长（毫秒）
 * @param isChapterMode 当前进度条是否处于章节进度视图模式
 * @param currentChapter 当前正处于播放状态的章节实体
 * @param isPlaying 当前是否处于播放中状态
 * @param playbackSpeed 当前的播放速率
 * @param isSpeedManualMode 播放速率是否被手动调节锁定
 * @param bookmarkToDelete 待删除书签实体
 * @param bookmarkToEdit 待编辑书签实体
 * @param bookmarkEditTitle 编辑书签时输入框中的草稿标题
 * @param onRequestDeleteBookmark 触发删除书签确认弹窗的回调
 * @param onRequestEditBookmark 触发编辑书签弹窗的回调
 * @param onBookmarkEditTitleChange 书签编辑标题输入变更的回调
 * @param onConfirmDeleteBookmark 确认删除书签的回调
 * @param onConfirmUpdateBookmark 确认更新书签标题的回调
 * @param onDismissBookmarkDialogs 取消/关闭书签对话框的回调
 */
@Composable
fun PlayerLandscapePhone(
    currentPosition: Long,
    totalDuration: Long,
    isChapterMode: Boolean,
    currentChapter: com.viel.aplayer.data.entity.ChapterEntity?,
    isPlaying: Boolean,
    playbackSpeed: Float,
    isSpeedManualMode: Boolean,
    bookmarkToDelete: com.viel.aplayer.data.entity.BookmarkEntity?,
    bookmarkToEdit: com.viel.aplayer.data.entity.BookmarkEntity?,
    bookmarkEditTitle: String,
    onRequestDeleteBookmark: (com.viel.aplayer.data.entity.BookmarkEntity) -> Unit,
    onRequestEditBookmark: (com.viel.aplayer.data.entity.BookmarkEntity) -> Unit,
    onBookmarkEditTitleChange: (String) -> Unit,
    onConfirmDeleteBookmark: () -> Unit,
    onConfirmUpdateBookmark: () -> Unit,
    onDismissBookmarkDialogs: () -> Unit,
    metadata: BookMetadataState,
    settings: com.viel.aplayer.ui.settings.PlayerSettingsState,
    actions: PlayerActions,
    fullUiState: PlayerUiState,
    currentMode: PlayerScreenMode,
    onModeChange: (PlayerScreenMode) -> Unit,
    animatedBgColor: Color,
    glassEffectMode: GlassEffectMode,
    chapterSheetBackdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    // 详尽的中文注释：使用全局窗口属性 LocalWindowClass 获取屏幕宽度和高度，完全隔离 LocalConfiguration 以提供更内聚的自适应布局体验。
    val windowClass = LocalWindowClass.current
    val density = LocalDensity.current

    // 根据手机屏幕总宽度按比重划分呼吸间距
    val screenWidthDp = windowClass.screenWidthDp
    val screenHeightDp = windowClass.screenHeightDp
    val sidePadding = screenWidthDp * 0.04f
    val middleSpacing = screenWidthDp * 0.06f

    // 由于是手机常规横大屏，上下边距需完全贴紧并规避状态栏与导航栏以保障大封面的渲染利用率
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
    val topPadding = systemBarsPadding.calculateTopPadding()
    val bottomPadding = systemBarsPadding.calculateBottomPadding()

    // 左右避让刘海缺口/挖孔屏及虚拟导航键，保障左侧和右侧操作绝对不被切角
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = sidePadding + systemBarsPadding.calculateStartPadding(layoutDirection)
    val endPadding = sidePadding + systemBarsPadding.calculateEndPadding(layoutDirection)

    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = startPadding,
                top = topPadding,
                end = endPadding,
                bottom = bottomPadding
            ),
        horizontalArrangement = Arrangement.spacedBy(middleSpacing)
    ) {
        val swipeThresholdPx = with(density) { 80.dp.toPx() }
        val tabModes = remember {
            listOf(PlayerScreenMode.BOOKMARKS, PlayerScreenMode.SUBTITLES, PlayerScreenMode.RELATED)
        }
        val contentShell = remember(currentMode) {
            when (currentMode) {
                PlayerScreenMode.BOOKMARKS -> PlayerContentShell.Bookmarks
                PlayerScreenMode.RELATED -> PlayerContentShell.Related
                PlayerScreenMode.PLAYER,
                PlayerScreenMode.SUBTITLES -> PlayerContentShell.PlaybackShell
            }
        }

        // ==========================================
        // 1. 左侧自适应页签内容列 (分配占比 1f)
        // ==========================================
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = screenWidthDp * 0.04f)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(currentMode) {
                        // 仅在非 PLAYER 模式下激活滑动手势以便在书签、歌词、关联中左右切换
                        if (currentMode == PlayerScreenMode.PLAYER) return@pointerInput
                        var accumulatedX = 0f
                        var hasSwipeTriggered = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onDragEnd = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onDragCancel = {
                                accumulatedX = 0f
                                hasSwipeTriggered = false
                            },
                            onHorizontalDrag = { change, dragAmount ->
                                if (!hasSwipeTriggered) {
                                    accumulatedX += dragAmount
                                    if (kotlin.math.abs(accumulatedX) > swipeThresholdPx) {
                                        val currentIndex = tabModes.indexOf(currentMode)
                                        val nextMode = if (accumulatedX < 0) {
                                            if (currentIndex < tabModes.lastIndex) tabModes[currentIndex + 1]
                                            else PlayerScreenMode.PLAYER
                                        } else {
                                            if (currentIndex > 0) tabModes[currentIndex - 1]
                                            else PlayerScreenMode.PLAYER
                                        }
                                        onModeChange(nextMode)
                                        hasSwipeTriggered = true
                                    }
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                // 横向滑动切换左边的大卡片模式（主播放/书签/推荐面板）
                AnimatedContent(
                    targetState = contentShell,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        if (targetState.index > initialState.index) {
                            (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(300)))
                        } else {
                            (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(300)))
                                .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(300)))
                        }.using(SizeTransform(clip = false))
                    },
                    label = "player_mode_transition"
                ) { shell ->
                    when (shell) {
                        PlayerContentShell.PlaybackShell -> {
                            val playbackTopMode = if (currentMode == PlayerScreenMode.SUBTITLES) {
                                PlayerScreenMode.SUBTITLES
                            } else {
                                PlayerScreenMode.PLAYER
                            }
                            // 渐变过渡，支持大封面与歌词滚动的淡入淡出无缝对接
                            AnimatedContent(
                                targetState = playbackTopMode,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    (fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300)))
                                        .using(SizeTransform(clip = false))
                                },
                                label = "player_playback_top_transition"
                            ) { topMode ->
                                when (topMode) {
                                    PlayerScreenMode.SUBTITLES -> {
                                        Box(modifier = Modifier.fillMaxSize()) {
                                            // 直接调用无状态的 SubtitlesView 渲染字幕组件，实现极致的重绘隔离
                                            SubtitlesView(
                                                subtitles = metadata.subtitles,
                                                currentPosition = currentPosition,
                                                onSeek = { actions.playback.onSeek(it, true) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    else -> {
                                        // 独立手势声音/左右切歌大封面，在横大屏下高度自适应顶满
                                        PlayerCover(
                                            coverPath = metadata.coverPath,
                                            isPlaying = isPlaying,
                                            coverLastUpdated = metadata.coverLastUpdated,
                                            onAdjustVolume = { actions.playback.onAdjustVolume(it) },
                                            onNextChapter = { actions.playback.onNextChapter() },
                                            onPreviousChapter = { actions.playback.onPreviousChapter() }
                                        )
                                    }
                                }
                            }
                        }
                        PlayerContentShell.Bookmarks -> {
                            // 直接调用无状态的 BookmarkListView，解耦所有的有状态桥接层
                            Box(modifier = Modifier.fillMaxSize()) {
                                BookmarkListView(
                                    bookmarks = metadata.bookmarks,
                                    bookmarkToDelete = bookmarkToDelete,
                                    bookmarkToEdit = bookmarkToEdit,
                                    bookmarkEditTitle = bookmarkEditTitle,
                                    onBookmarkClick = { pos -> actions.playback.onSeek(pos, true) },
                                    onRequestDelete = onRequestDeleteBookmark,
                                    onRequestEdit = onRequestEditBookmark,
                                    onEditTitleChange = onBookmarkEditTitleChange,
                                    onConfirmDelete = onConfirmDeleteBookmark,
                                    onConfirmUpdate = onConfirmUpdateBookmark,
                                    onDismissDialogs = onDismissBookmarkDialogs,
                                    currentPosition = currentPosition,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                        PlayerContentShell.Related -> {
                            Box(modifier = Modifier.fillMaxSize()) {
                                RelatedBooksView(
                                    currentBookId = metadata.id,
                                    heuristicBooks = fullUiState.heuristicRecommendedBooks,
                                    authorSections = fullUiState.relatedAuthorSections,
                                    narratorSections = fullUiState.relatedNarratorSections,
                                    recentBooks = fullUiState.recentlyAddedBooks,
                                    onBookClick = actions.content.onLoadRelatedBook
                                )
                            }
                        }
                    }
                }
            }

            // 页签切换条
            BottomNavTabs(
                selectedTab = currentMode,
                onTabSelected = {
                    val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                    onModeChange(nextMode)
                }
            )
        }

        // ==========================================
        // 2. 右侧固定播放控制列 (分配占比 1f)
        // ==========================================
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = androidx.compose.ui.graphics.RectangleShape,
            color = Color.Transparent,
            border = null
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 右侧顶部栏，容纳标题、作者信息、睡眠定时器及折叠控制菜单
                PlayerLandscapeHeader(
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    glassEffectMode = glassEffectMode,
                    backdrop = chapterSheetBackdrop
                )

                // 撑满多余的垂直高度，强制使控制面板在底部对齐
                Spacer(modifier = Modifier.weight(1f))
                
                // 横屏下的主要播放控制按钮及细长进度调节面板，采用全宽自适应填充。
                // 传入解包后的参数，彻底去除对 PlayerViewModel 的依赖。
                PlayerControlPanel(
                    currentPosition = currentPosition,
                    totalDuration = totalDuration,
                    isChapterMode = isChapterMode,
                    currentChapter = currentChapter,
                    isPlaying = isPlaying,
                    playbackSpeed = playbackSpeed,
                    isSpeedManualMode = isSpeedManualMode,
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    buttonColor = animatedBgColor,
                    glassEffectMode = glassEffectMode,
                    backdrop = chapterSheetBackdrop,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
