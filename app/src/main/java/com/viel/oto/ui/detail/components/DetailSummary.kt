package com.viel.oto.ui.detail.components

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
import com.viel.oto.shared.R

/**
 * Renders the detail-page summary title and description body.
 *
 * Description text can opt into an internal scroll region when the parent owns a fixed height.
 * The body remains backed by SelectableTextView so Android system text selection stays available.
 */
@Composable
fun DetailSummary(
    description: String,
    modifier: Modifier = Modifier,
    isScrollable: Boolean = false
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.summary_label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))

        val summaryDescription = remember(description) {
            renderDescriptionText(description)
        }

        val contentModifier = if (isScrollable) {
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
        }

        Box(modifier = contentModifier.fillMaxWidth()) {
            SelectableTextView(
                text = summaryDescription,
                modifier = Modifier.fillMaxWidth(),
                textColor = MaterialTheme.colorScheme.onSurface,
                textSizeSp = 16f,
                lineSpacingExtraSp = 4f,
                firstLineIndentEm = 2f
            )
        }
    }
}

/**
 * Detects whether stored summary text already contains HTML markup before it reaches Compose.
 */
private val htmlDescriptionPattern = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^>]*)?/?>""")

/**
 * Converts stored summary text into the CharSequence expected by SelectableTextView.
 *
 * Parser and import layers own line-break normalization and literal "\n" restoration.
 * This UI helper only chooses whether Android HTML parsing is needed, so display rendering
 * does not depend on source-specific audio tag formats.
 */
private fun renderDescriptionText(rawDescription: String): CharSequence {
    return if (htmlDescriptionPattern.containsMatchIn(rawDescription)) {
        HtmlCompat.fromHtml(rawDescription, HtmlCompat.FROM_HTML_MODE_COMPACT)
    } else {
        rawDescription
    }
}
