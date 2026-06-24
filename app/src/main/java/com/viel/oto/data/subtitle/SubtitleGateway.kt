package com.viel.oto.data.subtitle

import com.viel.oto.media.subtitle.SubtitleLine

/**
 * Application-facing subtitle loading seam.
 *
 * Exposes sidecar subtitle parsing independently from cover and metadata services so playback UI callers
 * can load captions without depending on cover asset or metadata refresh interfaces.
 */
interface SubtitleGateway {
    /**
     * VFS sidecar parsing.
     *
     * Resolves and parses subtitle files associated with one concrete audio track.
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine>
}
