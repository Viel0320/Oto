package com.viel.oto.ui.libraryManagement

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.viel.oto.shared.settings.GlassEffectMode
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

    var isPredictiveBackActive by remember { mutableStateOf(false) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)) + fadeIn(tween(300)),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300)) + fadeOut(tween(300)),
        modifier = modifier
    ) {
        // Registered inside the visible content so it is added to the back dispatcher after the
        // settings overlay's handlers, giving the connection overlay back priority over settings.
        PredictiveBackHandler(enabled = visible) { progressFlow ->
            try {
                progressFlow.collect { backEvent ->
                    isPredictiveBackActive = true
                    predictiveBackProgress = backEvent.progress
                }
                remoteConnectionViewModel.close()
            } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            } finally {
                isPredictiveBackActive = false
                predictiveBackProgress = 0f
            }
        }

        val density = LocalDensity.current
        val maxPredictiveTranslationY = with(density) { 200.dp.toPx() }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isPredictiveBackActive) {
                        translationY = predictiveBackProgress * maxPredictiveTranslationY
                        alpha = 1f - predictiveBackProgress * 0.3f
                    }
                }
        ) {
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
                        selectedLibraries = form.absSelectedLibraries,
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
