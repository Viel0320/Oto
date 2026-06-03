package com.viel.aplayer.ui.player.layouts

// 导入 Jetpack Compose 动画、手势和布局 API
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
 * 平板/大屏横屏播放器自适应布局组件 (PlayerTabletLandscape)。
 * 专门在平板横屏大屏幕或折叠屏展开的横屏状态下提供顶级的视觉表现。
 * 左右双栏对称铺开，上下应用 10% 屏幕高度的大边距，产生极其尊贵的高级感与舒适空灵的呼吸感。
 */
@Composable
fun PlayerTabletLandscape(
    currentPosition: Long, // 播放器当前物理播放进度（毫秒）
    totalDuration: Long, // 播放器当前物理总时长（毫秒）
    isChapterMode: Boolean, // 当前进度条是否处于章节进度视图模式
    currentChapter: com.viel.aplayer.data.entity.ChapterEntity?, // 当前正处于播放状态的章节实体
    isPlaying: Boolean, // 当前是否处于播放中状态
    playbackSpeed: Float, // 当前的播放速率
    isSpeedManualMode: Boolean, // 播放速率是否被手动调节锁定
    bookmarkToDelete: com.viel.aplayer.data.entity.BookmarkEntity?, // 待删除书签实体
    bookmarkToEdit: com.viel.aplayer.data.entity.BookmarkEntity?, // 待编辑书签实体
    bookmarkEditTitle: String, // 编辑书签时输入框中的草稿标题
    onRequestDeleteBookmark: (com.viel.aplayer.data.entity.BookmarkEntity) -> Unit, // 触发删除书签确认弹窗的回调
    onRequestEditBookmark: (com.viel.aplayer.data.entity.BookmarkEntity) -> Unit, // 触发编辑书签弹窗的回调
    onBookmarkEditTitleChange: (String) -> Unit, // 书签编辑标题输入变更的回调
    onConfirmDeleteBookmark: () -> Unit, // 确认删除书签的回调
    onConfirmUpdateBookmark: () -> Unit, // 确认更新书签标题的回调
    onDismissBookmarkDialogs: () -> Unit, // 取消/关闭书签对话框的回调
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
    // 详尽的中文注释：使用全局窗口属性 LocalWindowClass 获取屏幕逻辑像素尺寸，去掉了在此对 LocalConfiguration 对象的物理读取，增加了布局逻辑的内聚性与可移植性。
    val windowClass = LocalWindowClass.current
    val density = LocalDensity.current

    // 平板大屏幕下使用更宽裕的大边距
    val screenWidthDp = windowClass.screenWidthDp
    val screenHeightDp = windowClass.screenHeightDp
    val sidePadding = screenWidthDp * 0.04f
    val middleSpacing = screenWidthDp * 0.06f

    // 平板专用策略 — 上下固定留出 10% 屏幕高度，极具品质呼吸感
    val topPadding = screenHeightDp * 0.1f
    val bottomPadding = screenHeightDp * 0.1f

    // 根据系统方向在物理左右侧叠加计算刘海和系统栏安全边距，保证大封面与右侧控制完全显示
    val systemBarsPadding = WindowInsets.systemBars.asPaddingValues()
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
        // 1. Left side tab content column (Ratio 1f)
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
                // 页签大内容区动画过渡
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
                                            // 直接调用无状态的 SubtitlesView 渲染字幕组件
                                            SubtitlesView(
                                                subtitles = metadata.subtitles,
                                                currentPosition = currentPosition,
                                                onSeek = { actions.playback.onSeek(it, true) },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                    else -> {
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
                            // 直接调用无状态的 BookmarkListView，解耦桥接
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

            // 平板导航切签栏
            BottomNavTabs(
                selectedTab = currentMode,
                onTabSelected = {
                    val nextMode = if (currentMode == it) PlayerScreenMode.PLAYER else it
                    onModeChange(nextMode)
                }
            )
        }

        // ==========================================
        // 2. Right side tablet controls & title header (Ratio 1f)
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
                // 平板顶部标题及折叠操作入口
                PlayerLandscapeHeader(
                    metadata = metadata,
                    settings = settings,
                    actions = actions,
                    glassEffectMode = glassEffectMode,
                    backdrop = chapterSheetBackdrop
                )

                // 强制将控制面板下压靠底
                Spacer(modifier = Modifier.weight(1f))
                
                // 平板主控制面板。
                // 传入解包后的基础数据类型，彻底解耦 PlayerViewModel。
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


