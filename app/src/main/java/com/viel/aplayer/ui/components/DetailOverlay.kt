package com.viel.aplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.viel.aplayer.ui.screens.DetailScreen
import com.viel.aplayer.ui.viewmodel.LibraryViewModel
import com.viel.aplayer.ui.viewmodel.PlayerViewModel

/**
 * 书籍详情悬浮层组件。
 * 将详情页的显示逻辑与动画从主 App 容器中解耦。
 */
@Composable
fun DetailOverlay(
    libraryViewModel: LibraryViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavController,
    canStartNavigation: () -> Boolean,
    modifier: Modifier = Modifier
) {
    val detailUiState by libraryViewModel.detailUiState.collectAsStateWithLifecycle()
    val playerUiState by playerViewModel.uiState.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = detailUiState.isVisible,
        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(400)) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(400)) + fadeOut(),
        modifier = modifier
    ) {
        DetailScreen(
            uiState = if (playerUiState.currentUri == detailUiState.book?.uri && playerUiState.duration > 0) {
                val realTimePercent = kotlin.math.ceil(
                    playerUiState.currentPosition.toDouble() / playerUiState.duration.toDouble() * 100
                ).toInt().coerceIn(0, 100)
                detailUiState.copy(progressPercent = realTimePercent)
            } else {
                detailUiState
            },
            onBackClick = { libraryViewModel.setDetailVisible(false) },
            onSearchClick = { query ->
                libraryViewModel.setDetailVisible(false)
                if (canStartNavigation() && navController.currentBackStackEntry?.destination?.route?.startsWith("search") != true) {
                    navController.navigate("search?q=${android.net.Uri.encode(query)}") {
                        launchSingleTop = true
                    }
                }
            },
            onPlayClick = {
                detailUiState.book?.let { book ->
                    playerViewModel.loadMedia(
                        uri = book.uri.toUri(),
                        title = book.title,
                        author = book.author,
                        narrator = book.narrator,
                        startPositionMs = book.lastPosition
                    )
                }
                playerViewModel.setFullPlayerVisible(true)
            }
        )
    }
}
