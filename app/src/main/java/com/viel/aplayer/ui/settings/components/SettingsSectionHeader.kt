package com.viel.aplayer.ui.settings.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.viel.aplayer.ui.common.layout.LocalAppWindowSizeClass

/**
 * Separates reusable Settings header rendering from scene orchestration.
 * Feature sections can share one consistent header primitive while SettingsScreen stays focused on ordering functional clusters.
 * Horizontal insets are sourced from AppWindowSizeClass so headers align with responsive settings rows on every window width.
 */
@Composable
fun SettingsSectionHeader(title: String) {
    val screenHorizontalPadding = LocalAppWindowSizeClass.current.screenHorizontalPadding

    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = screenHorizontalPadding,
                top = 24.dp,
                end = screenHorizontalPadding,
                bottom = 8.dp
            )
    )
}
