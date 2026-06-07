package com.viel.aplayer.data.gateway

import com.viel.aplayer.media.subtitle.SubtitleLine

/**
 * Subtitle Gateway (Application-facing subtitle loading seam)
 *
 * Exposes sidecar subtitle parsing independently from cover and metadata services so playback UI callers
 * can load captions without depending on cover asset or metadata refresh interfaces.
 */
interface SubtitleGateway {
    /**
     * Load Subtitles For Book File (VFS sidecar parsing)
     *
     * Resolves and parses subtitle files associated with one concrete audio track.
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine>
}
