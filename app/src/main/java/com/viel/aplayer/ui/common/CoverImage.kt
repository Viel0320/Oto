package com.viel.aplayer.ui.common

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
 * state, alpha animation, or old/new layer overlap so the next image-transition design can start
 * from a plain image renderer.
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
                onError = {
                    isImageError = true
                }
            )
        } else {
            placeholder()
        }
    }
}
