package com.viel.aplayer.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.viel.aplayer.ui.player.PlayerScreenMode
import com.viel.aplayer.ui.theme.APlayerTheme

// 
// 独立出来的播放器底部 Tab 导航组件。
// 宽度完全依据 Tab 文本的实际测量宽度进行自适应设定，且在 Tab 之间滑动时提供完美的居中对齐与插值缩放动画。
@Composable
fun BottomNavTabs(
    selectedTab: PlayerScreenMode,
    onTabSelected: (PlayerScreenMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // 使用 Column 包裹底栏导航组件。我们不在此处直接添加 navigationBarsPadding()，
    // 而是通过在其底部顺序放置 16.dp 的防误触 Spacer 和系统的 navigationBarsPadding Spacer 来精准控制高度，
    // 确保把 Tab 的点击交互区（高度为 48.dp）硬性往上抬升 16.dp，彻底杜绝虚拟导航键/手势的防误触隐患。
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            val tabs = listOf(
                "Bookmark" to PlayerScreenMode.BOOKMARKS,
                "Subtitles" to PlayerScreenMode.SUBTITLES,
                "Related" to PlayerScreenMode.RELATED
            )

            val density = LocalDensity.current

            // 
            // 声明 3 个独立的 mutableStateOf 变量，为每个 Tab 真实文本宽度提供物理层面的隔离记录。
            // 使用经典优雅的 (80, 70, 60.dp) 做首帧保底，确保首帧文字在测量完成前指示器宽度绝不会突兀为 0。
            var bookmarkTextWidth by remember { mutableStateOf(80.dp) }
            var subtitlesTextWidth by remember { mutableStateOf(70.dp) }
            var relatedTextWidth by remember { mutableStateOf(60.dp) }

            var lastActiveTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
            LaunchedEffect(selectedTab) {
                if (selectedTab != PlayerScreenMode.PLAYER) {
                    lastActiveTab = selectedTab
                }
            }

            val isMainPlayer = selectedTab == PlayerScreenMode.PLAYER
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (isMainPlayer) 0f else 1f,
                animationSpec = tween(300),
                label = "indicator_alpha"
            )

            val indicatorOffset by animateFloatAsState(
                targetValue = lastActiveTab.index.toFloat(),
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_offset"
            )

            // 根据当前激活的 Tab 索引，动态读取对应的物理隔离后的独立测量文本宽度
            val activeTabWidth = remember(lastActiveTab, bookmarkTextWidth, subtitlesTextWidth, relatedTextWidth) {
                when (lastActiveTab) {
                    PlayerScreenMode.BOOKMARKS -> bookmarkTextWidth
                    PlayerScreenMode.SUBTITLES -> subtitlesTextWidth
                    PlayerScreenMode.RELATED -> relatedTextWidth
                    else -> 70.dp
                }
            }

            val currentIndicatorWidth by animateDpAsState(
                targetValue = activeTabWidth,
                animationSpec = if (indicatorAlpha == 0f) snap() else tween(300),
                label = "tab_indicator_width"
            )

            val activeColor = MaterialTheme.colorScheme.onSurface

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                val width = size.width
                val tabWidth = width / 3
                val indWidthPx = currentIndicatorWidth.toPx()

                // 
                // 精确计算出三个对齐 Tab 文本在 Canvas 坐标系下的物理中心坐标。
                // 1. Bookmark 靠左对齐，其中心点在宽度的一半。
                // 2. Subtitles 居中对齐，其中心点始终在中间 1/3 部分的几何中点。
                // 3. Related 靠右对齐，其右边缘贴紧 canvas 右侧，因此其中心点在宽度减去自身半宽处。
                val centerX0 = bookmarkTextWidth.toPx() / 2f
                val centerX1 = tabWidth * 1.5f
                val centerX2 = width - relatedTextWidth.toPx() / 2f

                // 
                // 基于滑动百分比 indicatorOffset 对这三个物理文本中心点做线性插值计算，
                // 确保指示器无论是在左、中、右，还是在滑动过渡过程中，都绝对与文字真实中心 100% 重合对齐。
                val indicatorCenterX = if (indicatorOffset <= 1f) {
                    val t = indicatorOffset
                    centerX0 + (centerX1 - centerX0) * t
                } else {
                    val t = indicatorOffset - 1f
                    centerX1 + (centerX2 - centerX1) * t
                }

                // 根据动画渐变宽 indWidthPx 和计算出的中心点，定位指示器的左侧起点 fluidXPos
                val fluidXPos = indicatorCenterX - indWidthPx / 2f

                // 
                // 将指示器在 y 轴上的高度绘制起点由原先的 size.height - 4.dp 往上抬升到 size.height - 10.dp。
                // 这样指示器与垂直居中的 Tab 文本（底部大约在 32.dp 处）之间的物理距离就从原先的 12.dp 精确缩减到 6.dp，完美将物理间距降低了一半。
                drawRoundRect(
                    color = activeColor.copy(alpha = indicatorAlpha),
                    topLeft = androidx.compose.ui.geometry.Offset(fluidXPos, size.height - 10.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(indWidthPx, 3.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                )
            }

            // M-18 修复 — 添加 selectableGroup 让无障碍服务
            // （TalkBack/切换控制）能识别出这是一组互斥单选的 Tab 容器
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, (title, mode) ->
                    // M-18 修复 — 每个 Tab 使用独立 MutableInteractionSource，
                    // 避免原先共享一个 interactionSource 导致按压/悬停状态相互串扰
                    val tabInteractionSource = remember(mode) { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            // M-18 修复 — 将 .clickable 改为 .selectable，
                            // 声明 selected 状态与 Role.Tab，让 TalkBack 读出"已选中/未选中"
                            .selectable(
                                selected = (selectedTab == mode),
                                onClick = { onTabSelected(mode) },
                                role = Role.Tab,
                                interactionSource = tabInteractionSource,
                                indication = null
                            ),
                        contentAlignment = when (index) {
                            0 -> Alignment.CenterStart
                            1 -> Alignment.Center
                            2 -> Alignment.CenterEnd
                            else -> Alignment.Center
                        }
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = if (selectedTab == mode) FontWeight.Bold else FontWeight.SemiBold,
                                fontSize = 16.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (selectedTab == mode) 1f else 0.6f),
                            modifier = Modifier.onSizeChanged { size ->
                                val textWidthDp = with(density) { size.width.toDp() }
                                when (index) {
                                    0 -> {
                                        if (bookmarkTextWidth != textWidthDp) bookmarkTextWidth = textWidthDp
                                    }
                                    1 -> {
                                        if (subtitlesTextWidth != textWidthDp) subtitlesTextWidth = textWidthDp
                                    }
                                    2 -> {
                                        if (relatedTextWidth != textWidthDp) relatedTextWidth = textWidthDp
                                    }
                                }
                            }
                        )
                    }
                }
            }
            
        }
        
        // 获取设备配置以识别屏幕方向
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        // 在 Tab 点击内容区 Box 的正下方放置防误触隔离 Spacer。
        // 横屏状态下垂直空间极其宝贵，且系统手势小白条通常移至侧边，故将占位缩减为 0.dp；竖屏时则保持 16.dp 保护手势防误触。
        val bottomSpacerHeight = if (isLandscape) 0.dp else 16.dp
        Spacer(modifier = Modifier.height(bottomSpacerHeight))
        
        // 使用独立的 Spacer 来应用系统底栏安全边距，确保交互区域完美安全抬升而不产生双重 Padding
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

// ==========================================
// Jetpack Compose @Preview 预览代码区
// ==========================================

// 
// 1. Tab 激活状态下的预览。
// 默认模拟选中“SUBTITLES（字幕歌词）”，且支持在 Android Studio 预览面板中通过 Live Edit 进行点击动态切换。
@Preview(name = "BottomNavTabs - Active Tab", showBackground = true)
@Composable
fun BottomNavTabsPreview_Active() {
    APlayerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            var selectedTab by remember { mutableStateOf(PlayerScreenMode.SUBTITLES) }
            BottomNavTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    }
}

// 
// 2. Tab 未激活状态下的预览。
// 模拟返回主播放器状态（PlayerScreenMode.PLAYER），此时指示器完美收缩隐藏，所有 Tab 文字呈均匀的 0.6f 低亮显示。
@Preview(name = "BottomNavTabs - All Inactive", showBackground = true)
@Composable
fun BottomNavTabsPreview_Inactive() {
    APlayerTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            var selectedTab by remember { mutableStateOf(PlayerScreenMode.PLAYER) }
            BottomNavTabs(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
    }
}