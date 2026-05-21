package com.viel.aplayer.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
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
import com.viel.aplayer.data.store.GlassEffectMode
import com.viel.aplayer.ui.common.BlurDialog
import com.viel.aplayer.ui.common.formatPeopleSubtitle
import dev.chrisbanes.haze.HazeState

/**
 * 详尽中文注释：
 * 独立出来的有声书长按管理与确认软删除系列 Dialog 组件。
 * 它将一级管理面板 Dialog 与二级删除确认 Dialog 统一打包封装，
 * 隔离了 Dialog 内部的显隐次序逻辑，极大程度瘦身了 HomeScreen.kt 主文件。
 *
 * 升级说明（Haze 毛玻璃）：
 * 将此前 Window 级 blurBehindRadius 模糊替换为 [BlurDialog] 内部的 Haze hazeEffect，
 * 调用方传入与主页 hazeSource 共用的 [HazeState]，让 Dialog 面板直接采样主页内容形成毛玻璃效果。
 */
@Composable
fun AudiobookActionDialogs(
    bookWithProgress: BookWithProgress?,
    hazeState: HazeState,
    // 为每一次改动添加详尽的中文注释：玻璃效果模式必须由主页从设置状态显式传入，避免 Dialog 封装内部私自声明默认值。
    glassEffectMode: GlassEffectMode,
    onDismissRequest: () -> Unit,
    onUpdateReadStatus: (String, String) -> Unit,
    onForceRegenerate: (String) -> Unit,
    onDeleteBook: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bookWithProgress == null) return

    val book = bookWithProgress.book
    // 详尽中文注释：内部维护二级软删除防误触确认 Dialog 的显示状态
    var showDeleteConfirm by remember { mutableStateOf(false) }

    // ─────────────────────────────────────────────────────────────────────────
    // 一级管理 Dialog（使用 BlurDialog + Haze 实现主页内容采样的毛玻璃效果）
    // ─────────────────────────────────────────────────────────────────────────
    if (!showDeleteConfirm) {
        BlurDialog(
            onDismissRequest = onDismissRequest,
            // 详尽中文注释：传入主页共享的 hazeState，确保一级操作面板能采样当前书架背景。
            hazeState = hazeState,
            // 为每一次改动添加详尽的中文注释：把用户设置传给 BlurDialog，Material 模式会跳过内部 Haze modifier。
            glassEffectMode = glassEffectMode,
            // 为每一次改动添加详尽的中文注释：Haze 具体半径、底色和 tint 由 BlurDialog 直接调用官方 HazeMaterials.regular()，不再由 Dialog 调用点传参。
            scrollable = true
        ) {
            // 详尽中文注释：对话框正文内容区，采用 Column 纵向排列各功能区块
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 详尽中文注释：图标区域，居中展示 Tune 管理图标
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 详尽中文注释：书籍标题，居中加粗显示，最多两行，溢出省略
                Text(
                    text = book.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                // 详尽中文注释：作者/配音副标题（若有），居中浅色展示
                if (book.author.isNotBlank() || book.narrator.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatPeopleSubtitle(book.author, book.narrator),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 详尽中文注释：微光分割线，透明度 0.5f 以降低视觉重量
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ─────────────────────────────────────────────────────────────
                // 详尽中文注释：1. 阅读状态标记区域
                // 展示三个并排 FilterChip，分别对应"未开始""进行中""已完成"三种状态
                // ─────────────────────────────────────────────────────────────
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "标记阅读状态",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
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
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Center
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ),
                                // 详尽中文注释：weight(1f) 使三个 Chip 均分行宽
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 详尽中文注释：微光分割线
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // ─────────────────────────────────────────────────────────────
                // 详尽中文注释：2. 重建封面与元数据。提供水波纹点击卡片，强制进行后台媒体重刷
                // ─────────────────────────────────────────────────────────────
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

                // ─────────────────────────────────────────────────────────────
                // 详尽中文注释：3. 从媒体库删除（红区软删除警告卡片）
                // ─────────────────────────────────────────────────────────────
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

                Spacer(modifier = Modifier.height(8.dp))

                // 详尽中文注释：底部"取消"按钮，右对齐
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismissRequest) {
                        Text("取消")
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 二级软删除确认 Dialog（同样使用 BlurDialog + Haze，略微加深模糊以强化警示感）
    // ─────────────────────────────────────────────────────────────────────────
    if (showDeleteConfirm) {
        BlurDialog(
            onDismissRequest = { showDeleteConfirm = false },
            // 详尽中文注释：二级确认面板复用主页共享 hazeState，保持与一级面板一致的背景采样来源。
            hazeState = hazeState,
            // 为每一次改动添加详尽的中文注释：删除确认 Dialog 同步遵循用户选择的玻璃效果模式。
            glassEffectMode = glassEffectMode,
            // 为每一次改动添加详尽的中文注释：删除确认 Dialog 同样交给 BlurDialog 直接调用官方 HazeMaterials.regular()，避免二级 Dialog 私自加深模糊参数。
            scrollable = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                // 详尽中文注释：删除确认图标，居中展示，使用 error 色调警示用户
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.CenterHorizontally)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 详尽中文注释：确认 Dialog 标题
                Text(
                    text = "确认从媒体库移除？",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 详尽中文注释：软删除说明文字，提醒用户仅为播放列表移除，不删除物理文件
                Text(
                    text = "您确定要从 APlayer 媒体库中移除《${book.title}》吗？\n\n⚠️ 注意：此操作仅为软删除，将从播放列表中移出，但不会删除您手机存储中的物理音频文件。",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 详尽中文注释：确认/取消按钮行，右对齐布局
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(
                        onClick = { showDeleteConfirm = false }
                    ) {
                        Text("取消")
                    }
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
                }
            }
        }
    }
}
