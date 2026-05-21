package com.viel.aplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.ui.common.formatPeopleSubtitle

/**
 * 为每一次改动添加详尽的中文注释：
 * 独立出来的有声书长按管理与确认软删除系列 Dialog 组件。
 * 它将一级管理面板 Dialog 与二级删除确认 Dialog 统一打包封装，
 * 隔离了 Dialog 内部的显隐次序逻辑，极大程度瘦身了 HomeScreen.kt 主文件。
 */
@Composable
fun AudiobookActionDialogs(
    bookWithProgress: BookWithProgress?,
    onDismissRequest: () -> Unit,
    onUpdateReadStatus: (String, String) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bookWithProgress == null) return

    val book = bookWithProgress.book
    // 为每一次改动添加详尽的中文注释：内部维护二级软删除防误触确认 Dialog 的显示状态
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // 一级管理 Dialog
    if (!showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = book.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titlelarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(), // 为每一次改动添加详尽的中文注释：使文本框占满标题栏宽度，以便实现居中对齐
                    textAlign = TextAlign.Center // 为每一次改动添加详尽的中文注释：设置标题文字水平居中对齐
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                        Text(
                            text = formatPeopleSubtitle(book.author, book.narrator),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(), // 为每一次改动添加详尽的中文注释：填充可用宽度，促成副标题文本水平居中对齐
                            textAlign = TextAlign.Center // 为每一次改动添加详尽的中文注释：使副标题文字水平居中对齐
                        )
                    }

                    // 为每一次改动添加详尽的中文注释：微光分割线
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 为每一次改动添加详尽的中文注释：1. 标记状态区域。展示“标记为”标题和三个状态选择 FilterChip，增加容器居中属性
                    Column(
                        modifier = Modifier.fillMaxWidth(), // 为每一次改动添加详尽的中文注释：填充宽度以促成内部元素居中
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally // 为每一次改动添加详尽的中文注释：控制子组件在水平方向上居中对齐
                    ) {
                        Text(
                            text = "标记阅读状态",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(), // 为每一次改动添加详尽的中文注释：填充整行宽度
                            textAlign = TextAlign.Center // 为每一次改动添加详尽的中文注释：设置状态说明文字水平居中对齐
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val statusList = listOf(
                                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.NOT_STARTED to "未开始",
                                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.IN_PROGRESS to "进行中",
                                com.viel.aplayer.data.db.AudiobookSchema.ReadStatus.FINISHED to "已完成"
                            )
                            statusList.forEach { (status, label) ->
                                val isSelected = book.readStatus == status
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        onUpdateReadStatus(book.id, status)
                                        onDismissRequest()
                                    },
                                    label = { 
                                        Text(
                                            text = label, 
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.fillMaxWidth(), // 为每一次改动添加详尽的中文注释：使文本填充 Chip 内部的可用空间，以保证完全居中
                                            textAlign = TextAlign.Center // 为每一次改动添加详尽的中文注释：设置 Chip 内部文字水平居中对齐
                                        ) 
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    ),
                                    modifier = Modifier.weight(1f) // 为每一次改动添加详尽的中文注释：使三个 Chip 平分屏幕宽度
                                )
                            }
                        }
                    }

                    // 为每一次改动添加详尽的中文注释：微光分割线
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    // 为每一次改动添加详尽的中文注释：2. 重建封面与元数据。提供水波纹点击卡片，强制进行后台媒体重刷
                    Surface(
                        onClick = {
                            onForceRegenerate(book.id)
                            onDismissRequest()
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                            Column {
                                Text(
                                    text = "重建封面与元数据",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "强制从音频文件中重新提取封面和描述信息",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // 为每一次改动添加详尽的中文注释：3. 从媒体库删除（红区软删除警告卡片）
                    Surface(
                        onClick = {
                            showDeleteConfirm = true
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Column {
                                Text(
                                    text = "从媒体库移除",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "仅从播放列表中移出此书籍，手机源文件仍保留",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = onDismissRequest) {
                    Text("取消")
                }
            },
            modifier = modifier
        )
    }

    // 二级软删除确认 Dialog
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "确认从媒体库移除？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "您确定要从 APlayer 媒体库中移除《${book.title}》吗？\n\n⚠️ 注意：此操作仅为软删除，将从播放列表中移出，但不会删除您手机存储中的物理音频文件。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteBook(book.id)
                        showDeleteConfirm = false
                        onDismissRequest()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteConfirm = false }
                ) {
                    Text("取消")
                }
            },
            modifier = modifier
        )
    }
}
