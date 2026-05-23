package com.viel.aplayer.ui.player

// 为每一次改动添加详尽 of 中文注释：导入 WindowInsets 及自适应配置检测相关的 Compose API
import android.content.res.Configuration
import android.view.RoundedCorner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.bookmarks.BookmarkDialog
import com.viel.aplayer.ui.common.BlurSnackbar
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.navigation.PlayerNavigationActions
import com.viel.aplayer.ui.player.components.ChapterListSheetStateful
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt

// 为每一次改动添加详尽的中文注释：导入自适应分发子 layouts 模块
import com.viel.aplayer.ui.player.layouts.PlayerPortrait
import com.viel.aplayer.ui.player.layouts.PlayerLandscapePhone
import com.viel.aplayer.ui.player.layouts.PlayerTablet

enum class PlayerScreenMode(val index: Int) {
    PLAYER(-1),
    BOOKMARKS(0),
    SUBTITLES(1),
    RELATED(2)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    actions: PlayerActions,
    navigationActions: PlayerNavigationActions,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由播放器 Overlay 从设置状态显式传入，播放页内部不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
) {
    val isPreview = androidx.compose.ui.platform.LocalInspectionMode.current

    // 为每一次改动添加详尽的中文注释：如果处于 IDE 预览环境，则注入精美的 Mock 数据，避免底层的 Flow 订阅和 ViewModel 的初始化依赖
    val metadata = if (isPreview) {
        BookMetadataState(
            id = "book_1",
            title = "三体：黑暗森林",
            author = "刘慈欣",
            narrator = "王明",
            coverPath = null,
            thumbnailPath = null,
            coverLastUpdated = 0L,
            backgroundColorArgb = android.graphics.Color.parseColor("#FF1E293B"), // 深色灰蓝色背景
            chapters = listOf(
                com.viel.aplayer.data.entity.ChapterEntity("ch_1", "book_1", "file_1", 1, "引子", 0L, 180000L, 0L, "EMBEDDED"),
                com.viel.aplayer.data.entity.ChapterEntity("ch_2", "book_1", "file_1", 2, "第一章：危机纪元", 180000L, 360000L, 180000L, "EMBEDDED")
            )
        )
    } else {
        viewModel.metadataState.collectAsStateWithLifecycle().value
    }

    val settings = if (isPreview) {
        com.viel.aplayer.ui.settings.PlayerSettingsState(
            isFullPlayerVisible = true,
            selectedContentTab = -1, // PLAYER 模式
            isChapterProgressMode = false,
            showUndoSeek = false,
            selectedSleepTimer = 0
        )
    } else {
        viewModel.settingsState.collectAsStateWithLifecycle().value
    }

    val controls = if (isPreview) {
        PlayerViewModel.PlaybackControlState(
            isPlaying = true,
            playbackSpeed = 1.0f,
            isSpeedManualMode = false
        )
    } else {
        viewModel.playbackControlState.collectAsStateWithLifecycle().value
    }
    
    val fullUiState = if (isPreview) {
        PlayerUiState()
    } else {
        viewModel.uiState.collectAsStateWithLifecycle().value
    }

    val targetMode = remember(settings.selectedContentTab) {
        when(settings.selectedContentTab) {
            0 -> PlayerScreenMode.BOOKMARKS
            1 -> PlayerScreenMode.SUBTITLES
            2 -> PlayerScreenMode.RELATED
            else -> PlayerScreenMode.PLAYER
        }
    }

    var currentMode by remember { mutableStateOf(targetMode) }

    // 详尽中文注释：定义全屏播放器预测性返回手势的激活状态和手势百分比进度值（0f 到 1f 之间）
    var isPlayerBackActive by remember { mutableStateOf(false) }
    var playerBackProgress by remember { mutableFloatStateOf(0f) }

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        val corner = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
        corner?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // 为每一次改动添加详尽的中文注释：使用 LocalConfiguration 检测屏幕方向，动态分流横竖屏自适应布局与圆角设计
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    LaunchedEffect(targetMode) {
        currentMode = targetMode
    }

    // 详尽中文注释：移除原先硬编码的 darkTheme = true，使全屏播放器跟随系统/应用主题设置
    APlayerTheme {
        val focusManager = LocalFocusManager.current
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        
        // 为每一次改动添加详尽的中文注释：
        // 重新在 LocalComposables 环境下声明全局 chapterSheetBackdrop 状态机采样源，修复未解析引用的编译错误。
        // 将其作为背景层专属采样源，并与前景组件隔离为同级兄弟，彻底规避 Vulkan feedback loop 死锁崩溃。
        val chapterSheetBackdrop = rememberLayerBackdrop()

        // 详尽的中文注释：当处于书签/歌词/推荐等面板时，使用 PredictiveBackHandler 平滑返回主播放页面
        androidx.activity.compose.PredictiveBackHandler(enabled = currentMode != PlayerScreenMode.PLAYER) { progressFlow ->
            try {
                progressFlow.collect { }
                currentMode = PlayerScreenMode.PLAYER
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // 详尽的中文注释：用户中途滑回取消，不做状态改变
            }
        }

        // 详尽的中文注释：当处于主播放页面且全屏播放器可见时，使用 PredictiveBackHandler 拦截并支持手势平滑最小化
        androidx.activity.compose.PredictiveBackHandler(
            enabled = currentMode == PlayerScreenMode.PLAYER && settings.isFullPlayerVisible
        ) { progressFlow ->
            try {
                // 收集预测性返回拖拽进度流，动态调节播放器向下滑动的过渡程度
                progressFlow.collect { backEvent ->
                    isPlayerBackActive = true
                    playerBackProgress = backEvent.progress
                }
                actions.content.onSelectedTabChange(PlayerScreenMode.PLAYER.index)
                navigationActions.onMinimize()
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
                // 详尽的中文注释：用户在手势拖拽时滑回以取消最小化返回，恢复原状态
            } finally {
                // 详尽的中文注释：手势执行完成或取消时，及时清空手势激活状态和进度值为 0f
                isPlayerBackActive = false
                playerBackProgress = 0f
            }
        }

        // 为每一次改动添加详尽的中文注释：动态捕获背景色过渡状态，产生流光颜色变换的顺滑动画效果
        val animatedBgColor by animateColorAsState(
            targetValue = Color(metadata.backgroundColorArgb),
            animationSpec = tween(300),
            label = "bg_color"
        )
        val bgColor = MaterialTheme.colorScheme.background

        // 详尽的中文注释：计算最大的向下位移像素值，顺应全屏播放器“向下滑动收起”的最小化退出特征
        val maxPredictiveTranslationY = with(density) { 120.dp.toPx() }

        // 详尽的中文注释：在横屏模式下，全屏播放器通常是左右双栏平铺排版，外层不需要竖屏抽屉的顶部大圆角，
        // 设为直角（RectangleShape）既符合大屏沉浸式视觉，也能在物理上彻底杜绝左上角和右上角内容被外层圆角裁切的隐患。
        val playerSurfaceShape = if (isLandscape) {
            androidx.compose.ui.graphics.RectangleShape
        } else {
            RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp)
        }

        Surface(
            modifier = modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.value.roundToInt()) }
                .graphicsLayer {
                    // 详尽的中文注释：当全屏播放器拖拽最小化预测性返回手势处于激活状态时，
                    // 让卡片整体随手势的百分比进度向下平移（最多 120.dp），并伴随轻微等比缩放（1.0f -> 0.95f）与淡出（1.0f -> 0.7f），
                    // 在力导向和视觉上与最终向下滑动收起为迷你播放器的退出动画无缝融合。
                    if (isPlayerBackActive) {
                        translationY = playerBackProgress * maxPredictiveTranslationY
                        val scale = 1f - playerBackProgress * 0.05f
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - playerBackProgress * 0.3f
                    }
                }
                .clip(playerSurfaceShape),
            // 为每一次改动添加详尽的中文注释：将 bgColor 设置为 Surface 的 container color，充当最牢固的主题底层背景，杜绝任何透明漏光发生
            color = bgColor
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 为每一次改动添加详尽的中文注释：
                // 彻底修复特定的 OPLUS (一加) 设备在启用 miuix-blur 模糊效果后，由于父子循环嵌套采样导致的 RenderThread SIGSEGV 段错误闪退。
                // 我们在此重构了 PlayerScreen 的根布局层级，将挂载了 layerBackdrop 采样器的背景图层，与使用 drawBackdrop 绘制毛玻璃的前景内容组件完全剥离为同级的【兄弟节点】。

                // 1. 纯净背景图层 (同级兄弟节点)
                CoverBackground(
                    coverPath = metadata.coverPath ?: metadata.thumbnailPath,
                    lastUpdated = metadata.coverLastUpdated,
                    backgroundColorArgb = metadata.backgroundColorArgb,
                    glassEffectMode = glassEffectMode,
                    backdrop = chapterSheetBackdrop
                )

                // 2. 纯净前景操作图层 (同级兄弟节点，内部所有组件均可安全 drawBackdrop 进行背景采样折射，彻底拆分后的容器分发)
                Box(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 为每一次改动添加详尽的中文注释：根据系统配置，自适应分流调用三大 layouts
                    val isTablet = configuration.smallestScreenWidthDp >= 600
                    when {
                        isTablet -> {
                            PlayerTablet(
                                viewModel = viewModel,
                                metadata = metadata,
                                controls = controls,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetBackdrop = chapterSheetBackdrop
                            )
                        }
                        isLandscape -> {
                            PlayerLandscapePhone(
                                viewModel = viewModel,
                                metadata = metadata,
                                controls = controls,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetBackdrop = chapterSheetBackdrop
                            )
                        }
                        else -> {
                            PlayerPortrait(
                                viewModel = viewModel,
                                metadata = metadata,
                                controls = controls,
                                settings = settings,
                                actions = actions,
                                fullUiState = fullUiState,
                                currentMode = currentMode,
                                onModeChange = {
                                    currentMode = it
                                    actions.content.onSelectedTabChange(it.index)
                                },
                                animatedBgColor = animatedBgColor,
                                glassEffectMode = glassEffectMode,
                                chapterSheetBackdrop = chapterSheetBackdrop,
                                offsetY = offsetY,
                                scope = scope,
                                dismissThreshold = dismissThreshold,
                                focusManager = focusManager,
                                navigationActions = navigationActions
                            )
                        }
                    }
                }

                // 为每一次改动添加详尽的中文注释：可切换 miuix-blur 模糊的 Snackbar，作为兄弟节点直接放置在最外层 Box 的底部层叠位置。
                // 采用 150ms 上滑淡入/下滑淡出，显示时长控制仍由 ViewModel 管理。
                androidx.compose.animation.AnimatedVisibility(
                    visible = settings.showUndoSeek,
                    enter = slideInVertically(
                        animationSpec = tween(150),
                        initialOffsetY = { it }
                    ) + fadeIn(animationSpec = tween(150)),
                    exit = slideOutVertically(
                        animationSpec = tween(150),
                        targetOffsetY = { it }
                    ) + fadeOut(animationSpec = tween(150)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        // 详尽的中文注释：由于 Snackbar 是悬浮在 BottomNavTabs 之上的独立层级，我们将底边距提升至 96.dp，以防它与下方的 BottomNavTabs 发生视觉重叠遮挡。
                        .padding(horizontal = 16.dp, vertical = 96.dp)
                ) {
                    // 为每一次改动添加详尽的中文注释：使用新创建的 BlurSnackbar，支持在 miuix-blur 模式下采样兄弟节点的背景模糊效果，Material 模式下展示原生样式。
                    BlurSnackbar(
                        backdrop = chapterSheetBackdrop,
                        glassEffectMode = glassEffectMode,
                        action = {
                            TextButton(onClick = actions.playback.onUndoSeek) {
                                Text(
                                    text = "Undo",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "Jumped to a new position",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        // 详尽的中文注释：采用 Stateful 局部隔间包裹章节列表弹窗，在弹窗不可见时完全停摆以防高频重组
        ChapterListSheetStateful(
            viewModel = viewModel,
            metadata = metadata,
            settings = settings,
            actions = actions,
            sheetState = sheetState,
            backdrop = chapterSheetBackdrop,
            glassEffectMode = glassEffectMode
        )

        // 详尽的中文注释：桥接就近打字隔离后的 BookmarkDialog。通过回调 localTitle 执行 onTitleChange 和 onSave，无损向下兼容原契约
        BookmarkDialog(
            isVisible = settings.isBookmarkDialogVisible,
            defaultTitle = settings.bookmarkTitle,
            onSave = { localTitle ->
                actions.bookmarks.onTitleChange(localTitle)
                actions.bookmarks.onSave()
            },
            onDismiss = actions.bookmarks.onDismissDialog
        )
    }
}

@Preview(showBackground = true, apiLevel = 36)
@Composable
fun PlayerScreenPreview() {
    APlayerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            PlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                glassEffectMode = GlassEffectMode.Material
            )
        }
    }
}

@Preview(showBackground = true, apiLevel = 36, widthDp = 800, heightDp = 480)
@Composable
fun PlayerScreenLandscapePreview() {
    APlayerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            PlayerScreen(
                viewModel = PlayerViewModel(),
                actions = PlayerActions(),
                navigationActions = PlayerNavigationActions(),
                glassEffectMode = GlassEffectMode.MiuixBlur
            )
        }
    }
}
