package com.viel.aplayer.ui.settings

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsRootItem
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
    data object AddLibraryType : SettingsDialogState
    data object WebDavRoot : SettingsDialogState
    data object AbsServer : SettingsDialogState
    // Root Dialog Payload (Retain only the settings scene projection while a modal is open)
    // Dialog state survives transitions, so storing SettingsRootItem prevents transient UI state from retaining Room entities.
    data class RootActions(val root: SettingsRootItem) : SettingsDialogState
    data class DeleteRoot(val root: SettingsRootItem) : SettingsDialogState

    // Title: Add ImportConfirm dialog state (Expose a confirmation state holding the ZIP file Uri to import)
    // Lets the overlay host present a warning dialog before replacing user configuration data.
    data class ImportConfirm(
        val uri: Uri,
        val manifest: com.viel.aplayer.application.usecase.BackupManifest?
    ) : SettingsDialogState
}

/**
 * LanguagePickerDialog Composable (Expose app-level locale choices inside settings)
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
                    // Language Option Row (Render every supported locale as a single-choice row)
                    // Row click and radio click share the same immediate apply path to keep accessibility and touch behavior aligned.
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

// Settings Dialog Controller (Own transient settings dialog inputs outside SettingsScreen content)
// The overlay owns this controller so modal UI can be rendered beside page haze sources while still preserving form fields across dialog transitions.
class SettingsDialogController {
    var editingSafRootId by mutableStateOf<String?>(null)
    var dialogState by mutableStateOf<SettingsDialogState>(SettingsDialogState.None)

    var webDavUrl by mutableStateOf("")
    var webDavUsername by mutableStateOf("")
    var webDavPassword by mutableStateOf("")
    var webDavDisplayName by mutableStateOf("")
    var webDavBasePath by mutableStateOf("")

    var absBaseUrl by mutableStateOf("")
    var absUsername by mutableStateOf("")
    var absPassword by mutableStateOf("")
    var absLibraryId by mutableStateOf("")
    var absLibraryName by mutableStateOf("")
    var absDisplayName by mutableStateOf("")

    var editingRootId by mutableStateOf<String?>(null)

    // WebDAV Form Reset (Clear remote-library input after submit or dismiss)
    // Resetting all WebDAV fields together prevents stale credentials from leaking into the next add/edit flow.
    fun resetWebDavForm() {
        webDavUrl = ""
        webDavUsername = ""
        webDavPassword = ""
        webDavDisplayName = ""
        webDavBasePath = ""
        editingRootId = null
    }

    // ABS Form Reset (Clear Audiobookshelf input after submit or dismiss)
    // Resetting library selection and credentials together keeps subsequent server dialogs independent.
    fun resetAbsForm() {
        absBaseUrl = ""
        absUsername = ""
        absPassword = ""
        absLibraryId = ""
        absLibraryName = ""
        absDisplayName = ""
        editingRootId = null
    }
}

@Composable
fun rememberSettingsDialogController(): SettingsDialogController {
    // Settings Dialog Controller Memory (Keep one overlay-scoped controller for modal transitions)
    // remember keeps typed input stable while dialogs switch between root actions, WebDAV editing, and ABS editing.
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

/**
 * AbsServerDialog Composable: Collects base configuration inputs to synchronize with external Audiobookshelf servers.
 */
@Composable
fun AbsServerDialog(
    baseUrl: String,
    username: String,
    password: String,
    editingRootId: String?,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    connectionState: AbsConnectionUiState,
    selectedLibraryId: String,
    selectedLibraryName: String,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLibrarySelected: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    LaunchedEffect(editingRootId) {
        if (editingRootId != null) {
            onTestConnection()
        }
    }

    SettingsTemplateDialog(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                if (editingRootId != null) {
                    stringResource(R.string.settings_abs_edit_title)
                } else {
                    stringResource(R.string.settings_abs_add_title)
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = onBaseUrlChange,
                    label = { Text(stringResource(R.string.settings_abs_base_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.settings_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = {
                        Text(
                            if (editingRootId != null) {
                                stringResource(R.string.settings_password_optional_label)
                            } else {
                                stringResource(R.string.settings_password_label)
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTestConnection,
                    enabled = baseUrl.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || editingRootId != null)
                ) {
                    Text(
                        if (connectionState.isTesting) {
                            stringResource(R.string.settings_test_connecting)
                        } else {
                            stringResource(R.string.settings_test_connection)
                        }
                    )
                }
                if (connectionState.loginSucceeded) {
                     Spacer(modifier = Modifier.height(8.dp))
                     Text(
                        text = stringResource(R.string.settings_abs_login_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                     )
                }
                if (connectionState.serverVersion != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_abs_server_version, connectionState.serverVersion),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selectedLibraryName.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_abs_selected_library, selectedLibraryName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                connectionState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (connectionState.libraries.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.settings_abs_select_library_title), style = MaterialTheme.typography.titleSmall)
                    connectionState.libraries.forEach { library ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLibrarySelected(library.id, library.name) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLibraryId == library.id,
                                onClick = { onLibrarySelected(library.id, library.name) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${library.name} (${library.id})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = baseUrl.isNotBlank() && username.isNotBlank() && (password.isNotBlank() || editingRootId != null) &&
                    selectedLibraryId.isNotBlank() && selectedLibraryName.isNotBlank()
            ) {
                Text(
                    if (editingRootId != null) {
                        stringResource(R.string.action_save)
                    } else {
                        stringResource(R.string.action_add)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * WebDavRootDialog Composable: Collects user parameters required to access remote WebDAV file storage systems.
 */
@Composable
fun WebDavRootDialog(
    url: String,
    username: String,
    password: String,
    displayName: String,
    basePath: String,
    editingRootId: String? = null,
    hazeState: HazeState? = null,
    glassEffectMode: GlassEffectMode = GlassEffectMode.Material,
    connectionState: WebDavConnectionUiState,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBasePathChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    SettingsTemplateDialog(
        onDismissRequest = onDismiss,
        hazeState = hazeState,
        glassEffectMode = glassEffectMode,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false),
        title = {
            Text(
                if (editingRootId != null) {
                    stringResource(R.string.settings_webdav_edit_title)
                } else {
                    stringResource(R.string.settings_webdav_add_title)
                }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = onUrlChange,
                    label = { Text(stringResource(R.string.settings_server_url_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.settings_display_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = basePath,
                    onValueChange = onBasePathChange,
                    label = { Text(stringResource(R.string.settings_library_base_path_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = username,
                    onValueChange = onUsernameChange,
                    label = { Text(stringResource(R.string.settings_username_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = {
                        Text(
                            if (editingRootId != null) {
                                stringResource(R.string.settings_password_optional_label)
                            } else {
                                stringResource(R.string.settings_password_label)
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onTestConnection,
                    enabled = url.isNotBlank() && !connectionState.isTesting
                ) {
                    Text(
                        if (connectionState.isTesting) {
                            stringResource(R.string.settings_webdav_test_connecting)
                        } else {
                            stringResource(R.string.settings_test_connection)
                        }
                    )
                }
                if (connectionState.testSucceeded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.settings_webdav_connection_success),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                connectionState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = url.isNotBlank() && connectionState.testSucceeded
            ) {
                Text(
                    if (editingRootId != null) {
                        stringResource(R.string.action_save)
                    } else {
                        stringResource(R.string.action_add)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
