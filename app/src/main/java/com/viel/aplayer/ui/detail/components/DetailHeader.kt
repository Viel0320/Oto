package com.viel.aplayer.ui.detail.components

import android.view.Gravity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R

/**
 * 详尽中文注释：书籍详情页的头部组件，包含标题、作者和朗读者信息。
 * 从 DetailScreen.kt 中提取以支持横竖屏布局的统一维护。
 *
 * @param title 书籍标题
 * @param author 作者姓名
 * @param narrator 朗读者姓名
 * @param onAuthorClick 点击作者触发（通常是搜索该作者）
 * @param onAuthorLongClick 长按作者触发（显示详细信息弹窗）
 * @param onNarratorClick 点击朗读者触发
 * @param onNarratorLongClick 长按朗读者触发
 * @param isLandscape 是否处于横屏模式，用于动态调整字号和间距
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailHeader(
    title: String,
    author: String,
    narrator: String,
    onAuthorClick: () -> Unit,
    onAuthorLongClick: () -> Unit,
    onNarratorClick: () -> Unit,
    onNarratorLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 详尽中文注释：书籍标题。横屏下字号缩减至 22f 以保持紧凑，竖屏则保持 28f 的视觉张力。
        SelectableTextView(
            text = title.takeIf { it.isNotBlank() } ?: "Unknown",
            modifier = Modifier.fillMaxWidth(),
            textColor = MaterialTheme.colorScheme.onSurface,
            textSizeSp = if (isLandscape) 22f else 28f,
            lineSpacingExtraSp = 0f,
            gravity = Gravity.CENTER,
            typefaceStyle = android.graphics.Typeface.BOLD
        )

        Spacer(modifier = Modifier.height(if (isLandscape) 12.dp else 16.dp))

        // 详尽中文注释：作者与朗读者信息栏。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val itemPadding = if (isLandscape) 2.dp else 4.dp
            val cornerRadius = if (isLandscape) 8.dp else 12.dp

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(cornerRadius))
                    .combinedClickable(
                        onClick = onAuthorClick,
                        onLongClick = onAuthorLongClick
                    )
                    .padding(vertical = itemPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.author_label),
                    style = if (isLandscape) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = author.takeIf { it.isNotBlank() } ?: "Unknown",
                    style = if (isLandscape) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold) 
                           else MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            VerticalDivider(
                modifier = Modifier
                    .height(if (isLandscape) 20.dp else 32.dp)
                    .padding(horizontal = if (isLandscape) 4.dp else 8.dp),
                thickness = if (isLandscape) 1.dp else 2.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(if (isLandscape) 0.3f else 0.5f)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(cornerRadius))
                    .combinedClickable(
                        onClick = onNarratorClick,
                        onLongClick = onNarratorLongClick
                    )
                    .padding(vertical = itemPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.narrator_label),
                    style = if (isLandscape) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = narrator.takeIf { it.isNotBlank() } ?: "Unknown",
                    style = if (isLandscape) MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                           else MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
