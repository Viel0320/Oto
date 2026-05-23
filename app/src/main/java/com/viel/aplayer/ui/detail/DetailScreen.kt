package com.viel.aplayer.ui.detail

// 为每一次改动添加详尽的中文注释：导入 WindowInsets.safeDrawing 动态物理避让 API 依赖，以支持横大屏下书籍详情页的防物理裁切
// 为每一次改动添加详尽的中文注释：使用 miuix-blur 替换旧的模糊库依赖，以实现基于视口的高分辨率毛玻璃高斯模糊效果。
import android.content.res.Configuration
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDialog
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.CoverBackground
import com.viel.aplayer.ui.detail.components.SelectableTextView
import com.viel.aplayer.ui.detail.layouts.DetailLandscapePhone
import com.viel.aplayer.ui.detail.layouts.DetailPortrait
import com.viel.aplayer.ui.detail.layouts.DetailTablet
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt


@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    // 详尽中文注释：M-19 修复 — 增加 onPlayPressed 参数，
    // 保护期逻辑已全部下沉到 DetailViewModel.onPlayPressed，
    // 此参数将在点击播放时在调用 onPlayClick 前一并触发
    onPlayPressed: () -> Unit = {},
    onPlayClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onSearchClick: (String) -> Unit = {},
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由详情 Overlay 从设置状态显式传入，详情页内部不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    // 为每一次改动添加详尽的中文注释：将共享的模糊状态变更为 miuix-blur 的 LayerBackdrop 采样源参数
    backdrop: LayerBackdrop? = null,
    // 为每一次改动添加详尽的中文注释：接收从 DetailOverlay 穿透进来的详情页整页包含前景组件的采样源，用以为子浮层组件提供无穿帮高质感模糊。
    fullPageBackdrop: LayerBackdrop? = null,
    // 为每一次改动添加详尽的中文注释：增加元数据编辑点击的回调函数，以实现非 Activity Overlay 化无缝跳转
    onEditClick: (String) -> Unit = {},
) {
    val bookWithProgress = uiState.book
    val book = bookWithProgress?.book
    // 详尽中文注释：定义预测性返回手势的激活状态和手势进度值（0f 到 1f 之间）
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    // 为每一次改动添加详尽的中文注释：控制详情页右上角 TopAppBar 折叠菜单的显示隐藏状态
    var showMenu by remember { mutableStateOf(false) }
    // 为每一次改动添加详尽的中文注释：专用于背景与页面内 sibling 组件防环路渲染死锁的局部仅背景采样源。
    val coverBackdrop = rememberLayerBackdrop()
    // 为每一次改动添加详尽的中文注释：感知当前 miuix-blur 磨砂玻璃模式是否已被开启。修改引用为新更名的 MiuixBlur
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur

    // 为每一次改动添加详尽的中文注释：利用运行时 WindowInsets.safeDrawing 动态获取最精确的状态栏、导航栏与刘海缺口三合一安全区边界，完全零硬编码
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()
    // 详尽中文注释：M-19 修复 — 3 秒保护期状态已全部移至 DetailViewModel，
    // 此处不再持有 isUnplayedProtectionActive，展示进度直接使用 uiState.displayProgressPercent。

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets =
            view.rootWindowInsets

        insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // 详尽的中文注释：接管并拦截系统预测性返回手势事件，动态收集并流式更新拖拽进度
    androidx.activity.compose.PredictiveBackHandler(enabled = uiState.isVisible) { progressFlow ->
        try {
            // 收集返回事件进度流，以动态感知返回拖拽百分比进度
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            // 手势完整滑动完成后，执行返回事件以顺畅退场
            onBackClick()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 详尽的中文注释：用户拖拽过程中放弃返回并滑回，恢复卡片状态
        } finally {
            // 详尽的中文注释：手势事件流结束后，重置预测性返回的触发激活状态与进度为 0f
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    val bgColor = MaterialTheme.colorScheme.background
    // 详尽的中文注释：计算最大的向下位移像素值，顺应详情页向下滑动退出的特征
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer {
                // 详尽的中文注释：当手势处于预测性返回拖拽状态时，顺应详情页向下滑动关闭的退出动画特征，
                // 让卡片整体随返回手势的进度向下平移，并伴随淡出效果（1.0f -> 0.7f）。依据用户最新指令，
                // 在此已彻底去除了原有的微小等比缩放效果（scaleX / scaleY 缩放形变），以保证大屏及常规手势返回时界面的稳固与平滑。
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .background(bgColor),
        color = Color.Transparent
    ) {
        // 为每一次改动添加详尽的中文注释：
        // 在最外层使用一个 fillMaxSize 的 Box 容器，用以在底层异步渲染铺满的全屏大封面，
        // 挂载 layerBackdrop 并挂载 drawBackdrop，使得详情页大背景呈现基于封面采样的毛玻璃高斯模糊底色。
        Box(modifier = Modifier.fillMaxSize()) {
            // 为每一次改动添加详尽的中文注释：
            // 将所有背景渲染逻辑（包括渐变颜色动画、miuix-blur 采样源挂载、封面强模糊背景）
            // 全部抽离至通用的 CoverBackground 组件内，实现视觉逻辑的高度内聚与播放页的完美复用。
            CoverBackground(
                coverPath = book?.coverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
                backgroundColorArgb = uiState.backgroundColorArgb,
                glassEffectMode = glassEffectMode,
                backdrop = coverBackdrop
            )

            Scaffold(
                topBar = {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back_content_description)
                            )
                        }
                    },
                    actions = {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    Icons.Rounded.MoreVert,
                                    contentDescription = stringResource(R.string.more_content_description)
                                )
                            }
                            BlurDropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                                // 为每一次改动添加详尽的中文注释：把详情页全量采样源透传给下拉菜单，实现包含前景文字与按钮的高真毛玻璃折射，并在 null 时安全自适应降级。
                                backdrop = fullPageBackdrop ?: coverBackdrop,
                                // 为每一次改动添加详尽的中文注释：更多菜单跟随设置页选择在 Material 与 miuix-blur 之间切换。
                                glassEffectMode = glassEffectMode
                            ) {
                                DropdownMenuItem(
                                    text = { Text("修改书籍信息") },
                                    onClick = {
                                        showMenu = false
                                        book?.id?.let { bookId ->
                                            // 为每一次改动添加详尽的中文注释：
                                            // 废弃原跨 Activity 物理启动，改用全内存 lambda 回调拉起编辑悬浮 Overlay。
                                            onEditClick(bookId)
                                        }
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    ),
                    // 为每一次改动添加详尽的中文注释：将 WindowInsets.statusBars 升级为 safeDrawing.exclude，
                    // 以使其在横竖屏以及带刘海屏幕的左右侧实现自适应完美规避，彻底去除 modifier 上手写的左右收缩 padding，使背景极致通铺
                    windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
                    modifier = Modifier
                        .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { change, dragAmount ->
                                val newOffset = (offsetY.value + dragAmount).coerceAtLeast(0f)
                                scope.launch {
                                    offsetY.snapTo(newOffset)
                                }
                                change.consume()
                            },
                            onDragEnd = {
                                scope.launch {
                                    if (offsetY.value > dismissThreshold) {
                                        onBackClick()
                                    } else {
                                        offsetY.animateTo(0f, animationSpec = tween(300))
                                    }
                                }
                            },
                            onDragCancel = {
                                scope.launch { offsetY.animateTo(0f, animationSpec = tween(300)) }
                            }
                        )
                    }
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            // 为每一次改动添加详尽的中文注释：使用适配最佳实践分发布局。
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val isTablet = configuration.smallestScreenWidthDp >= 600

            when {
                // 1. 平板/大屏模式：不论横竖屏，都使用双栏 Tablet 布局
                isTablet -> {
                    DetailTablet(
                        book = book,
                        uiState = uiState,
                        padding = padding,
                        safeDrawingPadding = safeDrawingPadding,
                        glassEffectMode = glassEffectMode,
                        detailBackdrop = coverBackdrop,
                        onPlayPressed = onPlayPressed,
                        onPlayClick = onPlayClick,
                        onSearchClick = onSearchClick,
                        onShowInfo = { title, text ->
                            infoDialogTitle = title
                            infoDialogText = text
                        }
                    )
                }
                // 2. 手机横屏模式：针对窄高度优化
                isLandscape -> {
                    DetailLandscapePhone(
                        book = book,
                        uiState = uiState,
                        padding = padding,
                        safeDrawingPadding = safeDrawingPadding,
                        glassEffectMode = glassEffectMode,
                        detailBackdrop = coverBackdrop,
                        onPlayPressed = onPlayPressed,
                        onPlayClick = onPlayClick,
                        onSearchClick = onSearchClick,
                        onShowInfo = { title, text ->
                            infoDialogTitle = title
                            infoDialogText = text
                        }
                    )
                }
                // 3. 默认手机竖屏模式
                else -> {
                    DetailPortrait(
                        book = book,
                        uiState = uiState,
                        padding = padding,
                        glassEffectMode = glassEffectMode,
                        detailBackdrop = coverBackdrop,
                        onPlayPressed = onPlayPressed,
                        onPlayClick = onPlayClick,
                        onSearchClick = onSearchClick,
                        onShowInfo = { title, text ->
                            infoDialogTitle = title
                            infoDialogText = text
                        }
                    )
                }
            }
        }
    }
}

    if (infoDialogText != null) {
        if (isBlur) {
            // 为每一次改动添加详尽的中文注释：
            // 在 miuix-blur 磨砂玻璃模式下，将详情页 info 弹窗重构为基于 fullPageBackdrop 的 BlurDialog。
            // 透传详情页全量采样源以获得无穿帮的全前景模糊，并且在 null 时安全回退至 coverBackdrop 背景采样源。
            BlurDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                backdrop = fullPageBackdrop ?: coverBackdrop,
                glassEffectMode = glassEffectMode
            ) {
                Column(
                    modifier = Modifier.padding(24.dp)
                ) {
                    infoDialogTitle?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    infoDialogText?.let { dialogText ->
                        SelectableTextView(
                            text = dialogText,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = MaterialTheme.colorScheme.onSurface,
                            textSizeSp = 16f,
                            lineSpacingExtraSp = 4f
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = {
                                infoDialogText = null
                                infoDialogTitle = null
                            }
                        ) {
                            Text("OK")
                        }
                    }
                }
            }
        } else {
            AlertDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                confirmButton = {
                    TextButton(onClick = {
                        infoDialogText = null
                        infoDialogTitle = null
                    }) {
                        Text("OK")
                    }
                },
                title = { infoDialogTitle?.let { Text(it) } },
                text = {
                    // 为每一次改动添加详尽的中文注释：使用安全的 let 作用域替代 infoDialogText!! 强制解包，防止发生 NPE 崩溃 (H-10)
                    infoDialogText?.let { dialogText ->
                        SelectableTextView(
                            text = dialogText,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = MaterialTheme.colorScheme.onSurface,
                            textSizeSp = 16f,
                            lineSpacingExtraSp = 4f
                        )
                    }
                }
            )
        }
    }
}

// 为每一次改动添加详尽的中文注释：为 DetailScreenPreview 增加多重自适应 Preview 组合。
// 1. Phone Portrait: 手机常规竖屏模式。
// 2. Phone Landscape: 手机横屏窄高模式 (smallest width < 600dp)，用来预览播放控制流转至右侧顶部的流式自适应新排版。
// 3. Tablet Landscape: 平板/折叠屏大屏横屏模式 (smallest width >= 600dp)，用来预览左侧经典双栏卡片式排版。
@Preview(name = "Phone Portrait", showBackground = true, apiLevel = 36)
@Preview(
    name = "Phone Landscape",
    showBackground = true,
    device = "spec:width=720dp,height=360dp,orientation=landscape,dpi=440",
    apiLevel = 36
)
@Preview(
    name = "Tablet Landscape",
    showBackground = true,
    device = "spec:width=1280dp,height=800dp,orientation=landscape,dpi=240",
    apiLevel = 36
)
@Composable
fun DetailScreenPreview() {
    APlayerTheme {
        DetailScreen(
            uiState = DetailUiState(
                book = BookWithProgress(
                    book = BookEntity(
                        id = "id",
                        // Preview data follows the new logical-book model.
                        rootId = "preview-root",
                        sourceType = "SINGLE_AUDIO",
                        // 详尽中文注释：修正 Preview 数据中的拼写空格错误
                        title = "In the Megachurch",
                        author = "Ryo Asai",
                        narrator = "Narrator A",
                        totalDurationMs = 36000L,
                        year = "2023",
                        description = "A preview description."
                    ),
                    progress = null
                ),
                isAvailable = true,
                progressPercent = 45
            ),
            onBackClick = {},
            // 为每一次改动添加详尽的中文注释：Preview 显式引用设置模型里的默认玻璃效果，避免 DetailScreen 参数重新拥有局部默认值。
            glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
        )
    }
}
