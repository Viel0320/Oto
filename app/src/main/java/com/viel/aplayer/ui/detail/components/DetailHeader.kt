package com.viel.aplayer.ui.detail.components

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.viel.aplayer.R
import com.viel.aplayer.ui.theme.APlayerTheme

/**
 * 详尽中文注释：书籍详情页的头部组件，包含标题、作者和朗读者信息。
 * 内部集成了支持系统文本选择菜单的 SelectableTextView。
 *
 * @param title 书籍标题
 * @param author 作者姓名
 * @param narrator 朗读者姓名
 * @param onAuthorClick 点击作者触发
 * @param onAuthorLongClick 长按作者触发
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
        // 详尽中文注释：使用内部 SelectableTextView 展示书籍标题。
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

/**
 * 详尽中文注释：内部使用的可选择文本组件。
 */
@Composable
fun SelectableTextView(
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
        val activities =
            pm.queryIntentActivities(baseIntent, PackageManager.ResolveInfoFlags.of(0))
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

@Preview(name = "Detail Header - Portrait", showBackground = true)
@Composable
fun DetailHeaderPortraitPreview() {
    APlayerTheme {
        DetailHeader(
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            onAuthorClick = {},
            onAuthorLongClick = {},
            onNarratorClick = {},
            onNarratorLongClick = {},
            isLandscape = false,
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(name = "Detail Header - Landscape", showBackground = true, widthDp = 640)
@Composable
fun DetailHeaderLandscapePreview() {
    APlayerTheme {
        DetailHeader(
            title = "In the Megachurch",
            author = "Ryo Asai",
            narrator = "Narrator A",
            onAuthorClick = {},
            onAuthorLongClick = {},
            onNarratorClick = {},
            onNarratorLongClick = {},
            isLandscape = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}
