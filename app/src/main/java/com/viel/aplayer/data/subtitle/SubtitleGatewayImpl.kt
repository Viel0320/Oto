package com.viel.aplayer.data.subtitle

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Dedicated subtitle sidecar loader.
 *
 * Wraps SubtitleFileResolver behind a small application service so subtitle loading stays separate from
 * cover asset persistence and metadata refresh modules.
 */
@OptIn(UnstableApi::class)
class SubtitleGatewayImpl(
    private val subtitleResolver: SubtitleFileResolver
) : SubtitleGateway {
    override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> = withContext(Dispatchers.IO) {
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)
    }
}
