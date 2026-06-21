package com.viel.aplayer.media.parser

import android.content.Context
import java.io.InputStream

/**
 * Compatibility shell for cover processing.
 *
 * Keeps the legacy class name and result type while forwarding actual cover persistence,
 * thumbnail generation, and color extraction to [ImageProcessor].
 */
class CoverExtractor(private val context: Context) {
    data class CoverResult(
        val originalPath: String?,
        val thumbnailPath: String?,
        val backgroundColor: Int? = null,
    )

    /**
     * Delegate custom cover stream processing to ImageProcessor.
     *
     * Enables callers to trigger center cropping and scaling operations directly from an external Content URI.
     */
    suspend fun saveCustomCoverFromUri(bookId: String, coverUriString: String): CoverResult =
        ImageProcessor.saveCustomCoverFromUri(context, bookId, coverUriString)

    /**
     * Persists embedded artwork bytes produced by audio metadata parsers.
     */
    suspend fun saveEmbeddedImage(sourceId: String, artBytes: ByteArray): CoverResult =
        ImageProcessor.saveEmbeddedImage(context, sourceId, artBytes)

    /**
     * Processes an external sidecar image stream without exposing storage details to callers.
     */
    suspend fun processExternalImage(sourceId: String, openStream: suspend () -> InputStream?): CoverResult =
        ImageProcessor.processExternalImage(context, sourceId, openStream)
}
