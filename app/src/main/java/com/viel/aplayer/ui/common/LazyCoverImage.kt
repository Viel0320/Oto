package com.viel.aplayer.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage

/**
 * Defers Coil request construction until a cover slot has been positioned by a lazy parent.
 *
 * Lazy lists can compose or measure nearby items before they become visible. This helper keeps
 * the expensive cover request behind the first real layout placement, so list rows and card rows
 * do not start decoding thumbnails while they are still only precomposed work. The placement gate
 * flips the request state in one step to avoid a per-cover two-frame recomposition burst during
 * dense Home flings.
 */
@Composable
fun LazyCoverImage(
    sourcePath: String?,
    lastUpdated: Long,
    variant: CoverImageVariant,
    scene: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    allowHardware: Boolean = true,
    bitmapConfig: Bitmap.Config? = null,
    crossfade: Boolean = true,
    placeholder: @Composable () -> Unit
) {
    val isPreview = LocalInspectionMode.current
    val context = LocalContext.current
    var canStartRequest by remember(sourcePath, lastUpdated) { mutableStateOf(false) }
    var isImageError by remember(sourcePath, lastUpdated) { mutableStateOf(false) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (!canStartRequest && sourcePath != null && coordinates.size != IntSize.Zero) {
                canStartRequest = true
            }
        }
    ) {
        val imageSourcePath = sourcePath
        if (canStartRequest && !isPreview && imageSourcePath != null && !isImageError) {
            val request = remember(
                context,
                imageSourcePath,
                lastUpdated,
                variant,
                scene,
                allowHardware,
                bitmapConfig,
                crossfade
            ) {
                CoverImageRequestFactory.build(
                    context = context,
                    sourcePath = imageSourcePath,
                    lastUpdated = lastUpdated,
                    variant = variant,
                    scene = scene,
                    allowHardware = allowHardware,
                    bitmapConfig = bitmapConfig,
                    crossfade = crossfade
                )
            }
            AsyncImage(
                model = request,
                contentDescription = null,
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
