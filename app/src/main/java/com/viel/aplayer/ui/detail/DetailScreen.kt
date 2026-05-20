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
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.viel.aplayer.ui.common.formatFileSize
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.theme.APlayerTheme



@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    uiState: DetailUiState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlayClick: () -> Unit = {},
    onMoreClick: () -> Unit = {},
    onSearchClick: (String) -> Unit = {},
) {
    val bookWithProgress = uiState.book
    val book = bookWithProgress?.book
    // 详尽中文注释：定义预测性返回手势的激活状态和手势进度值（0f 到 1f 之间）
    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableStateOf(0f) }
    var infoDialogTitle by remember { mutableStateOf<String?>(null) }
    var infoDialogText by remember { mutableStateOf<String?>(null) }

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
    val backgroundBrush by remember(animatedBgColor, bgColor) {
        derivedStateOf {
            Brush.verticalGradient(
                colors = listOf(
                    animatedBgColor.copy(alpha = 0.9f),
                    bgColor.copy(alpha = 0.95f)
                )
            )
        }
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
            .background(backgroundBrush),
        color = Color.Transparent
    ) {
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
                        IconButton(onClick = onMoreClick) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = stringResource(R.string.more_content_description)
                            )
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier
                        .size(260.dp)
                        .clip(RoundedCornerShape(24.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp
                ) {
                    val coverPath = book?.coverPath
                    val coverLastUpdated = book?.lastScannedAt ?: 0L
                    if ((coverPath != null) && File(coverPath).exists()) {
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
                        value = book?.year?.takeIf { it.isNotBlank() } ?: "Unknown"
                    )
                    DetailInfoChip(
                        icon = Icons.Rounded.Timelapse,
                        value = formatTime(book?.totalDurationMs ?: 0L)
                    )
                    DetailInfoChip(
                        icon = Icons.Rounded.Storage,
                        value = if ((book?.totalFileSize ?: 0L) > 0) formatFileSize(book!!.totalFileSize) else "Unknown"
                    )
                }

                Button(
                    onClick = { if (uiState.isAvailable) onPlayClick() },
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
                        else if (uiState.progressPercent > 0) Icons.Rounded.History 
                        else Icons.Rounded.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (!uiState.isAvailable) "File not found"
                               else if (uiState.progressPercent > 0) "Continue at ${uiState.progressPercent}%" 
                               else "Start Listening",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Spacer(modifier = Modifier.height(32.dp))

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
                        // Plain txt sidecar descriptions must keep literal line breaks; only real HTML goes through HtmlCompat.
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

    if (infoDialogText != null) {
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
                SelectableTextView(
                    text = infoDialogText!!,
                    modifier = Modifier.fillMaxWidth(),
                    textColor = MaterialTheme.colorScheme.onSurface,
                    textSizeSp = 16f,
                    lineSpacingExtraSp = 4f
                )
            }
        )
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

@Composable
fun DetailInfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    modifier: Modifier = Modifier
) {
    // 使用自定义布局替代 SuggestionChip，以获得更紧凑的间距且不带有额外的点击透明区域
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = LocalContentColor.current.copy(alpha = 0.5f)
        ),
        color = Color.Transparent
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

@Preview(showBackground = true, apiLevel = 36)
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
            onBackClick = {}
        )
    }
}