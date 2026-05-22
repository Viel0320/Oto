package com.viel.aplayer.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.style.LeadingMarginSpan
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Timelapse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.text.HtmlCompat
import coil.compose.AsyncImage
import com.viel.aplayer.R
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDialog
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.common.HazePresets
import com.viel.aplayer.ui.theme.APlayerTheme
// 为每一次改动添加详尽的中文注释：在此处引入 dev.chrisbanes.haze 相关的 HazeState, hazeSource, rememberHazeState 以及 hazeEffect 与 HazeMaterials，实现以封面采样为中心的完美毛玻璃模糊效果。
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.HazeMaterials




@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class
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
    // 为每一次改动添加详尽的中文注释：添加来自 APlayerApp 全局共享的 Haze 模糊背景状态以实现折射模糊效果。
    hazeState: HazeState? = null,
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
    // 为每一次改动添加详尽的中文注释：为详情页更多菜单创建 HazeState；详情页 Surface 作为 source，菜单内容作为 effect。
    val dropdownMenuHazeState = rememberHazeState()
    // 为每一次改动添加详尽的中文注释：创建专门用于详情页大封面毛玻璃效果的专属 HazeState 采样源，以封面图像实现毛玻璃背景的高级渲染。
    val coverHazeState = rememberHazeState()
    // 为每一次改动添加详尽的中文注释：感知当前 Haze 磨砂玻璃模式是否已被开启。
    val isHaze = glassEffectMode == GlassEffectMode.Haze
    // 详尽中文注释：M-19 修复 — 3 秒保护期状态已全部移至 DetailViewModel，
    // 此处不再持有 isUnplayedProtectionActive，展示进度直接使用 uiState.displayProgressPercent。

    val animatedBgColor by animateColorAsState(
        targetValue = Color(uiState.backgroundColorArgb),
        animationSpec = tween(300),
        label = "bg_color"
    )

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
    val backgroundBrush by remember(animatedBgColor, bgColor, isHaze) {
        derivedStateOf {
            if (isHaze) {
                // 为每一次改动添加详尽的中文注释：在 Haze 磨砂玻璃背景下，调色盘自动降为半透明 (0.35f / 0.5f)，使得封面高斯图能细腻透出
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = 0.35f),
                        bgColor.copy(alpha = 0.5f)
                    )
                )
            } else {
                Brush.verticalGradient(
                    colors = listOf(
                        animatedBgColor.copy(alpha = 0.9f),
                        bgColor.copy(alpha = 0.95f)
                    )
                )
            }
        }
    }
    // 为每一次改动添加详尽的中文注释：只有 Haze 模式才把详情页背景注册为菜单采样源，Material 模式不启用额外渲染。
    val dropdownMenuHazeSourceModifier = if (glassEffectMode == GlassEffectMode.Haze) {
        Modifier.hazeSource(state = dropdownMenuHazeState)
    } else {
        Modifier
    }

    // 详尽的中文注释：计算最大的向下位移像素值，顺应详情页向下滑动退出的特征
    val maxPredictiveTranslationY = with(density) { 120.dp.toPx() }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt()) }
            .graphicsLayer {
                // 详尽的中文注释：当手势处于预测性返回拖拽状态时，顺应详情页向下滑动关闭的退出动画特征，
                // 让卡片整体随返回手势的进度向下平移，并伴随微小等比缩放（1.0f -> 0.95f）与淡出效果（1.0f -> 0.7f）。
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    val scale = 1f - predictiveBackProgress * 0.05f
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .background(bgColor)
            .background(backgroundBrush)
            // 为每一次改动添加详尽的中文注释：详情页完整内容作为右上角 BlurDropdownMenu 的 Haze 背景采样源。
            .then(dropdownMenuHazeSourceModifier),
        color = Color.Transparent
    ) {
        // 为每一次改动添加详尽的中文注释：
        // 在最外层使用一个 fillMaxSize 的 Box 容器，用以在底层异步渲染铺满的全屏大封面，
        // 挂载 hazeSource(coverHazeState) 并在上层 Box 叠加 hazeEffect，使得详情页大背景呈现基于封面采样的毛玻璃高斯模糊底色。
        Box(modifier = Modifier.fillMaxSize()) {
            if (isHaze && book?.coverPath != null) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val bgRequest = remember(book.coverPath, book.lastScannedAt) {
                    coil.request.ImageRequest.Builder(context)
                        .data(File(book.coverPath))
                        .memoryCacheKey("${book.coverPath}?bg=true&t=${book.lastScannedAt}")
                        .diskCacheKey("${book.coverPath}?bg=true&t=${book.lastScannedAt}")
                        .crossfade(true)
                        .build()
                }
                AsyncImage(
                    model = bgRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeSource(state = coverHazeState),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .hazeEffect(
                            state = coverHazeState,
                            style = dev.chrisbanes.haze.materials.HazeMaterials.regular()
                        )
                )
            }

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
                                // 为每一次改动添加详尽的中文注释：把详情页 Surface 共用的 hazeState 传给菜单 effect。
                                hazeState = dropdownMenuHazeState,
                                // 为每一次改动添加详尽的中文注释：更多菜单跟随设置页选择在 Material 与 Haze 之间切换。
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
                    windowInsets = WindowInsets.statusBars,
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
            // 为每一次改动添加详尽的中文注释：使用 LocalConfiguration 检测屏幕方向，动态分流横竖屏自适应布局
            val configuration = LocalConfiguration.current
            val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

            if (isLandscape) {
                // 为每一次改动添加详尽的中文注释：使用 smallestScreenWidthDp 判断是否为大屏平板模式，sw >= 600dp 为大屏
                val isLargeScreen = configuration.smallestScreenWidthDp >= 600

                // 为每一次改动添加详尽的中文注释：针对小屏手机横屏与大屏平板横屏进行顶端占位高度的自适应适配。
                // 手机横屏高度较窄，减半以防内容需要滚动；平板高度充裕，完整保留以防视觉上过于靠上。
                val topSpacerHeight = if (isLargeScreen) padding.calculateTopPadding() else padding.calculateTopPadding() / 2

                // 为每一次改动添加详尽的中文注释：将原本左侧圈中的播放控制区、芯片组及物理文件路径抽取为统一的 Composable 局部组件变量，以便于在手机与平板设备横屏模式下进行流式自适应渲染
                val controlPanel = @Composable {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // 为每一次改动添加详尽的中文注释：元数据标签组，采用自适应流式 FlowRow，确保年份、时长及大小自适应编排
                        androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            DetailInfoChip(
                                icon = Icons.Rounded.Event,
                                value = book?.year?.takeIf { it.isNotBlank() } ?: "Unknown",
                                glassEffectMode = glassEffectMode,
                                hazeState = coverHazeState
                            )
                            DetailInfoChip(
                                icon = Icons.Rounded.Timelapse,
                                value = formatTime(book?.totalDurationMs ?: 0L),
                                glassEffectMode = glassEffectMode,
                                hazeState = coverHazeState
                            )
                            if ((book?.totalFileSize ?: 0L) > 0) {
                                DetailInfoChip(
                                    icon = Icons.Rounded.Storage,
                                    value = formatFileSize(book?.totalFileSize ?: 0L),
                                    glassEffectMode = glassEffectMode,
                                    hazeState = coverHazeState
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 为每一次改动添加详尽的中文注释：播放主控制按钮与进度展示
                        val displayProgress = uiState.displayProgressPercent
                        if (isHaze && uiState.isAvailable) {
                            Surface(
                                onClick = {
                                    onPlayPressed()
                                    onPlayClick()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .hazeEffect(state = coverHazeState, style = HazePresets.HazeStyle),
                                shape = RoundedCornerShape(12.dp),
                                color = HazePresets.BackgroundColor,
                                border = HazePresets.Border,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (displayProgress > 0) Icons.Rounded.History else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (displayProgress > 0) "Continue at $displayProgress%" else "Start Listening",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = { 
                                    if (uiState.isAvailable) {
                                        onPlayPressed()
                                        onPlayClick()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = if (uiState.isAvailable) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = if (!uiState.isAvailable) Icons.Rounded.Storage 
                                    else if (displayProgress > 0) Icons.Rounded.History 
                                    else Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (!uiState.isAvailable) "File not found"
                                           else if (displayProgress > 0) "Continue at $displayProgress%" 
                                           else "Start Listening",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                )
                            }
                        }

                        if (uiState.fullSourcePath.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = uiState.fullSourcePath,
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalContentColor.current.copy(alpha = 0.8f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // 与全屏播放器的横大屏排版规范保持高度一致。
                // 两侧占位（sidePadding）缩减至屏幕宽度的 3%（0.03f），双栏间隙（middleSpacing）缩减至 4%（0.04f），
                // 在极力拓宽书籍封面大图、元数据面板与右侧概要信息阅读版面的同时，提供完美的流体一致性视觉比例。
                val screenWidthDp = configuration.screenWidthDp.dp
                val sidePadding = screenWidthDp * 0.04f
                val middleSpacing = screenWidthDp * 0.06f

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        // 为每一次改动添加详尽的中文注释：横屏双栏大屏下不把 Scaffold padding（包含状态栏和 TopBar 的高度）作为 Row 本身的物理 padding，
                        // 这样整个 Row 的绘制区域会充满屏幕，避免滚动时内容在 TopBar 下边缘被直接生硬裁切。
                        // 我们只在 Row 本身保留水平安全边距，将垂直方向的 padding 占位推迟到滚动容器内部通过 Spacer 来完成。
                        .padding(horizontal = sidePadding),
                    horizontalArrangement = Arrangement.spacedBy(middleSpacing)
                ) {
                    // 为每一次改动添加详尽的中文注释：左侧固定书籍元数据面板 (与右侧 1f:1f 等宽分配)。
                    // 为满足最新视觉需求与流畅度体验，将原有的透明 Surface 容器替换为 Box 容器，并彻底去除 .clip(RoundedCornerShape) 裁剪修饰符，从而杜绝在滚动时内容于隐藏的圆角边界发生生硬裁切的问题，使背景与封面等元素滚动时能够完美延伸与过渡。
                    Box(
                        modifier = Modifier
                            // 为每一次改动添加详尽的中文注释：横屏双栏大屏等宽平分设计，左侧元数据面板分配比重为 1f，去除任何形式的边缘裁剪以确保内容滚动时不被截断
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                // 为每一次改动添加详尽的中文注释：由于外部移除了 Row 容器的 padding 限制，这里左栏 Column 的内边距只保留水平方向 and 紧凑的 8.dp，垂直大屏占位全部移交给内部顶底 Spacer 处理
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // 为每一次改动添加详尽的中文注释：使用自适应顶端占位高度，平板大屏保留完整边距以保美观，手机窄屏减半防滑动
                            Spacer(modifier = Modifier.height(topSpacerHeight))
                            // 为每一次改动添加详尽的中文注释：左侧栏内部的封面显示，不再限制固定 150.dp 大小，
                            // 而是根据左侧栏可用宽度的比例自适应渲染，长宽比例统一按比例设为 60% 宽度 (fillMaxWidth(0.6f)) 配合 1:1 宽高比 (aspectRatio(1f))，
                            // 在普通大屏下相当于将长宽各增加一倍（放大至约 300.dp），并完美实现流体自适应伸缩，拥有阴影和精美圆角。
                            Surface(
                                modifier = Modifier
                                    // 为每一次改动添加详尽的中文注释：左侧栏内部的封面显示，不再限制固定 150.dp 大小，
                                    // 而是根据左侧栏可用宽度的比例自适应渲染，长宽比例统一按比例设为 60% 宽度 (fillMaxWidth(0.6f)) 配合 1:1 宽高比 (aspectRatio(1f))，
                                    // 在普通大屏下相当于将长宽各增加一倍（放大至约 300.dp），并完美实现流体自适应伸缩，拥有阴影和精美圆角。
                                    .fillMaxWidth(0.6f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp)),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 4.dp
                            ) {
                                val coverPath = book?.coverPath
                                val coverLastUpdated = book?.lastScannedAt ?: 0L
                                var isImageError by remember(coverPath) { mutableStateOf(false) }

                                if ((coverPath != null) && !isImageError) {
                                    val context = androidx.compose.ui.platform.LocalContext.current
                                    val request = remember(coverPath, coverLastUpdated) {
                                        coil.request.ImageRequest.Builder(context)
                                            .data(File(coverPath))
                                            .memoryCacheKey("$coverPath?t=$coverLastUpdated")
                                            .diskCacheKey("$coverPath?t=$coverLastUpdated")
                                            .crossfade(true)
                                            .build()
                                    }
                                    AsyncImage(
                                        model = request,
                                        contentDescription = "Cover",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                        onError = { state ->
                                            isImageError = true
                                            android.util.Log.e(
                                                "DetailScreen",
                                                "DetailScreen 横屏大图封面加载失败！物理路径: $coverPath, 原因: ${state.result.throwable.message}",
                                                state.result.throwable
                                            )
                                        }
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            modifier = Modifier.size(48.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 为每一次改动添加详尽的中文注释：书籍标题，横屏下字号轻微缩减为 22f 保持紧凑协调
                            SelectableTextView(
                                text = book?.title?.takeIf { it.isNotBlank() } ?: "Unknown",
                                modifier = Modifier.fillMaxWidth(),
                                textColor = MaterialTheme.colorScheme.onSurface,
                                textSizeSp = 22f,
                                lineSpacingExtraSp = 0f,
                                gravity = Gravity.CENTER,
                                typefaceStyle = android.graphics.Typeface.BOLD
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // 为每一次改动添加详尽的中文注释：作者与朗读者信息栏，保持横向并排紧凑显示
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = { 
                                                book?.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Author:$it ") } 
                                            },
                                            onLongClick = { 
                                                if (!book?.author.isNullOrBlank() && !book.author.equals("unknown", true)) {
                                                    infoDialogTitle = "Author"
                                                    infoDialogText = book.author
                                                }
                                            }
                                        )
                                        .padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.author_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = book?.author?.takeIf { it.isNotBlank() } ?: "Unknown",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                VerticalDivider(
                                    modifier = Modifier
                                        .height(20.dp)
                                        .padding(horizontal = 4.dp),
                                    thickness = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(0.3f)
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .combinedClickable(
                                            onClick = {
                                                book?.narrator?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Narrator:$it ") } 
                                            },
                                            onLongClick = { 
                                                if (!book?.narrator.isNullOrBlank() && !book.narrator.equals("unknown", true)) {
                                                    infoDialogTitle = "Narrator"
                                                    infoDialogText = book.narrator
                                                }
                                            }
                                        )
                                        .padding(vertical = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.narrator_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = book?.narrator?.takeIf { it.isNotBlank() } ?: "Unknown",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            // 为每一次改动添加详尽的中文注释：若是大屏模式，则将控制按钮面板留在左侧元数据区底部
                            if (isLargeScreen) {
                                Spacer(modifier = Modifier.height(16.dp))
                                controlPanel()
                            }
                            // 为每一次改动添加详尽的中文注释：在 Column 最下方放置一个与 Scaffold bottomBar / 底部导航栏相同高度的 Spacer，在初始状态下留出安全底距，但滚动时允许内容完全显示且不被屏幕物理边缘切断
                            Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
                        }
                    }

                    // 为每一次改动添加详尽的中文注释：右侧独立滚动的书籍概要简介信息面板 (占比约 60%)
                    Column(
                        modifier = Modifier
                            // 为每一次改动添加详尽的中文注释：横屏双栏大屏等宽平分设计，右侧概要滚动面板分配比重为 1f
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        // 为每一次改动添加详尽的中文注释：使用自适应顶端占位高度，右侧概要区域与左侧对齐，平板保留完整，手机减半
                        Spacer(modifier = Modifier.height(topSpacerHeight))

                        // 为每一次改动添加详尽的中文注释：如果当前处于手机窄屏横屏模式，左侧空间窄，则在此处（右侧顶部）率先渲染播放控制标签面板，实现流畅自适应流式布局
                        if (!isLargeScreen) {
                            controlPanel()
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                        Text(
                            text = stringResource(R.string.summary_label),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        val htmlDescription = book?.description ?: ""
                        val summaryDescription = remember(htmlDescription) {
                            renderDescriptionText(htmlDescription)
                        }
                        
                        SelectableTextView(
                            text = summaryDescription,
                            modifier = Modifier.fillMaxWidth(),
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            textSizeSp = 16f,
                            lineSpacingExtraSp = 4f,
                            firstLineIndentEm = 2f
                        )
                        Spacer(modifier = Modifier.height(32.dp))
                        // 为每一次改动添加详尽的中文注释：在 Column 最下方放置一个与 Scaffold bottomBar / 底部导航栏相同高度的 Spacer，在初始状态下留出安全底距，但滚动时允许内容完全显示
                        Spacer(modifier = Modifier.height(padding.calculateBottomPadding()))
                    }
                }
            } else {
                // 为每一次改动添加详尽的中文注释：现有的竖屏单列 Column 编排布局，并在底部自适应底部栏间距
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 8.dp
                    ) {
                        val coverPath = book?.coverPath
                        val coverLastUpdated = book?.lastScannedAt ?: 0L
                        // 详尽的中文注释：定义用于追踪大封面异步加载是否失败的局部状态。如果加载发生错误（例如文件物理丢失），则异步降级为占位符渲染，彻底消除主线程 File.exists() 磁盘 I/O 同步阻塞 (H-12)
                        var isImageError by remember(coverPath) { mutableStateOf(false) }

                        if ((coverPath != null) && !isImageError) {
                            // 详尽中文注释：使用 LocalContext 构建附带 lastScannedAt 更新戳的 ImageRequest，在底层打破 Coil 对于相同物理文件的本地与内存缓存
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val request = remember(coverPath, coverLastUpdated) {
                                coil.request.ImageRequest.Builder(context)
                                    .data(File(coverPath))
                                    .memoryCacheKey("$coverPath?t=$coverLastUpdated")
                                    .diskCacheKey("$coverPath?t=$coverLastUpdated")
                                    .crossfade(true)
                                    .build()
                            }
                            AsyncImage(
                                model = request,
                                contentDescription = "Cover",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onError = { state ->
                                    isImageError = true
                                    // 详尽中文注释：若大封面加载失败，向 Logcat 打印明确的文件路径与异常根本原因以利于线上诊断
                                    android.util.Log.e(
                                        "DetailScreen",
                                        "DetailScreen 大封面加载失败！物理路径: $coverPath, 原因: ${state.result.throwable.message}",
                                        state.result.throwable
                                    )
                                }
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    SelectableTextView(
                        text = book?.title?.takeIf { it.isNotBlank() } ?: "Unknown",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        textColor = MaterialTheme.colorScheme.onSurface,
                        textSizeSp = 28f,
                        lineSpacingExtraSp = 0f,
                        gravity = Gravity.CENTER,
                        typefaceStyle = android.graphics.Typeface.BOLD
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { 
                                        book?.author?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Author:$it ") } 
                                    },
                                    onLongClick = { 
                                        if (!book?.author.isNullOrBlank() && !book.author.equals("unknown", true)) {
                                            infoDialogTitle = "Author"
                                            infoDialogText = book.author
                                        }
                                    }
                                )
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.author_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = book?.author?.takeIf { it.isNotBlank() } ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        VerticalDivider(
                            modifier = Modifier
                                .height(32.dp)
                                .padding(horizontal = 8.dp),
                            thickness = 2.dp,
                            color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
                        )

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        book?.narrator?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let { onSearchClick("Narrator:$it ") } 
                                    },
                                    onLongClick = { 
                                        if (!book?.narrator.isNullOrBlank() && !book.narrator.equals("unknown", true)) {
                                            infoDialogTitle = "Narrator"
                                            infoDialogText = book.narrator
                                        }
                                    }
                                )
                                .padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.narrator_label),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = book?.narrator?.takeIf { it.isNotBlank() } ?: "Unknown",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // 使用 FlowRow 实现自适应布局：
                    // 1. 空间足够时，3 个 Chip 自动并排成一行。
                    // 2. 空间不足时，自动换行，并保持居中对齐。
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        DetailInfoChip(
                            icon = Icons.Rounded.Event,
                            value = book?.year?.takeIf { it.isNotBlank() } ?: "Unknown",
                            glassEffectMode = glassEffectMode,
                            hazeState = coverHazeState
                        )
                        DetailInfoChip(
                            icon = Icons.Rounded.Timelapse,
                            value = formatTime(book?.totalDurationMs ?: 0L),
                            glassEffectMode = glassEffectMode,
                            hazeState = coverHazeState
                        )
                        if ((book?.totalFileSize ?: 0L) > 0) {
                            DetailInfoChip(
                                icon = Icons.Rounded.Storage,
                                    value = formatFileSize(book?.totalFileSize ?: 0L),
                                    glassEffectMode = glassEffectMode,
                                    hazeState = coverHazeState
                                )
                            }
                        }

                        val displayProgress = uiState.displayProgressPercent

                        if (isHaze && uiState.isAvailable) {
                            Surface(
                                onClick = {
                                    onPlayPressed()
                                    onPlayClick()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .hazeEffect(state = coverHazeState, style = HazePresets.HazeStyle),
                                shape = RoundedCornerShape(16.dp),
                                color = HazePresets.BackgroundColor,
                                border = HazePresets.Border,
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = if (displayProgress > 0) Icons.Rounded.History else Icons.Rounded.PlayArrow,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (displayProgress > 0) "Continue at $displayProgress%" else "Start Listening",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Button(
                                onClick = { 
                                    if (uiState.isAvailable) {
                                        onPlayPressed()
                                        onPlayClick()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = if (uiState.isAvailable) {
                                    ButtonDefaults.buttonColors()
                                } else {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = if (!uiState.isAvailable) Icons.Rounded.Storage 
                                    else if (displayProgress > 0) Icons.Rounded.History 
                                    else Icons.Rounded.PlayArrow,
                                    contentDescription = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (!uiState.isAvailable) "File not found"
                                           else if (displayProgress > 0) "Continue at $displayProgress%" 
                                           else "Start Listening",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }

                        if (uiState.fullSourcePath.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = uiState.fullSourcePath,
                                style = MaterialTheme.typography.labelMedium,
                                color = LocalContentColor.current.copy(alpha = 0.8f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        } else {
                            Spacer(modifier = Modifier.height(32.dp))
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.summary_label),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            val htmlDescription = book?.description ?: ""
                            val summaryDescription = remember(htmlDescription) {
                                renderDescriptionText(htmlDescription)
                            }
                            
                            SelectableTextView(
                                text = summaryDescription,
                                modifier = Modifier.fillMaxWidth(),
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                textSizeSp = 16f,
                                lineSpacingExtraSp = 4f,
                                firstLineIndentEm = 2f
                            )
                        }

                        Spacer(modifier = Modifier.height(100.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
                    }
            }
        }
    }
}

    if (infoDialogText != null) {
        if (isHaze) {
            // 为每一次改动添加详尽的中文注释：
            // 在 Haze 磨砂玻璃模式下，将详情页 info 弹窗重构为基于封面 coverHazeState 的 BlurDialog。
            // 点击外部或点击 OK 均能顺畅退出，且毛玻璃视觉同源。
            BlurDialog(
                onDismissRequest = {
                    infoDialogText = null
                    infoDialogTitle = null
                },
                hazeState = coverHazeState,
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

// HTML detection stays conservative because HtmlCompat collapses plain-text newline characters.
private val htmlDescriptionPattern = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^>]*)?/?>""")

private fun renderDescriptionText(rawDescription: String): CharSequence {
    // Normalize CRLF/CR line endings so plain txt descriptions render consistently in TextView.
    val normalizedDescription = rawDescription.replace("\r\n", "\n").replace('\r', '\n')
    return if (htmlDescriptionPattern.containsMatchIn(normalizedDescription)) {
        // Existing HTML descriptions still use the Android parser for tags and entities.
        HtmlCompat.fromHtml(normalizedDescription, HtmlCompat.FROM_HTML_MODE_COMPACT)
    } else {
        normalizedDescription
    }
}

@Composable
private fun SelectableTextView(
    text: CharSequence,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    textSizeSp: Float = 16f,
    lineSpacingExtraSp: Float = 0f,
    firstLineIndentEm: Float = 0f,
    gravity: Int = Gravity.START,
    typefaceStyle: Int = android.graphics.Typeface.NORMAL
) {
    val textColorInt = textColor.toArgb()
    val density = LocalDensity.current
    val lineSpacingExtraPx = with(density) { lineSpacingExtraSp.sp.toPx() }
    val firstLineIndentPx = with(density) {
        (textSizeSp * firstLineIndentEm).sp.toPx().toInt()
    }
    val displayText = remember(text, firstLineIndentPx) {
        if (firstLineIndentPx > 0) {
            SpannableStringBuilder(text).apply {
                setSpan(
                    LeadingMarginSpan.Standard(firstLineIndentPx, 0),
                    0,
                    length,
                    0
                )
            }
        } else {
            text
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            TextView(context).apply {
                setTextIsSelectable(true)
                background = null
                setPadding(0, 0, 0, 0)
                setHorizontallyScrolling(false)
                customSelectionActionModeCallback = ProcessTextMenuCallback(this)
            }
        },
        update = { tv ->
            if (tv.text?.toString() != displayText.toString()) {
                tv.text = displayText
            }
            tv.setTextColor(textColorInt)
            tv.textSize = textSizeSp
            tv.gravity = gravity
            tv.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                typefaceStyle
            )
            tv.setLineSpacing(lineSpacingExtraPx, 1.0f)
        }
    )
}

private class ProcessTextMenuCallback(
    private val textView: TextView
) : ActionMode.Callback {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu) = true
    override fun onDestroyActionMode(mode: ActionMode) = Unit

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val pm = textView.context.packageManager
        val baseIntent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(baseIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(baseIntent, 0)
        }
        activities.forEachIndexed { index, ri ->
            val label = ri.loadLabel(pm).toString()
            if (menu.findItem(label.hashCode()) == null) {
                menu.add(Menu.NONE, label.hashCode(), 100 + index, label)
                    .setIntent(
                        Intent(baseIntent)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name)
                            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                    )
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            }
        }
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val intent = item.intent ?: return false
        val s = textView.selectionStart.coerceAtLeast(0)
        val e = textView.selectionEnd.coerceAtLeast(0)
        val text = textView.text.subSequence(minOf(s, e), maxOf(s, e)).toString()
        if (text.isEmpty()) return false
        intent.putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        return try {
            textView.context.startActivity(intent)
            mode.finish()
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}



/**
 * 为每一次改动添加详尽的中文注释：
 * 重构后的详情元数据卡片 (DetailInfoChip)。
 * 完美支持在 Haze 模式下动态应用“高雅白羽雾化”设计规范，同时在传统不透明模式下退回为原生 Material3 经典微光描边，确保零额外开销。
 */
@OptIn(dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi::class)
@Composable
fun DetailInfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    modifier: Modifier = Modifier,
    // 为每一次改动添加详尽的中文注释：传入全局玻璃效果模式，默认值设为已存在的 GlassEffectMode.Material
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    // 为每一次改动添加详尽的中文注释：传入全局 Haze 背景模糊状态
    hazeState: HazeState? = null
) {
    val isHaze = glassEffectMode == GlassEffectMode.Haze && hazeState != null

    // 使用自定义 Surface 替代 SuggestionChip，以获得更紧凑的间距且不带有额外的点击透明区域
    Surface(
        modifier = modifier
            .then(
                if (isHaze) {
                    Modifier
                        // 首先在 Modifier 链最前端裁剪 12.dp 圆角，杜绝毛玻璃直角溢出穿帮
                        .clip(RoundedCornerShape(12.dp))
                        // 挂载 Haze 模糊并应用统一的 HazeStyle 材质
                        .hazeEffect(state = hazeState, style = HazePresets.HazeStyle)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(12.dp),
        border = if (isHaze) {
            // Haze 模式下引用统一的 0.5.dp 微光轮廓白边描边
            HazePresets.Border
        } else {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = LocalContentColor.current.copy(alpha = 0.5f)
            )
        },
        color = if (isHaze) {
            // Haze 模式下使用极低饱和度 15% 透明乳白蒙版
            HazePresets.BackgroundColor
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = LocalContentColor.current
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = LocalContentColor.current,
                maxLines = 1,
                overflow = TextOverflow.Visible,
                softWrap = false
            )
        }
    }
}

// 为每一次改动添加详尽的中文注释：为 DetailScreenPreview 增加多重自适应 Preview。
// 1. Phone Portrait: 手机常规竖屏模式。
// 2. Phone Landscape: 手机横屏窄高模式 (smallest width < 600dp)，用来预览我们刚做好的播放控制流转至右侧顶部的流式自适应新排版。
// 3. Tablet Landscape: 平板/折叠屏大屏横屏模式 (smallest width >= 600dp)，用来预览原有的左侧经典双栏卡片式排版。
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
                        title = "In the M   egachurch",
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
