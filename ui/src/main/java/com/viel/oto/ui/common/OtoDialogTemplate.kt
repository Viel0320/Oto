package com.viel.oto.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.model.GlassEffectMode
import dev.chrisbanes.haze.HazeState

/**
 * Shared derivation shell for page-owned dialogs.
 *
 * Provides the common dialog container, padding rhythm, optional header slots, optional body slot, and trailing action row.
 * Page-level DialogHost components should derive concrete dialogs from this template while keeping business state and click handling outside the shared shell.
 */
@Composable
fun OtoDialogTemplate(
    onDismissRequest: () -> Unit,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    scrollable: Boolean = true,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
    dialogMaxWidth: Dp = 460.dp,
    headerAlignment: Alignment.Horizontal = Alignment.Start,
    sectionSpacing: Dp = 16.dp,
    actionsSpacing: Dp = 8.dp,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    body: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null
) {
    BlurDialog(
        onDismissRequest = onDismissRequest,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        dismissOnBackPress = dismissOnBackPress,
        dismissOnClickOutside = dismissOnClickOutside,
        dialogMaxWidth = dialogMaxWidth,
        scrollable = scrollable
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
            horizontalAlignment = headerAlignment,
            verticalArrangement = Arrangement.spacedBy(sectionSpacing)
        ) {
            icon?.invoke()
            title?.invoke()
            body?.invoke(this)

            if (actions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(actionsSpacing, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions()
                }
            }
        }
    }
}
