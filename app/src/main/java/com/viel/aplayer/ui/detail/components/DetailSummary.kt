package com.viel.aplayer.ui.detail.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.text.HtmlCompat
import com.viel.aplayer.R

/**
 * 详情页书籍概要组件 (DetailSummary)。
 * 包含“概要”标题标签和经过 HTML 解析处理的简介内容。
 * 使用 SelectableTextView 支持系统级文本选择。
 */
@Composable
fun DetailSummary(
    description: String,
    modifier: Modifier = Modifier,
    isScrollable: Boolean = false // 新增参数：控制内容区域是否允许滚动
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 1. 固定标题部分：始终显示在组件顶部
        Text(
            text = stringResource(R.string.summary_label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        val summaryDescription = remember(description) {
            renderDescriptionText(description)
        }
        
        // 2. 可滚动的内容区域：如果开启 isScrollable，则占据剩余空间并允许内部滚动
        val contentModifier = if (isScrollable) {
            Modifier
                .weight(1f) // 当父布局高度固定时，占据除标题外的所有剩余高度
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
        }

        Box(modifier = contentModifier.fillMaxWidth()) {
            SelectableTextView(
                text = summaryDescription,
                modifier = Modifier.fillMaxWidth(),
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                textSizeSp = 16f,
                lineSpacingExtraSp = 4f,
                firstLineIndentEm = 2f
            )
        }
    }
}

// HTML 标签检测正则表达式，用于判断简介是否包含 HTML 格式
private val htmlDescriptionPattern = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^>]*)?/?>""")

/**
 * 渲染简介文本的辅助方法。
 * 1. 规范化换行符（CRLF/CR -> LF）。
 * 2. 如果包含 HTML 标签，则使用 HtmlCompat 进行解析。
 * 3. 否则直接返回普通字符串。
 */
private fun renderDescriptionText(rawDescription: String): CharSequence {
    val normalizedDescription = rawDescription.replace("\r\n", "\n").replace('\r', '\n')
    return if (htmlDescriptionPattern.containsMatchIn(normalizedDescription)) {
        HtmlCompat.fromHtml(normalizedDescription, HtmlCompat.FROM_HTML_MODE_COMPACT)
    } else {
        normalizedDescription
    }
}
