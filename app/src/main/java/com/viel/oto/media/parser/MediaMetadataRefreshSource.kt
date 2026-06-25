package com.viel.oto.media.parser

import com.viel.oto.data.entity.BookFileEntity
import com.viel.oto.data.metadata.MetadataRefreshRecord
import com.viel.oto.data.metadata.MetadataRefreshSource
import com.viel.oto.media.AudiobookMetadata

/**
 * MetadataRefreshSource adapter backed by the media parser router.
 *
 * Keeps format detection, range parsing, title repair, and chapter extraction in media/parser while exposing the
 * smaller data-layer projection required for forced Room metadata refresh.
 */
class MediaMetadataRefreshSource(
    private val metadataResolver: MetadataResolver
) : MetadataRefreshSource {
    override suspend fun extract(file: BookFileEntity): MetadataRefreshRecord =
        metadataResolver.extract(file).toMetadataRefreshRecord()

    private fun AudiobookMetadata.toMetadataRefreshRecord(): MetadataRefreshRecord =
        MetadataRefreshRecord(
            title = title,
            author = author,
            narrator = narrator,
            album = album,
            description = description,
            year = year,
            durationMs = durationMs,
            chapters = chapters
        )
}
