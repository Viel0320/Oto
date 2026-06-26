package com.viel.oto.shared.model

/**
 * Stable playback source block code shared across media checks and feedback mapping.
 * It is a pure value model so media can report lifecycle facts without forcing UI feedback code to
 * depend on the playback implementation module.
 */
enum class PlaybackSourcePreflightBlockReason {
    MissingRoot,
    UnavailableRoot
}
