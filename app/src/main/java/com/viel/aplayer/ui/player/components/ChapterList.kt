package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
// 详尽中文注释：迁移至 BlurModalBottomSheet，启用原生 Window 背景模糊（API 31+）
import com.viel.aplayer.ui.common.BlurModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.UUID
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.media.ChapterTimeline
import com.viel.aplayer.ui.common.formatTime
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.ui.player.BookMetadataState
import com.viel.aplayer.ui.player.PlayerActions
import com.viel.aplayer.ui.player.PlayerViewModel
import com.viel.aplayer.ui.settings.PlayerSettingsState

// 详尽的中文注释：
// 5. 章节列表弹窗有状态局部隔间 ChapterListSheetStateful
// 本隔间仅当弹窗真正可见（isVisible == true）时才订阅高频流以获取进度和高亮，
// 在弹窗关闭（isVisible == false）时整个有状态隔间不执行内部订阅代码，完全停摆，避免无意义的高频空耗。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheetStateful(
    viewModel: PlayerViewModel,
    metadata: BookMetadataState,
    settings: PlayerSettingsState,
    actions: PlayerActions,
    sheetState: SheetState,
    // 为每一次改动添加详尽的中文注释：将失效的旧模糊状态类型替换为 miuix-blur 核心的 LayerBackdrop，保证顶层宿主组件的类型传递一致性
    backdrop: LayerBackdrop,
    // 为每一次改动添加详尽的中文注释：接收全局玻璃效果模式并传给实际的 ChapterListSheet。
    glassEffectMode: GlassEffectMode
) {
    if (settings.isChapterListVisible) {
        val isPreview = LocalInspectionMode.current
        val progressState = if (isPreview) {
            PlayerViewModel.PlaybackProgressViewState(
                elapsedMs = 120000L,
                durationMs = 360000L,
                isChapterProgressMode = false
            )
        } else {
            viewModel.playbackProgressState.collectAsStateWithLifecycle().value
        }
        val currentChapter = remember(progressState.elapsedMs, metadata.chapters) {
            ChapterTimeline.currentChapter(metadata.chapters, progressState.elapsedMs)
        }
        ChapterListSheet(
            isVisible = true,
            chapters = metadata.chapters,
            currentChapter = currentChapter,
            totalDuration = progressState.durationMs,
            onDismissRequest = actions.content.onDismissChapterList,
            onChapterClick = { pos ->
                actions.playback.onSeek(pos, true)
                actions.content.onDismissChapterList()
            },
            sheetState = sheetState,
            // 为每一次改动添加详尽的中文注释：将播放器背景 source 共用的 backdrop 传入章节列表面板 effect。
            backdrop = backdrop,
            // 为每一次改动添加详尽的中文注释：Material 模式会让章节列表回到原生 BottomSheet 容器层次。
            glassEffectMode = glassEffectMode
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterListSheet(
    isVisible: Boolean,
    chapters: List<ChapterEntity>,
    currentChapter: ChapterEntity?,
    totalDuration: Long,
    onDismissRequest: () -> Unit,
    onChapterClick: (Long) -> Unit,
    backdrop: LayerBackdrop,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由播放页从设置状态显式传入，章节 BottomSheet 不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
) {
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val initialIndex = remember(chapters, currentChapter) {
        val index = chapters.indexOfFirst { it.id == currentChapter?.id }
        (index - 2).coerceAtLeast(0)
    }

    val listState = remember(isVisible) {
        LazyListState(firstVisibleItemIndex = initialIndex)
    }

    var canCalculateOffset by remember(isVisible) { mutableStateOf(false) }
    LaunchedEffect(isVisible) {
        if (isVisible) {
            snapshotFlow { sheetState.currentValue == sheetState.targetValue }
                .filter { it }
                .first()
            canCalculateOffset = true
        } else {
            canCalculateOffset = false
        }
    }

    val dynamicSpacerHeight by remember(sheetState, canCalculateOffset) {
        derivedStateOf {
            val halfHeight = with(density) { (windowInfo.containerSize.height / 2).toDp() }
            if (!canCalculateOffset || sheetState.targetValue == SheetValue.Hidden) {
                halfHeight
            } else {
                try {
                    val offsetPx = sheetState.requireOffset()
                    with(density) { offsetPx.toDp() }
                } catch (_: IllegalStateException) {
                    halfHeight
                }
            }
        }
    }

    if (isVisible) {
        if (LocalInspectionMode.current) {
            ChapterListContent(
                chapters = chapters,
                currentChapter = currentChapter,
                totalDuration = totalDuration,
                onChapterClick = onChapterClick,
                listState = listState,
                // 为每一次改动添加详尽的中文注释：Preview/Inspection 路径同样传入模式，避免调试渲染和真实 BottomSheet 选中态不一致。
                glassEffectMode = glassEffectMode
            )
        } else {
            // 详尽中文注释：使用 miuix-blur 版 BlurModalBottomSheet 替代原 Window blur 版封装，
            // backdrop 来自播放器 Surface 的采样源，因此章节列表能采样播放器画面形成毛玻璃。
            // 同时保留所有原有参数：sheetState、自定义拖拽把手、零内边距等。
            BlurModalBottomSheet(
                onDismissRequest = onDismissRequest,
                sheetState = sheetState,
                // 为每一次改动添加详尽的中文注释：传递播放器背景共用的 LayerBackdrop 采样源，以在 BottomSheet 浮层渲染高精度的磨砂玻璃效果。
                backdrop = backdrop,
                // 为每一次改动添加详尽的中文注释：把 Material/miuix-blur 选择传入通用 BottomSheet 封装，统一控制内部 drawBackdrop 是否启用。
                glassEffectMode = glassEffectMode,
                // 为每一次改动添加详尽的中文注释：章节列表的模糊参数由 BlurModalBottomSheet 直接配置，不再在调用处单独传半径。
                tonalElevation = 8.dp,
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                dragHandle = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.statusBarsPadding())
                        BottomSheetDefaults.DragHandle()
                    }
                }
            ) {
                ChapterListContent(
                    chapters = chapters,
                    currentChapter = currentChapter,
                    totalDuration = totalDuration,
                    onChapterClick = { pos ->
                        scope.launch {
                            sheetState.hide()
                            onChapterClick(pos)
                            onDismissRequest()
                        }
                    },
                    listState = listState,
                    bottomSpacerHeight = dynamicSpacerHeight,
                    // 为每一次改动添加详尽的中文注释：章节列表内容根据 Material/miuix-blur 模式选择不同的当前章节高亮样式。
                    glassEffectMode = glassEffectMode
                )
            }
        }
    }
}

@Composable
fun ChapterListContent(
    chapters: List<ChapterEntity>,
    currentChapter: ChapterEntity?,
    totalDuration: Long,
    onChapterClick: (Long) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    bottomSpacerHeight: Dp = 0.dp,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由章节 BottomSheet 显式传入，列表内容不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode
) {

    Column(
        modifier = modifier
            .fillMaxWidth()            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "Chapters",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        if (chapters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("No chapters available", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f)
            ) {
                itemsIndexed(
                    items = chapters,
                    key = { _, chapter -> chapter.id }
                ) { index, chapter ->
                    val isCurrent = chapter.id == currentChapter?.id
                    // 为每一次改动添加详尽的中文注释：MiuixBlur 模式使用更轻的圆角玻璃高亮，Material 模式保留更明确的 primaryContainer 选中反馈。修改引用至 MiuixBlur。
                    val selectedContainerColor = when (glassEffectMode) {
                        GlassEffectMode.MiuixBlur -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.22f)
                        GlassEffectMode.Material -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f)
                    }
                    // 为每一次改动添加详尽的中文注释：MiuixBlur 模式用细描边替代大面积蓝色块，让选中态在毛玻璃上更精致、更不抢背景焦点。修改引用至 MiuixBlur
                    val selectedBorderModifier = if (isCurrent && glassEffectMode == GlassEffectMode.MiuixBlur) {
                        Modifier.border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            shape = RoundedCornerShape(8.dp)
                        )
                    } else {
                        Modifier
                    }
                    // 为每一次改动添加详尽的中文注释：统一给当前章节行增加圆角，避免 miuix-blur 背景上出现生硬的整块矩形高亮。
                    val rowShape = RoundedCornerShape(8.dp)
                    ListItem(
                        headlineContent = {
                            Text(
                                text = chapter.title,
                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        },
                        leadingContent = {
                            Text(
                                text = (index + 1).toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        trailingContent = {
                            // 单文件内嵌章节时优先用相邻起点推导时长，避免裸 durationMs 显示不一致。
                            Text(
                                text = formatTime(ChapterTimeline.duration(chapters, chapter, totalDuration)),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(rowShape)
                            .then(selectedBorderModifier)
                            .clickable {
                                onChapterClick(chapter.startPositionMs)
                            },
                        colors = ListItemDefaults.colors(
                            containerColor = if (isCurrent)
                                selectedContainerColor
                            else Color.Transparent
                        )
                    )
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(bottomSpacerHeight))
                    Spacer(modifier = Modifier.height(32.dp))
                }

            }
        }
    }
}

// 为每一次改动添加详尽的中文注释：使用 @Suppress 抑制在 Composable 预览中直接构造 ViewModel 的 Lint 校验错误
@Suppress("ComposeViewModelForwarding", "ComposeViewModelInjection", "ViewModelConstructorInComposable")
@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, apiLevel = 36)
@Composable
fun ChapterListSheetStatefulPreview() {
    APlayerTheme {
        Surface {
            ChapterListSheetStateful(
                viewModel = PlayerViewModel(),
                metadata = BookMetadataState(title = "三体"),
                settings = PlayerSettingsState(isChapterListVisible = true),
                actions = PlayerActions(),
                sheetState = rememberModalBottomSheetState(),
                backdrop = rememberLayerBackdrop(),
                glassEffectMode = GlassEffectMode.Material
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showBackground = true, apiLevel = 35)
@Composable
fun ChapterListSheetPreview() {
    val sampleChapters = List(20) { i ->
        ChapterEntity(
            id = UUID.randomUUID().toString(),
            bookId = "bookId",
            bookFileId = "fileId",
            index = i,
            title = "Chapter ${i + 1}",
            startPositionMs = i * 60000L,
            durationMs = 60000L,
            fileOffsetMs = 0L,
            source = "EMBEDDED"
        )
    }

    APlayerTheme {
        Surface {
            ChapterListContent(
                chapters = sampleChapters,
                currentChapter = sampleChapters[17],
                totalDuration = sampleChapters.last().startPositionMs + sampleChapters.last().durationMs,
                onChapterClick = {},
                listState = rememberLazyListState(initialFirstVisibleItemIndex = 15),
                // 为每一次改动添加详尽的中文注释：Preview 显式引用设置模型里的默认玻璃效果，避免 ChapterListContent 参数重新拥有局部默认值。
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE
            )
        }
    }
}
