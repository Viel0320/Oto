package com.viel.aplayer.data.service

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.data.gateway.SubtitleGateway
import com.viel.aplayer.media.subtitle.SubtitleFileResolver
import com.viel.aplayer.media.subtitle.SubtitleLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Subtitle Service (Dedicated subtitle sidecar loader)
 *
 * Wraps SubtitleFileResolver behind a small application service so subtitle loading stays separate from
 * cover asset persistence and metadata refresh modules.
 */
@OptIn(UnstableApi::class)
class SubtitleService(
    private val subtitleResolver: SubtitleFileResolver
) : SubtitleGateway {
    override suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine> = withContext(Dispatchers.IO) {
        // Subtitle Resolver Delegation (Keep VFS subtitle parsing localized behind SubtitleGateway)
        // Playback UI callers receive parsed subtitle lines through the facade without importing cover-domain services.
        subtitleResolver.loadSubtitlesForBookFile(bookFileId)
    }
}
