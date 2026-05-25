package com.viel.aplayer.ui.edit

import android.content.res.Configuration
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.viel.aplayer.ui.theme.APlayerTheme
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.library.vfs.VfsExternalInputReader
import com.viel.aplayer.ui.common.PlayerCover
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import java.io.File
import androidx.core.graphics.scale

/**
 * 书籍编辑屏幕的 Stateless 渲染 Composable 视图。
 * 此组件已被彻底解耦，它不再直接持有 ViewModel，
 * 而是接收外部传入的 BookEntity 实体以及当用户修改并触发保存时的 onSave 回调函数。
 * 它仅保留编辑页面内的高频高内聚的临时 UI 交互状态（如输入文本及封面临时文件物理路径）。
 * 支持磨砂玻璃视效以及向原生 Material 3 不透明样式的高性能无损回退。
 *
 * @param book 要编辑的书籍实体，为 null 时将显示加载圈
 * @param onNavigationBack 当用户点击返回按钮或者手势返回时执行的回调，用于优雅清理临时文件并退出
 * @param onSave 当用户确认修改并点击保存按钮时的业务回调，向上派发完整的修改元数据及新封面临时路径
 * @param glassEffectMode 系统当前的玻璃毛玻璃特效配置模式
 * @param modifier 外部修饰符
 * @param detailBackdrop 详情页渲染画面所输出的物理层级模糊采样源
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    book: BookEntity?,
    onNavigationBack: () -> Unit,
    onSave: (title: String, author: String, narrator: String, year: String, description: String, newCoverPath: String?) -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    detailBackdrop: LayerBackdrop? = null
) {
    // 获取当前的 Android 上下文环境，用于文件操作和内容解析器调用
    val context = LocalContext.current

    // 声明临时封面绝对路径状态，用以持有用户选择并按最短边居中裁剪为正方形后的临时文件路径
    var tempCoverPath by remember { mutableStateOf<String?>(null) }

    // 构建系统图库选择器 Launcher，当用户选中图片后，在 cache 目录生成 temp 文件，调用 cropToSquareAndSave 裁剪并刷新预览路径
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: android.net.Uri? ->
        uri?.let { inputUri ->
            book?.let { currentBook ->
                val tempFile = File(context.cacheDir, "temp_cover_${currentBook.id}_${System.currentTimeMillis()}.jpg")
                if (cropToSquareAndSave(context, inputUri, tempFile)) {
                    // 若之前已存在另一个临时封面，先物理删除旧的临时文件以防止垃圾堆积
                    tempCoverPath?.let { oldPath ->
                        val oldFile = File(oldPath)
                        if (oldFile.exists()) {
                            oldFile.delete()
                        }
                    }
                    tempCoverPath = tempFile.absolutePath
                }
            }
        }
    }

    // 统一封装的退出/取消返回函数，物理清理裁剪后产生的 temp 临时文件，确保零文件垃圾残留
    val handleCancel = remember(tempCoverPath) {
        {
            tempCoverPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
            onNavigationBack()
        }
    }

    // 定义预测性返回手势在书籍编辑页的激活状态与百分比进度，用以驱动视觉过渡动画
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // 拦截并接管系统预测性返回手势，收集返回进度以渲染平移动画，点击返回时物理删除临时文件并优雅退出
    androidx.activity.compose.PredictiveBackHandler(enabled = book != null) { progressFlow ->
        try {
            // 收集返回进度事件以驱动视觉过渡动画
            progressFlow.collect { backEvent ->
                isPredictiveBackActive = true
                predictiveBackProgress = backEvent.progress
            }
            handleCancel()
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // 用户中途取消
        } finally {
            isPredictiveBackActive = false
            predictiveBackProgress = 0f
        }
    }

    // 使用 DisposableEffect 守住防线，当编辑页面彻底销毁或退出组合树时，防御性物理清除残留的 temp 临时文件
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            tempCoverPath?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    // 判断是否开启 miuix-blur 效果且存在有效的背景模糊采样源（即 Detail 页面）。
    val isBlur = glassEffectMode == GlassEffectMode.MiuixBlur && detailBackdrop != null

    val animatedBgColor = MaterialTheme.colorScheme.surfaceVariant
    val bgColor = MaterialTheme.colorScheme.background

    // 定义背景调色层，isBlur 模式下采用高透光的半透明色以便看清底部的详情页折射
    val backgroundBrush = remember(animatedBgColor, bgColor, isBlur) {
        if (isBlur) {
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

    val isDark = androidx.compose.foundation.isSystemInDarkTheme()
    val density = LocalDensity.current
    val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

    // 利用 View 层级的 RootWindowInsets 获取系统最精确的物理圆角半径，
    // 以便编辑页在磨砂玻璃悬浮展开时，顶部圆角切边能与系统屏幕外轮廓完全共形契合，极具高级质感。
    val view = LocalView.current
    val systemCornerRadius = remember(view) {
        val insets = view.rootWindowInsets
        insets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius ?: 0
    }
    val cornerRadiusDp = with(density) { systemCornerRadius.toDp().coerceAtLeast(24.dp) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 当处于预测性返回拖拽状态时，顺应编辑页面向下滑动退出的特征，
                // 让卡片整体随返回手势进度向下平移，并伴随淡出效果（不包含缩放，对齐详情页与播放器页的最新去缩放设计）。
                if (isPredictiveBackActive) {
                    translationY = predictiveBackProgress * maxPredictiveTranslationY
                    alpha = 1f - predictiveBackProgress * 0.3f
                }
            }
            .clip(RoundedCornerShape(topStart = cornerRadiusDp, topEnd = cornerRadiusDp))
            .then(
                if (isBlur) {
                    // 
                    // 背景彻底重构为 texture blur pured regular 极致质感磨砂毛玻璃背景。
                    // 1. 使用 textureBlur 代替原本的 drawBackdrop 绘制。
                    // 2. 将 blurRadius 设为 60f (regular -> 适中标准高保真高斯模糊半径)。
                    // 3. 将 noiseCoefficient 设为 0.05f (texture -> 增加拟真细腻的磨砂噪点质感)。
                    // 4. colors 参数中采用单色高保真混合层 (pured -> 无杂质单色极简底色)，亮暗系统自适应。
                    Modifier.textureBlur(
                        backdrop = detailBackdrop,
                        shape = RectangleShape,
                        blurRadius = 60f,
                        noiseCoefficient = 0.05f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(
                                    color = if (isDark) Color.Black.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.85f),
                                    mode = BlurBlendMode.SrcOver
                                )
                            )
                        )
                    )
                } else {
                    Modifier
                }
            )
            .background(if (isBlur) Color.Transparent else MaterialTheme.colorScheme.background),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 使用 Modifier.then 链式条件判定背景，避免在 if-else 分支中混合 Color 与 Brush 导致 Kotlin 编译器类型推导为 Any 发生错误
                .then(
                    if (isBlur) {
                        Modifier.background(Color.Transparent)
                    } else {
                        Modifier.background(backgroundBrush)
                    }
                )
        ) {
            Scaffold(
                // 允许背景和 TopAppBar 沉浸式通铺到屏幕顶端及底端，通过 contentWindowInsets 托管给系统进行统一物理规避
                modifier = Modifier.fillMaxSize(),
                contentWindowInsets = WindowInsets.safeDrawing,
                topBar = {
                    TopAppBar(
                        modifier = Modifier,
                        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
                        title = {
                            Text(
                                text = "修改书籍信息",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        navigationIcon = {
                            IconButton(
                                onClick = handleCancel, // 点击返回时通过 handleCancel 清理临时图片并退出
                                modifier = Modifier
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = "返回"
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = MaterialTheme.colorScheme.onBackground
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { paddingValues ->
                if (book == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    // 高频输入框状态隔离，完全避免由 ViewModel 高频刷新带来的重组开销
                    var title by remember(book) { mutableStateOf(book.title) }
                    var author by remember(book) { mutableStateOf(book.author) }
                    var narrator by remember(book) { mutableStateOf(book.narrator) }
                    var year by remember(book) { mutableStateOf(book.year) }
                    var description by remember(book) { mutableStateOf(book.description) }

                    // 在 miuix-blur 磨砂玻璃下，配置输入框背景为轻微半透融合色，极大增强透光的质感层级
                    val textFieldColors = if (isBlur) {
                        OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f),
                            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    }

                    // 使用 LocalConfiguration 判断当前是否处于横屏或者平板大屏状态以流转响应式双栏排版
                    val configuration = LocalConfiguration.current
                    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                    val isTablet = configuration.smallestScreenWidthDp >= 600
                    val useLandscapeLayout = isLandscape || isTablet

                    // 声明局部 Composable 闭包函数对输入框进行高内聚解耦，避免在横竖屏双栏布局中编写重复代码
                    val titleField = @Composable {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("书名") },
                            placeholder = { Text("请输入书名") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    val authorField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = author,
                            onValueChange = { author = it },
                            label = { Text("作者") },
                            placeholder = { Text("请输入作者") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val narratorField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = narrator,
                            onValueChange = { narrator = it },
                            label = { Text("讲述人") },
                            placeholder = { Text("请输入讲述人") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val yearField = @Composable { modifier: Modifier ->
                        OutlinedTextField(
                            value = year,
                            onValueChange = { year = it },
                            label = { Text("年份") },
                            placeholder = { Text("请输入出版年份") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = modifier
                        )
                    }

                    val descriptionField = @Composable {
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("简介描述") },
                            placeholder = { Text("请输入书籍简介") },
                            minLines = 4,
                            maxLines = 8,
                            shape = RoundedCornerShape(12.dp),
                            colors = textFieldColors,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 抽离出统一精致、高度自适应的“更换封面”按钮
                    val changeCoverButton = @Composable {
                        androidx.compose.material3.OutlinedButton(
                            onClick = { imagePickerLauncher.launch("image/*") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.primary
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = "更换封面"
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "更换封面",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    val saveButton = @Composable {
                        // 保存修改按钮
                        if (isBlur) {
                            // 
                            // 在 miuix-blur 模式下，将保存按钮重构为与详情页播放按钮同样高品质的磨砂玻璃 Surface。
                            // 共享底部的 detailBackdrop，底色采用半透主色 (0.12f) 并用 1.dp 精细主色边框勾勒 (0.25f)。
                            Surface(
                                onClick = {
                                    val finalTitle = title.ifBlank { "Unknown" }
                                    // 触发保存回调，将所有被改变的元数据属性向上流转给有状态的 Overlay 容器处理
                                    onSave(
                                        finalTitle,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        tempCoverPath
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    // 在此处使用 drawBackdrop 渲染按钮的毛玻璃背景，使其与主背景高度和谐与视觉统一
                                    .then(
                                        Modifier.drawBackdrop(
                                            backdrop = detailBackdrop,
                                            shape = { RoundedCornerShape(16.dp) },
                                            effects = { blur(20f) }
                                        )
                                    ),
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                border = androidx.compose.foundation.BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                ),
                                contentColor = MaterialTheme.colorScheme.primary
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = "保存"
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            // 
                            // 在 Material 模式下优雅无缝回退到高能效、不带高斯模糊的 Material 3 实色 Button。
                            Button(
                                onClick = {
                                    val finalTitle = title.ifBlank { "Unknown" }
                                    // 触发保存回调，向上派发完整的修改元数据
                                    onSave(
                                        finalTitle,
                                        author,
                                        narrator,
                                        year,
                                        description,
                                        tempCoverPath
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Save,
                                        contentDescription = "保存",
                                        tint = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "保存书籍信息",
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues)
                            .imePadding()
                            .verticalScroll(rememberScrollState())
                            // 借助 consumeWindowInsets 精确规避双重 padding，仅增加 24.dp 的纯界面视觉呼吸留白边距
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        if (useLandscapeLayout) {
                            // 横大屏模式下使用双栏布局排布
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(24.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                // 左侧栏使用 Column 承载 PlayerCover 和更换封面按钮，宽度限制在 280dp 以保重叠度完美
                                Column(
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .widthIn(max = 280.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    PlayerCover(
                                        coverPath = tempCoverPath ?: book.coverPath,
                                        isPlaying = false,
                                        coverLastUpdated = book.lastScannedAt,
                                        onAdjustVolume = {},
                                        onNextChapter = {},
                                        onPreviousChapter = {},
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(24.dp)),
                                        sizeRatio = 1.0f,
                                        gesturesEnabled = false
                                    )

                                    changeCoverButton()
                                }

                                // 右侧列容器放置所有的输入框，使其单独成行并彻底铺满容器宽
                                Column(
                                    modifier = Modifier.weight(1.8f),
                                    verticalArrangement = Arrangement.spacedBy(20.dp)
                                ) {
                                    titleField()
                                    authorField(Modifier.fillMaxWidth())
                                    narratorField(Modifier.fillMaxWidth())
                                    yearField(Modifier.fillMaxWidth())
                                    descriptionField()
                                    Spacer(modifier = Modifier.height(8.dp))
                                    saveButton()
                                }
                            }
                        } else {
                            // 常规手机竖屏模式，自上而下纵向布局，使用 PlayerCover 渲染封面并限制纵横比
                            PlayerCover(
                                coverPath = tempCoverPath ?: book.coverPath,
                                isPlaying = false,
                                coverLastUpdated = book.lastScannedAt,
                                onAdjustVolume = {},
                                onNextChapter = {},
                                onPreviousChapter = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(24.dp)),
                                sizeRatio = 1.0f,
                                gesturesEnabled = false
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            changeCoverButton()

                            Spacer(modifier = Modifier.height(8.dp))

                            titleField()
                            authorField(Modifier.fillMaxWidth())
                            narratorField(Modifier.fillMaxWidth())
                            yearField(Modifier.fillMaxWidth())
                            descriptionField()
                            Spacer(modifier = Modifier.height(16.dp))
                            saveButton()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 从系统 Uri 中解码图片，按照最短边居中裁剪为正方形，并按需等比缩放到高清紧凑尺寸（如 800x800），最后以 90% 质量的 JPEG 格式输出到目标临时物理文件中。
 * 此为私有辅助工具函数，能妥善应对高分辨率长图，防范 OutOfMemoryError 溢出，并严谨地显式回收已分配的内存。
 */
private fun cropToSquareAndSave(
    context: android.content.Context,
    inputUri: android.net.Uri,
    outputFile: File
): Boolean {
    // 编辑页只通过 VFS 外部输入读取器打开用户选择的封面 Uri，不直接访问 ContentResolver 文件流。
    val externalInputReader = VfsExternalInputReader(context)
    var inputStream: java.io.InputStream? = null
    try {
        inputStream = externalInputReader.openInputStream(inputUri) ?: return false
        // 1. 先通过 inJustDecodeBounds 获取图片原始宽和高以计算采样率，防御性防范高分辨率大图导致内存溢出 (OOM)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(inputStream, null, options)
        inputStream.close()

        // 2. 重新开启输入流，以便真正将 Bitmap 数据载入堆内存
        inputStream = externalInputReader.openInputStream(inputUri) ?: return false
        val maxDim = maxOf(options.outWidth, options.outHeight)
        val decodeOptions = android.graphics.BitmapFactory.Options()
        // 若图片极度庞大（宽或高大于 2000 像素），则采用二次采样以安全无险地载入内存
        if (maxDim > 2000) {
            decodeOptions.inSampleSize = 2
        }
        val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return false
        inputStream.close()

        // 3. 按最短边居中裁剪为正方形
        val width = bitmap.width
        val height = bitmap.height
        val size = minOf(width, height)
        val x = (width - size) / 2
        val y = (height - size) / 2

        val croppedBitmap = android.graphics.Bitmap.createBitmap(bitmap, x, y, size, size)

        // 4. 将正方形裁剪图等比缩放至 800x800 像素的高清紧凑分辨率，以节约磁盘存储开销并维持极致清晰度
        val targetResolution = 800
        val finalBitmap = if (size > targetResolution) {
            croppedBitmap.scale(targetResolution, targetResolution)
        } else {
            croppedBitmap
        }

        // 5. 物理写入目标临时文件，采用 JPEG 格式与 90% 压缩品质
        outputFile.parentFile?.mkdirs()
        java.io.FileOutputStream(outputFile).use { out ->
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        }

        // 6. 严谨清理并显式回收已分配的 Bitmap 内存堆空间，防堵 GC 漂移引起的 Native 物理内存泄露
        if (finalBitmap != croppedBitmap) {
            finalBitmap.recycle()
        }
        if (croppedBitmap != bitmap) {
            croppedBitmap.recycle()
        }
        bitmap.recycle()
        return true
    } catch (e: Exception) {
        android.util.Log.e("EditBookScreen", "居中裁剪正方形封面失败，原因: ", e)
        try { inputStream?.close() } catch (_: Exception) {}
        return false
    }
}

/**
 * 为无状态的 EditBookScreen UI 组件添加多重自适应 Preview 组合。
 * 支持三种不同的设备形态及系统主题模式的集成化可视化预览：
 * 1. Phone Portrait: 手机常规竖屏模式。
 * 2. Phone Landscape: 手机横屏窄高自适应排版。
 * 3. Tablet Landscape: 平板/大屏大宽度双栏最佳排版。
 */
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
fun EditBookScreenPreview() {
    APlayerTheme {
        EditBookScreen(
            book = BookEntity(
                id = "preview-id",
                rootId = "preview-root",
                sourceType = "SINGLE_AUDIO",
                title = "In the Megachurch",
                author = "Ryo Asai",
                narrator = "Narrator A",
                totalDurationMs = 36000L,
                year = "2023",
                description = "A premium preview description of this beautifully designed audiobook widget, showcasing full detail and rich metadata."
            ),
            onNavigationBack = {},
            onSave = { _, _, _, _, _, _ -> },
            glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
        )
    }
}
