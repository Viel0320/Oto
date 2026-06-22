package com.viel.aplayer.ui.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.shared.settings.AppLanguage
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.common.APlayerDialogTemplate
import dev.chrisbanes.haze.HazeState

/**
 * SettingsDialogState interface: Keeps track of active settings-related dialog configurations.
 */
sealed interface SettingsDialogState {
    data object None : SettingsDialogState
    data object LanguagePicker : SettingsDialogState
    data object WebDavRoot : SettingsDialogState
    data object AbsServer : SettingsDialogState

    data class ImportConfirm(
        val uri: Uri,
        val manifest: com.viel.aplayer.application.usecase.BackupManifest?
    ) : SettingsDialogState
}

/**
 * Expose app-level locale choices inside settings.
 * Selecting a row applies the language immediately so users can see the UI switch without an extra confirmation step.
 */
@Composable
fun LanguagePickerDialog(
    selectedLanguage: AppLanguage,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    onLanguageSelected: (AppLanguage) -> Unit,
    onDismiss: () -> Unit
) {
    SettingsTemplateDialog(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        title = { Text(stringResource(R.string.settings_language_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppLanguageOptions.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onLanguageSelected(language)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedLanguage == language,
                            onClick = {
                                onLanguageSelected(language)
                                onDismiss()
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = appLanguageLabel(language),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

class SettingsDialogController {
    var dialogState by mutableStateOf<SettingsDialogState>(SettingsDialogState.None)
}

@Composable
fun rememberSettingsDialogController(): SettingsDialogController {
    return remember { SettingsDialogController() }
}

/**
 * SettingsTemplateDialog Composable: Acts as a local adapter slot connecting setting dialog blocks to the common app template.
 */
@Composable
fun SettingsTemplateDialog(
    onDismissRequest: () -> Unit,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties()
) {
    APlayerDialogTemplate(
        onDismissRequest = onDismissRequest,
        hazeState = hazeState,
        glassEffectMode = if (hazeState != null) glassEffectMode else GlassEffectMode.Material,
        dismissOnBackPress = properties.dismissOnBackPress,
        dismissOnClickOutside = properties.dismissOnClickOutside,
        scrollable = true,
        title = title,
        body = text?.let { content ->
            {
                content()
            }
        },
        actions = {
            dismissButton?.invoke()
            confirmButton()
        }
    )
}
