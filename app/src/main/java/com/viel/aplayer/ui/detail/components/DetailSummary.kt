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
 * DetailSummary Setup (Detail Summary Component)
 *
 * Detail page book summary component (DetailSummary).
 * Contains "Summary" title label and HTML parsed description content.
 * Uses SelectableTextView to support system-level text selection.
 */
@Composable
fun DetailSummary(
    description: String,
    modifier: Modifier = Modifier,
    isScrollable: Boolean = false // New parameter: Controls whether the content area is allowed to scroll
) {
    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // 1. Fixed Title Part: Always displayed at the top of the component
        Text(
            text = stringResource(R.string.summary_label),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        val summaryDescription = remember(description) {
            renderDescriptionText(description)
        }
        
        // 2. Scrollable Content Area: If isScrollable is true, occupies remaining space and allows inner scrolling
        val contentModifier = if (isScrollable) {
            Modifier
                .weight(1f) // Occupy all remaining height except the title when parent layout height is fixed
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

// Regular expression for HTML tag detection, used to check if description contains HTML formatting
private val htmlDescriptionPattern = Regex("""</?[a-zA-Z][a-zA-Z0-9]*(\s[^>]*)?/?>""")

/**
 * Render Description Text (Helper Method)
 *
 * Helper method for rendering description text.
 * 1. Parser/import layer is responsible for line break normalization and literal "\n" restoration.
 * 2. UI layer only determines whether HTML parsing is needed, avoiding display component coupling with audio tag format differences.
 * 3. Non-HTML content keeps the original text format as stored in the database.
 */
private fun renderDescriptionText(rawDescription: String): CharSequence {
    return if (htmlDescriptionPattern.containsMatchIn(rawDescription)) {
        HtmlCompat.fromHtml(rawDescription, HtmlCompat.FROM_HTML_MODE_COMPACT)
    } else {
        rawDescription
    }
}
