package com.viel.oto.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage

/**
 * Render one cover image request without retaining outgoing artwork layers.
 *
 * This keeps the cover-loading boundary centralized while intentionally avoiding bitmap transition
 * state, alpha animation, or old/new layer overlap so callers own any transition design.
 *
 * @param onSuccess Invoked once Coil reports the request decoded. Callers that gate a fade on the
 * target bitmap being on-screen use this so a not-yet-decoded layer can never flash in.
 * @param onError Invoked once Coil reports the request failed; the placeholder is shown locally and
 * gated callers can still release their transition toward a stable state.
 */
@Composable
fun CoverImage(
    sourcePath: String?,
    lastUpdated: Long,
    variant: CoverImageVariant,
    scene: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    bitmapConfig: Bitmap.Config? = null,
    onSuccess: (() -> Unit)? = null,
    onError: (() -> Unit)? = null,
    placeholder: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    var isImageError by remember(sourcePath, lastUpdated, variant) { mutableStateOf(false) }

    Box(modifier = modifier) {
        if (sourcePath != null && !isImageError) {
            val request = remember(context, sourcePath, lastUpdated, variant, scene, allowHardware, bitmapConfig) {
                CoverImageRequestFactory.build(
                    context = context,
                    sourcePath = sourcePath,
                    lastUpdated = lastUpdated,
                    variant = variant,
                    scene = scene,
                    allowHardware = allowHardware,
                    bitmapConfig = bitmapConfig
                )
            }

            AsyncImage(
                model = request,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                onSuccess = { onSuccess?.invoke() },
                onError = {
                    isImageError = true
                    onError?.invoke()
                }
            )
        } else {
            placeholder()
        }
    }
}
