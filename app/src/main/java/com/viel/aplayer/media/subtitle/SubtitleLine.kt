package com.viel.aplayer.media.subtitle

/**
 * Media-domain caption cue.
 *
 * Represents one parsed external subtitle cue independently from Compose UI so parsers, resolvers,
 * playback plans, and screens can share the same media model without data-to-UI package coupling.
 */
data class SubtitleLine(
    val startTime: Long,
    val endTime: Long,
    val text: String
)
