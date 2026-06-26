package com.viel.oto.data.cover

import java.io.InputStream

/**
 * Persisted artwork result shared by data-owned cover records and media-owned image processors.
 *
 * Data code only needs the persisted original and thumbnail paths it can write back to Room; media adapters may
 * also populate [backgroundColor] for logging or future presentation without exposing parser-specific result types.
 */
data class CoverImageResult(
    val originalPath: String?,
    val thumbnailPath: String?,
    val backgroundColor: Int? = null
) {
    /**
     * Reports whether the adapter produced any persisted artwork file.
     */
    fun hasImage(): Boolean = originalPath != null || thumbnailPath != null

    companion object {
        val Empty = CoverImageResult(originalPath = null, thumbnailPath = null)
    }
}

/**
 * Narrow adapter contract for writing artwork bytes into Oto's cover cache.
 *
 * Implementations live with the image-processing code; data-layer gateways depend on this contract so manual cover
 * persistence can update Room paths without importing media parser implementation classes.
 */
interface CoverImageWriter {
    /**
     * Saves a user-selected external image as the manual cover for one book.
     */
    suspend fun saveCustomCoverFromUri(bookId: String, coverUriString: String): CoverImageResult

    /**
     * Saves embedded artwork bytes extracted from an audio file.
     */
    suspend fun saveEmbeddedImage(sourceId: String, artBytes: ByteArray): CoverImageResult

    /**
     * Saves an external sidecar or remote image stream without exposing cache storage details to the caller.
     */
    suspend fun processExternalImage(sourceId: String, openStream: suspend () -> InputStream?): CoverImageResult
}
