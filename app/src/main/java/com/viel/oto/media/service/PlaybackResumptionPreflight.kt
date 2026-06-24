package com.viel.oto.media.service

import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackDomainEvent
import com.viel.oto.media.PlaybackDomainEventSink
import com.viel.oto.media.PlaybackSourcePreflight
import com.viel.oto.media.PlaybackSourcePreflightResult
import com.viel.oto.shared.settings.AppSettings

/**
 * Keeps MediaSession resume aligned with foreground playback policy.
 * VFS media IDs hide the original root policy, so resumption must validate the BookPlaybackPlan before exposing MediaItems to Media3.
 */
internal class PlaybackResumptionPreflight(
    private val playbackSourcePreflight: PlaybackSourcePreflight,
    private val settingsProvider: suspend () -> AppSettings,
    private val playbackEventSink: PlaybackDomainEventSink
) {
    /**
     * Blocks MediaSession restoration before media-item construction.
     * Emits the same playback-domain events as normal foreground playback so UI feedback does not degrade into generic load failures.
     */
    suspend fun requireAvailable(plan: BookPlaybackPlan) {
        val settings = settingsProvider()
        when (val result = playbackSourcePreflight.check(plan, settings)) {
            PlaybackSourcePreflightResult.Available -> Unit
            PlaybackSourcePreflightResult.CleartextHttpBlocked -> {
                playbackEventSink.emit(PlaybackDomainEvent.CleartextPlaybackBlocked(bookTitle = plan.title))
                throw UnsupportedOperationException("Playback resumption blocked by cleartext policy")
            }
            is PlaybackSourcePreflightResult.Blocked -> {
                playbackEventSink.emit(
                    PlaybackDomainEvent.SourcePreflightBlocked(
                        reason = result.reason,
                        rootName = result.rootName,
                        bookTitle = plan.title
                    )
                )
                throw UnsupportedOperationException("Playback resumption blocked by source preflight")
            }
        }
    }
}
