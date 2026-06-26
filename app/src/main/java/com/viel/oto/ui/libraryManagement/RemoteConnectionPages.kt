package com.viel.oto.ui.libraryManagement

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.viel.oto.shared.R
import com.viel.oto.shared.settings.GlassEffectMode
import com.viel.oto.ui.common.OtoGlassTopBar
import com.viel.oto.ui.common.OtoPopupSelector
import com.viel.oto.ui.common.OtoPopupWidth
import com.viel.oto.ui.common.aPlayerTextPopupItem
import com.viel.oto.ui.common.layout.LocalAppWindowSizeClass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * URL split into the parts the remote-connection forms edit independently.
 * [scheme] and [port] are null when the source text did not contain them, letting the source
 * field tell a pasted full URL apart from a bare host that is still being typed.
 */
private data class ParsedServerUrl(
    val scheme: String?,
    val host: String,
    val port: String?,
    val path: String
)

private data class FoldedRemoteInput(
    val protocol: String,
    val host: String,
    val port: String,
    val path: String
)

private val SERVER_URL_REGEX =
    Regex("^(?:([a-zA-Z][a-zA-Z0-9+.\\-]*)://)?([^:/?#]*)(:(\\d*))?([/?#].*)?$")

private val HOST_WITH_PORT_REGEX = Regex(".*:\\d+$")

/**
 * Splits a server URL without android.net.Uri, whose getPort() logs and swallows a
 * NumberFormatException on an in-progress authority such as "http://host:". Segments the
 * input never supplied come back as null ([scheme]/[port]) or empty ([host]/[path]).
 */
private fun parseServerUrl(raw: String): ParsedServerUrl {
    val trimmed = raw.trim()
    val match = SERVER_URL_REGEX.find(trimmed)
        ?: return ParsedServerUrl(null, trimmed, null, "")
    val scheme = match.groupValues[1].takeIf { it.isNotEmpty() }?.lowercase()
    val port = if (match.groups[3] != null) match.groupValues[4] else null
    return ParsedServerUrl(scheme, match.groupValues[2], port, match.groupValues[5])
}

/**
 * Rebuilds a server URL from the edited fields. A port already embedded in [host]
 * (e.g. "192.168.1.100:13378") wins over the separate [port] field, and a dangling colon
 * with no digits is dropped so the result is always a clean URL.
 */
private fun buildServerUrl(protocol: String, host: String, port: String, path: String = ""): String {
    val trimmedHost = host.trim()
    if (trimmedHost.isEmpty()) return ""
    val portSuffix = when {
        HOST_WITH_PORT_REGEX.matches(trimmedHost) -> ""
        port.isBlank() -> ""
        else -> ":${port.trim()}"
    }
    return "$protocol://${trimmedHost.removeSuffix(":")}$portSuffix${normalizePathSuffix(path)}"
}

/**
 * Builds the editable authority text shown in the URL field.
 */
private fun buildAuthority(host: String, port: String): String {
    val trimmedHost = host.trim().removeSuffix(":")
    if (trimmedHost.isEmpty()) return ""
    return if (port.isBlank()) trimmedHost else "$trimmedHost:${port.trim()}"
}

/**
 * Builds the editable host/port/path text shown in the WebDAV URL field.
 */
private fun buildAddressWithPath(host: String, port: String, path: String): String {
    val authority = buildAuthority(host, port)
    if (authority.isEmpty()) return ""
    return authority + normalizePathSuffix(path)
}

/**
 * Normalizes a path suffix so the saved URL keeps a single leading slash when a path exists.
 */
private fun normalizePathSuffix(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank()) return ""
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}

/**
 * Extracts the host and trailing numeric port from an editable authority string.
 * A dangling colon is discarded and a missing port comes back as an empty string.
 */
private fun splitAuthorityPort(authority: String): Pair<String, String> {
    val trimmed = authority.trim()
    if (trimmed.isEmpty()) return "" to ""
    if (!HOST_WITH_PORT_REGEX.matches(trimmed)) {
        return trimmed.removeSuffix(":") to ""
    }
    val separatorIndex = trimmed.lastIndexOf(':')
    if (separatorIndex <= 0) return trimmed.removeSuffix(":") to ""
    return trimmed.substring(0, separatorIndex) to trimmed.substring(separatorIndex + 1)
}

/**
 * Splits a scheme-less editable input into authority and the remaining path/query/fragment part.
 * WebDAV keeps that trailing part synchronized with its dedicated base-path field.
 */
private fun splitEditableAuthorityAndPath(raw: String): Pair<String, String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return "" to ""
    val pathStart = trimmed.indexOfFirst { it == '/' || it == '?' || it == '#' }
    if (pathStart < 0) return trimmed to ""
    return trimmed.substring(0, pathStart) to trimmed.substring(pathStart)
}

/**
 * Folds a WebDAV URL field edit into protocol, host, port, and path.
 * Pasted full URLs update every dedicated control, while direct field edits preserve any path
 * suffix so the URL field and base-path field remain two views over the same data.
 */
private fun foldWebDavInput(
    raw: String,
    currentProtocol: String,
    protocols: List<String>
): FoldedRemoteInput {
    if (raw.trim().isEmpty()) {
        return FoldedRemoteInput(
            protocol = currentProtocol,
            host = "",
            port = "",
            path = ""
        )
    }

    val parsed = parseServerUrl(raw)
    if (parsed.scheme != null) {
        return FoldedRemoteInput(
            protocol = parsed.scheme.takeIf { it in protocols } ?: currentProtocol,
            host = parsed.host,
            port = parsed.port.orEmpty(),
            path = parsed.path
        )
    }

    val (authority, path) = splitEditableAuthorityAndPath(raw)
    val (host, port) = splitAuthorityPort(authority)
    return FoldedRemoteInput(
        protocol = currentProtocol,
        host = host,
        port = port,
        path = path
    )
}

/**
 * Folds an ABS base-url edit into protocol, host, and port.
 * ABS ignores any pasted path and keeps the dedicated port field synchronized with the text field.
 */
private fun foldAbsInput(
    raw: String,
    currentProtocol: String,
    protocols: List<String>
): FoldedRemoteInput {
    if (raw.trim().isEmpty()) {
        return FoldedRemoteInput(
            protocol = currentProtocol,
            host = "",
            port = "",
            path = ""
        )
    }

    val parsed = parseServerUrl(raw)
    if (parsed.scheme != null) {
        return FoldedRemoteInput(
            protocol = parsed.scheme.takeIf { it in protocols } ?: currentProtocol,
            host = parsed.host,
            port = parsed.port.orEmpty(),
            path = ""
        )
    }

    val authority = splitEditableAuthorityAndPath(raw).first
    val (host, port) = splitAuthorityPort(authority)
    return FoldedRemoteInput(
        protocol = currentProtocol,
        host = host,
        port = port,
        path = ""
    )
}

/**
 * Presents the WebDAV root form as a route-owned page instead of a dialog.
 * The page keeps SettingsConnectionHandler as the single owner of connection testing and root persistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavConnectionPage(
    url: String,
    username: String,
    password: String,
    displayName: String,
    basePath: String,
    editingRootId: String? = null,
    connectionState: WebDavConnectionUiState,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    onUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onBasePathChange: (String) -> Unit,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    val protocols = listOf("https", "http")
    var protocolExpanded by remember { mutableStateOf(false) }

    val initial = remember(editingRootId) { parseServerUrl(url) }
    var protocol by remember(editingRootId) {
        mutableStateOf(initial.scheme?.takeIf { it in protocols } ?: "https")
    }
    var host by remember(editingRootId) { mutableStateOf(initial.host) }
    var port by remember(editingRootId) { mutableStateOf(initial.port.orEmpty()) }

    val effectiveBasePath = basePath.ifBlank { initial.path }

    val rebuildUrl: (String, String, String, String) -> Unit = { nextProtocol, nextHost, nextPort, nextPath ->
        onUrlChange(buildServerUrl(nextProtocol, nextHost, nextPort, nextPath))
    }

    val urlPreview = buildServerUrl(protocol, host, port, effectiveBasePath).ifEmpty { "-" }
    val serverFieldValue = buildAddressWithPath(host, port, effectiveBasePath)

    RemoteConnectionPageFrame(
        title = stringResource(R.string.settings_library_type_webdav),
        onBack = onBack,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        primaryAction = {
            Button(
                onClick = {
                    if (displayName.isBlank()) {
                        onDisplayNameChange(host.ifBlank { url })
                    }
                    onConfirm()
                },
                enabled = host.isNotBlank() && connectionState.testSucceeded,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (editingRootId != null) {
                        stringResource(R.string.action_save)
                    } else {
                        stringResource(R.string.action_connect)
                    }
                )
            }
        },
        secondaryAction = {
            TextButton(
                onClick = onTestConnection,
                enabled = host.isNotBlank() && !connectionState.isTesting,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (connectionState.isTesting) {
                        stringResource(R.string.settings_webdav_test_connecting)
                    } else {
                        stringResource(R.string.action_test)
                    }
                )
            }
        },
        serverContent = {
            RemoteSectionTitle(text = stringResource(R.string.settings_server_section))

            RemoteTextField(
                value = serverFieldValue,
                onValueChange = {
                    val folded = foldWebDavInput(it, protocol, protocols)
                    protocol = folded.protocol
                    host = folded.host
                    port = folded.port
                    onBasePathChange(folded.path)
                    rebuildUrl(folded.protocol, folded.host, folded.port, folded.path)
                },
                label = stringResource(R.string.settings_server_url_label),
                placeholder = stringResource(R.string.settings_server_address_placeholder),
                keyboardType = KeyboardType.Uri,
                isError = connectionState.lastError != null && host.isBlank(),
                prefixText = "$protocol://"
            )
            connectionState.lastError?.takeIf { it.isNotBlank() && host.isBlank() }?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            RemoteTextField(
                value = effectiveBasePath,
                onValueChange = {
                    onBasePathChange(it)
                    rebuildUrl(protocol, host, port, it)
                },
                label = stringResource(R.string.settings_library_base_path_label)
            )

            Spacer(modifier = Modifier.height(10.dp))
            RemoteProtocolPortRow(
                protocols = protocols,
                protocol = protocol,
                protocolExpanded = protocolExpanded,
                port = port,
                onProtocolExpandedChange = { protocolExpanded = it },
                onProtocolSelected = { selectedProtocol ->
                    protocol = selectedProtocol
                    protocolExpanded = false
                    rebuildUrl(selectedProtocol, host, port, effectiveBasePath)
                },
                onPortChange = { nextPort ->
                    port = nextPort
                    rebuildUrl(protocol, host, nextPort, effectiveBasePath)
                }
            )

            Text(
                text = "${stringResource(R.string.settings_url_preview_label)} $urlPreview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        },
        authContent = {
            RemoteSectionTitle(text = stringResource(R.string.settings_auth_section))
            Text(
                text = stringResource(R.string.settings_auth_anonymous_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            RemoteTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.settings_username_label)
            )
            Spacer(modifier = Modifier.height(10.dp))
            RemotePasswordField(
                value = password,
                onValueChange = onPasswordChange,
                label = if (editingRootId != null) {
                    stringResource(R.string.settings_password_optional_label)
                } else {
                    stringResource(R.string.settings_password_label)
                }
            )
            WebDavConnectionFeedback(connectionState)
        }
    )
}

/**
 * Presents the AudiobookShelf server form as a route-owned page.
 * Editing an existing ABS root still refreshes libraries on entry so saved server metadata remains selectable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbsConnectionPage(
    baseUrl: String,
    username: String,
    password: String,
    editingRootId: String?,
    connectionState: AbsConnectionUiState,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    selectedLibraries: Map<String, String>,
    onBaseUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLibrarySelected: (String, String) -> Unit,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    LaunchedEffect(editingRootId) {
        if (editingRootId != null) {
            onTestConnection()
        }
    }

    val protocols = listOf("https", "http")
    var protocolExpanded by remember { mutableStateOf(false) }

    val initial = remember(editingRootId) { parseServerUrl(baseUrl) }
    var protocol by remember(editingRootId) {
        mutableStateOf(initial.scheme?.takeIf { it in protocols } ?: "https")
    }
    var host by remember(editingRootId) { mutableStateOf(initial.host) }
    var port by remember(editingRootId) { mutableStateOf(initial.port.orEmpty()) }

    val rebuildUrl: (String, String, String) -> Unit = { nextProtocol, nextHost, nextPort ->
        onBaseUrlChange(buildServerUrl(nextProtocol, nextHost, nextPort))
    }

    val baseUrlFieldValue = buildAuthority(host, port)
    val urlPreview = buildServerUrl(protocol, host, port).ifEmpty { "-" }

    RemoteConnectionPageFrame(
        title = stringResource(R.string.settings_library_type_abs),
        onBack = onBack,
        glassEffectMode = glassEffectMode,
        hazeState = hazeState,
        primaryAction = {
            Button(
                onClick = onConfirm,
                enabled = baseUrl.isNotBlank() &&
                        username.isNotBlank() &&
                        (password.isNotBlank() || editingRootId != null) &&
                        selectedLibraries.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (editingRootId != null) {
                        stringResource(R.string.action_save)
                    } else {
                        stringResource(R.string.action_connect)
                    }
                )
            }
        },
        secondaryAction = {
            TextButton(
                onClick = onTestConnection,
                enabled = baseUrl.isNotBlank() &&
                        username.isNotBlank() &&
                        (password.isNotBlank() || editingRootId != null) &&
                        !connectionState.isTesting,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    if (connectionState.isTesting) {
                        stringResource(R.string.settings_test_connecting)
                    } else {
                        stringResource(R.string.action_test)
                    }
                )
            }
        },
        serverContent = {
            RemoteSectionTitle(text = stringResource(R.string.settings_server_section))

            RemoteTextField(
                value = baseUrlFieldValue,
                onValueChange = {
                    val folded = foldAbsInput(it, protocol, protocols)
                    protocol = folded.protocol
                    host = folded.host
                    port = folded.port
                    rebuildUrl(folded.protocol, folded.host, folded.port)
                },
                label = stringResource(R.string.settings_abs_base_url_label),
                placeholder = stringResource(R.string.settings_server_address_placeholder),
                keyboardType = KeyboardType.Uri,
                prefixText = "$protocol://"
            )

            Spacer(modifier = Modifier.height(10.dp))
            RemoteProtocolPortRow(
                protocols = protocols,
                protocol = protocol,
                protocolExpanded = protocolExpanded,
                port = port,
                onProtocolExpandedChange = { protocolExpanded = it },
                onProtocolSelected = { selectedProtocol ->
                    protocol = selectedProtocol
                    protocolExpanded = false
                    rebuildUrl(selectedProtocol, host, port)
                },
                onPortChange = { nextPort ->
                    port = nextPort
                    rebuildUrl(protocol, host, nextPort)
                }
            )

            Text(
                text = "${stringResource(R.string.settings_url_preview_label)} $urlPreview",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        },
        authContent = {
            RemoteSectionTitle(text = stringResource(R.string.settings_auth_section))
            Text(
                text = stringResource(R.string.settings_auth_anonymous_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            RemoteTextField(
                value = username,
                onValueChange = onUsernameChange,
                label = stringResource(R.string.settings_username_label)
            )
            Spacer(modifier = Modifier.height(10.dp))
            RemotePasswordField(
                value = password,
                onValueChange = onPasswordChange,
                label = if (editingRootId != null) {
                    stringResource(R.string.settings_password_optional_label)
                } else {
                    stringResource(R.string.settings_password_label)
                }
            )
            AbsConnectionFeedback(
                connectionState = connectionState,
                selectedLibraries = selectedLibraries,
                isEditMode = editingRootId != null,
                onLibrarySelected = onLibrarySelected
            )
        }
    )
}

/**
 * Keeps the remote-source protocol selector and port input on the same form grid.
 *
 * The protocol control participates in row width instead of using a fixed narrow width, which prevents
 * the dropdown's own touch target and content padding from reading as an extra outer margin beside the
 * port field on compact add-library forms.
 */
@Composable
private fun RemoteProtocolPortRow(
    protocols: List<String>,
    protocol: String,
    protocolExpanded: Boolean,
    port: String,
    onProtocolExpandedChange: (Boolean) -> Unit,
    onProtocolSelected: (String) -> Unit,
    onPortChange: (String) -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth()
    ) {
        OtoPopupSelector(
            items = protocols.map { option ->
                aPlayerTextPopupItem(key = option, label = option.uppercase())
            },
            expanded = protocolExpanded,
            onExpandedChange = onProtocolExpandedChange,
            selectedIndex = protocols.indexOf(protocol),
            onSelect = { index ->
                protocols.getOrNull(index)?.let(onProtocolSelected)
            },
            panelWidth = OtoPopupWidth.Wrap,
            collapsedHeight = 56.dp
        )
        RemoteTextField(
            value = port,
            onValueChange = { rawPort ->
                onPortChange(rawPort.filter { character -> character.isDigit() })
            },
            label = stringResource(R.string.settings_port_hint, 443),
            keyboardType = KeyboardType.Number,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Shared page frame for remote-source login forms.
 * Chrome is the shared [OtoGlassTopBar] overlaid on top of the scrolling form, matching the other
 * settings sub-pages. The top bar owns the top safe-drawing inset and reports its height so the content
 * starts below it; the content applies the remaining start/end/bottom safe-drawing and IME insets.
 * The frame owns the responsive split: [serverContent] and [authContent] sit side by side on wide
 * (landscape/tablet) viewports and stack vertically on compact ones. The test/connect actions flow at
 * the end of the scrollable content rather than being pinned to the bottom.
 */
@Composable
private fun RemoteConnectionPageFrame(
    title: String,
    onBack: () -> Unit,
    glassEffectMode: GlassEffectMode,
    hazeState: HazeState?,
    primaryAction: @Composable RowScope.() -> Unit,
    secondaryAction: @Composable RowScope.() -> Unit,
    serverContent: @Composable ColumnScope.() -> Unit,
    authContent: @Composable ColumnScope.() -> Unit
) {
    val twoColumn = LocalAppWindowSizeClass.current.isWideScreen
    val resolvedHazeState = hazeState.takeIf { glassEffectMode == GlassEffectMode.Haze }
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val safeDrawingPadding = WindowInsets.safeDrawing.exclude(WindowInsets.ime).asPaddingValues()
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    var topBarHeightPx by remember { mutableIntStateOf(0) }
    val topBarHeight = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() }
    } else {
        safeDrawingPadding.calculateTopPadding() + 64.dp
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (resolvedHazeState != null) Modifier.hazeSource(resolvedHazeState) else Modifier)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = safeDrawingPadding.calculateStartPadding(layoutDirection) + 24.dp,
                    end = safeDrawingPadding.calculateEndPadding(layoutDirection) + 24.dp,
                    top = topBarHeight + 8.dp,
                    bottom = imeBottom.coerceAtLeast(safeDrawingPadding.calculateBottomPadding()) + 20.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (twoColumn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) { serverContent() }
                    Column(modifier = Modifier.weight(1f)) { authContent() }
                }
            } else {
                serverContent()
                Spacer(modifier = Modifier.height(28.dp))
                authContent()
            }

            Spacer(modifier = Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                secondaryAction()
                primaryAction()
            }
        }

        OtoGlassTopBar(
            glassEffectMode = glassEffectMode,
            hazeState = resolvedHazeState,
            onHeightChanged = { topBarHeightPx = it },
            modifier = Modifier.align(Alignment.TopCenter),
            title = { Text(title) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.back_content_description)
                    )
                }
            }
        )
    }
}

/**
 * Draws a compact source badge without introducing image assets for remote providers.
 * The stable square shape keeps the header aligned with the reference page while remaining theme-aware.
 */

/**
 * Standardizes section headings for remote login pages.
 * Centered headings make the form scan as server details followed by authentication details.
 */
@Composable
private fun RemoteSectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

/**
 * Applies the same field treatment to all remote-source inputs.
 * Keeping the styling local avoids changing the broader settings text-field behavior.
 */
@Composable
private fun RemoteTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    isError: Boolean = false,
    prefixText: String = ""
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotBlank()) {
            { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)) }
        } else {
            null
        },
        prefix = if (prefixText.isNotBlank()) {
            { Text(prefixText) }
        } else {
            null
        },
        singleLine = true,
        isError = isError,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Password field with visibility toggle icon.
 */
@Composable
private fun RemotePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = null
                )
            }
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Shows WebDAV test output directly under the authentication group.
 * The page keeps success and error feedback visible while the user decides whether to save the root.
 */
@Composable
private fun WebDavConnectionFeedback(connectionState: WebDavConnectionUiState) {
    if (connectionState.testSucceeded) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_webdav_connection_success),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
    connectionState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Shows ABS login output, server metadata, and the selectable library list after a successful test.
 * Library rows stay inside the page so choosing a remote library is part of the connection workflow.
 */
@Composable
private fun AbsConnectionFeedback(
    connectionState: AbsConnectionUiState,
    selectedLibraries: Map<String, String>,
    isEditMode: Boolean,
    onLibrarySelected: (String, String) -> Unit
) {
    if (connectionState.loginSucceeded) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.settings_abs_login_success),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
    connectionState.serverVersion?.let { version ->
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_abs_server_version, version),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (selectedLibraries.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        val selectedNames = selectedLibraries.values.joinToString(", ")
        Text(
            text = stringResource(R.string.settings_abs_selected_library, selectedNames),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth()
        )
    }
    connectionState.lastError?.takeIf { it.isNotBlank() }?.let { error ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth()
        )
    }
    if (connectionState.libraries.isNotEmpty()) {
        Spacer(modifier = Modifier.height(24.dp))
        RemoteSectionTitle(text = stringResource(R.string.settings_abs_select_library_title))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            connectionState.libraries.forEach { library ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onLibrarySelected(library.id, library.name) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp))
                    ) {
                        if (isEditMode) {
                            RadioButton(
                                selected = selectedLibraries.containsKey(library.id),
                                onClick = { onLibrarySelected(library.id, library.name) }
                            )
                        } else {
                            Checkbox(
                                checked = selectedLibraries.containsKey(library.id),
                                onCheckedChange = { onLibrarySelected(library.id, library.name) }
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${library.name} (${library.id})",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
