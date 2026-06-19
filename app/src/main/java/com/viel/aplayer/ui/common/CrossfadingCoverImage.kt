package com.viel.aplayer.ui.common

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage

/**
 * Loaded-cover crossfade identity.
 *
 * The timestamp is part of the identity because regenerated artwork can reuse the same physical
 * path while still needing a visible transition to the refreshed bitmap. The variant is also part
 * of the identity because mini-player thumbnails and full-player artwork can share a path while
 * decoding to different bitmap sizes.
 */
private data class CrossfadingCoverKey(
    val sourcePath: String?,
    val lastUpdated: Long,
    val variant: CoverImageVariant
)

/**
 * Cover image renderer that waits for the next bitmap to load before starting the visible fade.
 *
 * Compose `Crossfade` starts when the model changes, but Coil may still be decoding the new image.
 * This component keeps the previous successful cover on screen, loads the next request underneath
 * an alpha gate, and only animates that gate after `AsyncImage` reports success or failure.
 * Only the settled layer owns accessibility text so temporary transition layers never duplicate
 * the same cover node while both bitmaps are present.
 */
@Composable
fun CrossfadingCoverImage(
    sourcePath: String?,
    lastUpdated: Long,
    variant: CoverImageVariant,
    scene: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    bitmapConfig: Bitmap.Config? = null,
    durationMillis: Int = 300,
    placeholder: @Composable BoxScope.() -> Unit
) {
    val targetKey = remember(sourcePath, lastUpdated, variant) {
        CrossfadingCoverKey(sourcePath, lastUpdated, variant)
    }
    var settledKey by remember { mutableStateOf(targetKey) }
    var incomingKey by remember { mutableStateOf<CrossfadingCoverKey?>(null) }
    var incomingReady by remember { mutableStateOf(false) }
    var failedKeys by remember { mutableStateOf(emptySet<CrossfadingCoverKey>()) }
    val incomingAlpha = remember { Animatable(1f) }

    LaunchedEffect(targetKey) {
        if (targetKey == settledKey) {
            incomingKey = null
            incomingReady = false
            incomingAlpha.snapTo(1f)
        } else {
            incomingKey = targetKey
            incomingReady = targetKey.sourcePath == null || failedKeys.contains(targetKey)
            incomingAlpha.snapTo(0f)
        }
    }

    LaunchedEffect(incomingKey, incomingReady) {
        val readyKey = incomingKey ?: return@LaunchedEffect
        if (!incomingReady) return@LaunchedEffect
        incomingAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis)
        )
        settledKey = readyKey
        incomingKey = null
        incomingReady = false
        incomingAlpha.snapTo(1f)
    }

    Box(modifier = modifier) {
        CrossfadingCoverLayer(
            coverKey = settledKey,
            failedKeys = failedKeys,
            scene = scene,
            contentDescription = contentDescription,
            contentScale = contentScale,
            allowHardware = allowHardware,
            bitmapConfig = bitmapConfig,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholder,
            onSuccess = {},
            onError = { failedKey ->
                failedKeys = failedKeys + failedKey
            }
        )

        val activeIncomingKey = incomingKey
        if (activeIncomingKey != null) {
            CrossfadingCoverLayer(
                coverKey = activeIncomingKey,
                failedKeys = failedKeys,
                scene = scene,
                contentDescription = null,
                contentScale = contentScale,
                allowHardware = allowHardware,
                bitmapConfig = bitmapConfig,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = incomingAlpha.value
                    },
                placeholder = placeholder,
                onSuccess = { successKey ->
                    if (incomingKey == successKey) {
                        incomingReady = true
                    }
                },
                onError = { failedKey ->
                    failedKeys = failedKeys + failedKey
                    if (incomingKey == failedKey) {
                        incomingReady = true
                    }
                }
            )
        }
    }
}

/**
 * Render one cover transition layer.
 *
 * A failed key is rendered through the caller's placeholder so the transition can still complete
 * toward a stable visual state instead of leaving a transparent target layer on screen.
 */
@Composable
private fun BoxScope.CrossfadingCoverLayer(
    coverKey: CrossfadingCoverKey,
    failedKeys: Set<CrossfadingCoverKey>,
    scene: String,
    contentDescription: String?,
    contentScale: ContentScale,
    allowHardware: Boolean,
    bitmapConfig: Bitmap.Config?,
    modifier: Modifier,
    placeholder: @Composable BoxScope.() -> Unit,
    onSuccess: (CrossfadingCoverKey) -> Unit,
    onError: (CrossfadingCoverKey) -> Unit
) {
    if (coverKey.sourcePath == null || failedKeys.contains(coverKey)) {
        Box(modifier = modifier, content = placeholder)
        return
    }

    val context = LocalContext.current
    val request = remember(context, coverKey, scene, allowHardware, bitmapConfig) {
        CoverImageRequestFactory.build(
            context = context,
            sourcePath = coverKey.sourcePath,
            lastUpdated = coverKey.lastUpdated,
            variant = coverKey.variant,
            scene = scene,
            allowHardware = allowHardware,
            bitmapConfig = bitmapConfig,
            crossfade = false
        )
    }

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        onSuccess = {
            onSuccess(coverKey)
        },
        onError = {
            onError(coverKey)
        }
    )
}
