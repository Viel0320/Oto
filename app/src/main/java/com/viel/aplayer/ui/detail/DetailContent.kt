package com.viel.aplayer.ui.detail

// 使用 miuix-blur 替换旧的模糊库依赖，以实现基于视口的高分辨率毛玻璃高斯模糊效果。
// 引入 Compose 的各种组件与状态依赖以构造无状态的书籍详情页纯渲染组件 DetailContent。
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
import androidx.compose.runtime.mutableFloatStateOf
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
import com.viel.aplayer.ui.detail.layouts.DetailTabletLandscape
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.roundToInt

/**
 * 纯 L3 级别的无状态详情页渲染骨架 DetailContent。
 * 遵循 Compose 三层分层架构规范，移除所有与外部 UI 状态或 ViewModel 级别的耦合引用，
 * 仅接收基础数据类型与纯粹的 Lambda 回调函数，为多端自适应布局提供更高效、可测试的渲染层。
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun DetailContent(
    isVisible: Boolean, // 是否当前可见，用以配置拦截预测性返回手势
    book: BookEntity?, // 书籍的元数据实体
    bookWithProgress: BookWithProgress?, // 携带播放进度的完整书籍状态
    isAvailable: Boolean, // 本地文件乐观可用性状态
    progressPercent: Int, // 媒体的历史播放进度百分比
    displayProgressPercent: Int, // 经过保护期过滤后的前端显示播放百分比
    backgroundColorArgb: Int, // 精确提取后的书籍背景 ARGB 色值
    fullSourcePath: String, // 物理文件完整映射源路径
    onBackClick: () -> Unit, // 点击返回键或下滑退场触发的回调
    modifier: Modifier = Modifier,
    onPlayPressed: () -> Unit = {}, // 物理点击播放键开始防抖状态前置监听
    onPlayClick: () -> Unit = {}, // 正式播放操作回调
    onMoreClick: () -> Unit = {}, // 点击右上角更多控制
    onSearchClick: (String) -> Unit = {}, // 点击特定标签进行相关书籍搜索回调
    glassEffectMode: GlassEffectMode, // 精准控制 Material 与磨砂毛玻璃 miuix-blur 双模切换
    backdrop: LayerBackdrop? = null, // 上层共享的采样源
    fullPageBackdrop: LayerBackdrop? = null, // 全屏无缝采样的模糊映射源
    onEditClick: (String) -> Unit = {}, // 点击编辑书籍元数据详情回调
) {
    // 状态定义：预测性返回拖拽过程中的物理缩放与动画位移进度
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }
    
    // 右上角 TopAppBar 下拉折叠管理菜单显示管理
    var showMenu by remember { mutableStateOf(false) }
    
    // 背景通铺渲染专用的 layerBackdrop 采样源，避免子浮层毛玻璃递归穿帮段错误闪退
    val coverBackdrop = rememberLayerBackdrop()
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur

    // 动态捕获最精确的系统状态栏与物理安全区大小
    val safeDrawingPadding = WindowInsets.safeDrawing.asPaddingValues()

    val scope = rememberCoroutineScope()
    val offsetY = remember { Animatable(0f) }
    val density = LocalDensity.current
    val dismissThreshold = with(density) { 50.dp.toPx() }

    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    // 将外部传入的零散扁平化参数，重新且安全地包装为 DetailUiState，
    // 以实现对底层既有 Layout 子骨架（DetailPortrait/DetailLandscapePhone/DetailTablet）的历史无缝契合，规避级联修改成本。
    val uiState = remember(bookWithProgress, isVisible, isAvailable, progressPercent, displayProgressPercent, backgroundColorArgb, fullSourcePath) {
        DetailUiState(
            book = bookWithProgress,
            isVisible = isVisible,
            isAvailable = isAvailable,
            progressPercent = progressPercent,
            displayProgressPercent = displayProgressPercent,
            backgroundColorArgb = backgroundColorArgb,
            fullSourcePath = fullSourcePath
        )
    }

    // 感知系统级拦截并实时绘制系统预测性返回过渡动画
    androidx.activity.compose.PredictiveBackHandler(enabled = isVisible) { progressFlow ->
        try {
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            onBackClick()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 中途滑回放弃返回手势
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    val bgColor = MaterialTheme.colorScheme.background
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer {
                // 手势拖拽向下平移，并不再应用 Scale 形变，使得动画更沉浸与稳固
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .background(bgColor),
        color = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 背景毛玻璃流光动画与大图采样渲染
            CoverBackground(
                coverPath = book?.coverPath,
                lastUpdated = book?.lastScannedAt ?: 0L,
                backgroundColorArgb = backgroundColorArgb,
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
                                    backdrop = fullPageBackdrop ?: coverBackdrop,
                                    glassEffectMode = glassEffectMode
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("修改书籍信息") },
                                        onClick = {
                                            showMenu = false
                                            book?.id?.let { bookId ->
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
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
                        modifier = Modifier.pointerInput(Unit) {
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
                // 动态获取当前的配置与屏幕方向，用于设备自适应布局分流
                val configuration = LocalConfiguration.current
                val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                val isTablet = configuration.smallestScreenWidthDp >= 600
                val isTabletLandscape = isTablet && isLandscape

                when {
                    isTabletLandscape -> {
                        DetailTabletLandscape(
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
fun DetailContentPreview() {
    APlayerTheme {
        DetailContent(
            isVisible = true,
            book = BookEntity(
                id = "id",
                rootId = "preview-root",
                sourceType = "SINGLE_AUDIO",
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Narrator A",
                totalDurationMs = 36000L,
                year = "2023",
                description = "A preview description."
            ),
            bookWithProgress = BookWithProgress(
                book = BookEntity(
                    id = "id",
                    rootId = "preview-root",
                    sourceType = "SINGLE_AUDIO",
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
            progressPercent = 45,
            displayProgressPercent = 45,
            backgroundColorArgb = AppSettings.DEFAULT_GLASS_EFFECT_MODE.ordinal,
            fullSourcePath = "",
            onBackClick = {},
            glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
        )
    }
}
