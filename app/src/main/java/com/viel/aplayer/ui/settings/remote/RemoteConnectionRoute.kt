package com.viel.aplayer.ui.settings.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.aplayer.shared.settings.GlassEffectMode
import com.viel.aplayer.ui.settings.AbsConnectionPage
import com.viel.aplayer.ui.settings.WebDavConnectionPage
import dev.chrisbanes.haze.HazeState

/**
 * App-level overlay host for the WebDAV / AudiobookShelf connection forms.
 *
 * Visibility is derived from [RemoteConnectionViewModel.form]'s source, so the connection flow lives
 * beside the other top-level overlays rather than inside the settings overlay. It renders the existing
 * stateless [WebDavConnectionPage] / [AbsConnectionPage] (which carry their own top bar + insets) and
 * wires every field and action back to the view model. System back closes the overlay.
 */
@Composable
fun RemoteConnectionRoute(
    remoteConnectionViewModel: RemoteConnectionViewModel,
    glassEffectMode: GlassEffectMode,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val form by remoteConnectionViewModel.form.collectAsStateWithLifecycle()
    val visible = form.source != RemoteConnectionSource.None

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(300)) + fadeIn(tween(300)),
        exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(300)) + fadeOut(tween(300)),
        modifier = modifier
    ) {
        // Registered inside the visible content so it is added to the back dispatcher after the
        // settings overlay's handlers, giving the connection overlay back priority over settings.
        BackHandler(enabled = visible) { remoteConnectionViewModel.close() }
        Surface(modifier = Modifier.fillMaxSize()) {
            when (form.source) {
                RemoteConnectionSource.WebDav -> {
                    val state by remoteConnectionViewModel.webDavConnectionState.collectAsStateWithLifecycle()
                    WebDavConnectionPage(
                        url = form.webDavUrl,
                        username = form.webDavUsername,
                        password = form.webDavPassword,
                        displayName = form.webDavDisplayName,
                        basePath = form.webDavBasePath,
                        editingRootId = form.editingRootId,
                        connectionState = state,
                        glassEffectMode = glassEffectMode,
                        hazeState = hazeState,
                        onUrlChange = remoteConnectionViewModel::onWebDavUrlChange,
                        onUsernameChange = remoteConnectionViewModel::onWebDavUsernameChange,
                        onPasswordChange = remoteConnectionViewModel::onWebDavPasswordChange,
                        onDisplayNameChange = remoteConnectionViewModel::onWebDavDisplayNameChange,
                        onBasePathChange = remoteConnectionViewModel::onWebDavBasePathChange,
                        onTestConnection = remoteConnectionViewModel::testWebDav,
                        onBack = remoteConnectionViewModel::close,
                        onConfirm = remoteConnectionViewModel::confirmWebDav
                    )
                }

                RemoteConnectionSource.Abs -> {
                    val state by remoteConnectionViewModel.absConnectionState.collectAsStateWithLifecycle()
                    AbsConnectionPage(
                        baseUrl = form.absBaseUrl,
                        username = form.absUsername,
                        password = form.absPassword,
                        editingRootId = form.editingRootId,
                        connectionState = state,
                        glassEffectMode = glassEffectMode,
                        hazeState = hazeState,
                        selectedLibraryId = form.absLibraryId,
                        selectedLibraryName = form.absLibraryName,
                        onBaseUrlChange = remoteConnectionViewModel::onAbsBaseUrlChange,
                        onUsernameChange = remoteConnectionViewModel::onAbsUsernameChange,
                        onPasswordChange = remoteConnectionViewModel::onAbsPasswordChange,
                        onLibrarySelected = remoteConnectionViewModel::onAbsLibrarySelected,
                        onTestConnection = remoteConnectionViewModel::testAbs,
                        onBack = remoteConnectionViewModel::close,
                        onConfirm = remoteConnectionViewModel::confirmAbs
                    )
                }

                RemoteConnectionSource.None -> {}
            }
        }
    }
}
