package com.viel.oto.media.parser

import com.viel.oto.data.cover.CoverImageResult
import com.viel.oto.data.cover.CoverImageWriter
import java.io.InputStream

/**
 * CoverImageWriter adapter backed by CoverExtractor.
 *
 * Keeps image decoding, resizing, and cache-file writes in the media parser package while returning the stable
 * data-layer result shape used by Room-facing cover gateways.
 */
class MediaCoverImageWriter(
    private val coverExtractor: CoverExtractor
) : CoverImageWriter {
    override suspend fun saveCustomCoverFromUri(bookId: String, coverUriString: String): CoverImageResult =
        coverExtractor.saveCustomCoverFromUri(bookId, coverUriString).toCoverImageResult()

    override suspend fun saveEmbeddedImage(sourceId: String, artBytes: ByteArray): CoverImageResult =
        coverExtractor.saveEmbeddedImage(sourceId, artBytes).toCoverImageResult()

    override suspend fun processExternalImage(
        sourceId: String,
        openStream: suspend () -> InputStream?
    ): CoverImageResult =
        coverExtractor.processExternalImage(sourceId, openStream).toCoverImageResult()
}

/**
 * Converts the legacy parser result into the cover result shared with data-layer persistence code.
 */
fun CoverExtractor.CoverResult.toCoverImageResult(): CoverImageResult =
    CoverImageResult(
        originalPath = originalPath,
        thumbnailPath = thumbnailPath,
        backgroundColor = backgroundColor
    )
