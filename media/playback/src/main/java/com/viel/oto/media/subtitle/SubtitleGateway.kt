package com.viel.oto.media.subtitle

/**
 * Media-facing subtitle loading seam.
 *
 * Keeps sidecar subtitle discovery and parsing outside the data store while giving player-scene callers
 * one small contract for loading captions by the active audio track id.
 */
interface SubtitleGateway {
    /**
     * VFS sidecar parsing.
     *
     * Resolves and parses subtitle files associated with one concrete audio track.
     */
    suspend fun loadSubtitlesForBookFile(bookFileId: String): List<SubtitleLine>
}
