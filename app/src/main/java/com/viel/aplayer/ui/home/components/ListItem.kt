package com.viel.aplayer.ui.home.components

// 新增 combinedClickable 导入以响应列表项目长按的高阶手势监听
// 新增 ExperimentalFoundationApi 导入，由于 combinedClickable 在旧版中是实验性 API，这里作为安全屏障防御编译期缺陷
// 补充导入 getValue 和 setValue 的扩展方法以支持 Composable 属性代理委托机制 (H-13)
import android.util.Log
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.viel.aplayer.ui.common.formatCompactDuration
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import com.viel.aplayer.ui.common.theme.APlayerTheme
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListItem(
    title: String,
    author: String,
    narrator: String,
    duration: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverPath: String? = null,
    coverLastUpdated: Long = 0L, // 用于传递封面文件自愈重建时间戳，用以触发响应式强打破缓存
    progressPercent: Int? = null,
    // 新增 onLongClick 参数以接收长按列表项目触发的事件回调
    onLongClick: () -> Unit = {},
    onPlayClick: () -> Unit = {}
) {
    ListItem(
        // 使用 combinedClickable 替换原本的 clickable，以高阶手势监听 onClick 及 onLongClick 交互
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        ),
        headlineContent = {
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    formatPeopleSubtitle(author, narrator),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val separator = " • "
                    val textStyle = MaterialTheme.typography.labelSmall
                    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

                    if (progressPercent != null && progressPercent > 0) {
                        Text(
                            text = "$progressPercent%",
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    } else {
                        Text(
                            text = "NEW",
                            style = textStyle,
                            color = textColor
                        )
                        Text(text = separator, style = textStyle, color = textColor)
                    }

                    Text(
                        text = formatCompactDuration(duration),
                        style = textStyle,
                        color = textColor
                    )
                }
            }
        },
        leadingContent = {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                val isPreview = LocalInspectionMode.current
                // 定义本地图片加载错误状态，利用 Coil 的异步加载与 onError 回调实现零主线程磁盘同步 I/O 探测 (H-13)
                var isImageError by remember(coverPath) { mutableStateOf(false) }
                if (!isPreview && (coverPath != null) && !isImageError) {
                    // 使用 LocalContext 构建附带 lastScannedAt 作为更新戳的 ImageRequest，在底层打破 Coil 对于相同物理文件的本地与内存缓存
                    val context = LocalContext.current
                    val request = remember(coverPath, coverLastUpdated) {
                        ImageRequest.Builder(context)
                            .data(File(coverPath))
                            .memoryCacheKey("$coverPath?t=$coverLastUpdated")
                            .diskCacheKey("$coverPath?t=$coverLastUpdated")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { state ->
                            isImageError = true
                            // 当封面物理文件损坏或系统读取失败时，在控制台打印可供调试的具体物理路径和错误原委
                            Log.e(
                                "ListItem",
                                "ListItem 封面加载失败！物理路径: $coverPath, 原因: ${state.result.throwable.message}",
                                state.result.throwable
                            )
                        }
                    )
                } else {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        trailingContent = {
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier.offset(x = 8.dp)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play")
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Preview(showBackground = true, name = "New Book", apiLevel = 36)
@Composable
fun ListItemNewPreview() {
    APlayerTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "The Great Adventure",
                author = "John Doe",
                narrator = "Jane Smith",
                duration = 3600000L,
                progressPercent = 0,
//                addedAt = System.currentTimeMillis(),
                onClick = {}
            )
        }
    }
}

@Preview(showBackground = true, name = "In Progress", apiLevel = 36)
@Composable
fun ListItemProgressPreview() {
    APlayerTheme(dynamicColor = false) {
        Surface {
            ListItem(
                title = "Mystery in the Woods",
                author = "Arthur Conan Doyle",
                narrator = "Stephen Fry",
                duration = 7200000L,
                progressPercent = 45,
//                addedAt = System.currentTimeMillis() - 86400000,
                onClick = {}
            )
        }
    }
}