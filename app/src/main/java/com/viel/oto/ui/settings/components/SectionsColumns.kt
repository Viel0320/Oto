package com.viel.oto.ui.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Independent responsive columns for settings-style sections.
 * Provides one shared vertical scroll container while distributing indexed content into isolated columns, so uneven or expanding sections do not resize neighboring column spacing.
 */
@Composable
fun SectionsColumns(
    columnsCount: Int,
    itemCount: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
    horizontalSpacing: Dp = 16.dp,
    verticalSpacing: Dp = 16.dp,
    header: (@Composable () -> Unit)? = null,
    itemContent: @Composable (itemIndex: Int) -> Unit
) {
    val resolvedColumnsCount = columnsCount.coerceAtLeast(1)

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        header?.invoke()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(horizontalSpacing)
        ) {
            repeat(resolvedColumnsCount) { columnIndex ->
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(verticalSpacing)
                ) {
                    repeat(itemCount) { itemIndex ->
                        if (itemIndex % resolvedColumnsCount == columnIndex) {
                            itemContent(itemIndex)
                        }
                    }
                }
            }
        }
    }
}
