package com.viel.aplayer.ui.player.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDropdownMenu
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.theme.APlayerTheme
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerAppBar(
    title: String,
    author: String,
    narrator: String,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onToggleProgressMode: (() -> Unit)? = null,
    onDeleteBook: (() -> Unit)? = null,
    isChapterProgressMode: Boolean = false,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由播放页从设置状态显式传入，播放器顶部栏不再声明 Material 默认值。
    glassEffectMode: GlassEffectMode,
    // 为每一次改动添加详尽的中文注释：菜单复用播放器背景的 LayerBackdrop 采样源；独立预览或未传参时默认自愈。
    backdrop: LayerBackdrop? = null,
    containerColor: Color = Color.Transparent,
    contentColor: Color = LocalContentColor.current
) {
    val navIcon = navigationIcon ?: Icons.Rounded.KeyboardArrowDown
    var showMenu by remember { mutableStateOf(false) }
    // 为每一次改动添加详尽的中文注释：在内部安全创建本地 backdrop 采样器，确保当外部 backdrop 为 null 时仍能获得优雅的高清模糊背景
    val localBackdrop = backdrop ?: rememberLayerBackdrop()

    CenterAlignedTopAppBar(
        // 为每一次改动添加详尽的中文注释：移除修饰符上的 statusBarsPadding()，改由更专业的 windowInsets 直接自适应管理，彻底斩断双重 Padding
        modifier = modifier,
        windowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.navigationBars),
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatPeopleSubtitle(author, narrator),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (contentColor == Color.White) {
                        Color.White.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onNavigationClick) {
                Icon(
                    painter = rememberVectorPainter(navIcon),
                    contentDescription = "Back",
                    tint = contentColor
                )
            }
        },
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        painter = rememberVectorPainter(Icons.Rounded.MoreVert),
                        contentDescription = "More",
                        tint = contentColor
                    )
                }
                
                BlurDropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    // 为每一次改动添加详尽的中文注释：将播放器 Surface 采样的 LayerBackdrop 传入下拉菜单以生成毛玻璃效果。
                    backdrop = localBackdrop,
                    // 为每一次改动添加详尽的中文注释：播放器更多菜单跟随设置页选择在 Material 与 miuix-blur 之间切换。
                    glassEffectMode = glassEffectMode
                ) {
                    // 1. 进度模式切换
                    DropdownMenuItem(
                        text = {
                            Text(if (isChapterProgressMode) "Show Total Progress" else "Show Chapter Progress")
                        },
                        onClick = {
                            onToggleProgressMode?.invoke()
                            showMenu = false
                        },
                        enabled = onToggleProgressMode != null
                    )

                    // 2. 删除书籍
                    if (onDeleteBook != null) {
                        DropdownMenuItem(
                            text = { 
                                Text("Delete from Library", color = MaterialTheme.colorScheme.error) 
                            },
                            onClick = {
                                onDeleteBook.invoke()
                                showMenu = false
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = Color.Unspecified,
            navigationIconContentColor = Color.Unspecified,
            titleContentColor = Color.Unspecified,
            actionIconContentColor = Color.Unspecified
        )
    )
}

@Preview(apiLevel = 36)
@Composable
fun PlayerAppBarPreview() {
    APlayerTheme {
        Surface(color = Color(0xFF1C1B1F)) {
            PlayerAppBar(
                title = "Preview Book",
                author = "Preview Author",
                narrator = "Preview Narrator",
                onNavigationClick = {},
                // 为每一次改动添加详尽的中文注释：Preview 显式引用设置模型里的默认玻璃效果，避免 PlayerAppBar 参数重新拥有局部默认值。
                glassEffectMode = AppSettings.DEFAULT_GLASS_EFFECT_MODE,
                contentColor = Color.White
            )
        }
    }
}
