package com.viel.aplayer.ui.detail.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
 * 为每一次改动添加详尽的中文注释：
 * 详情页书籍概要组件 (DetailSummary)。
 * 包含“概要”标题标签和经过 HTML 解析处理的简介内容。
 * 使用 SelectableTextView 支持系统级文本选择。
 */
@Composable
fun DetailSummary(
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.summary_label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        val summaryDescription = remember(description) {
            renderDescriptionText(description)
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
}

// HTML 标签检测正则表达式，用于判断简介是否包含 HTML 格式
private val htmlDescriptionPattern = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^>]*)?/?>""")

/**
 * 为每一次改动添加详尽的中文注释：
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
