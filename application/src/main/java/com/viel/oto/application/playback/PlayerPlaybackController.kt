package com.viel.oto.application.playback

import com.viel.oto.media.AutoRewindManager
import com.viel.oto.media.BookPlaybackPlan
import com.viel.oto.media.PlaybackMediaId
import com.viel.oto.media.PlaybackManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Stable playback identity exposed to player-facing application callers.
 * The media layer still owns the encoded Media3 id format, while UI only receives
 * the book and file coordinates needed for scene state and subtitle loading.
 */
data class PlaybackMediaIdentity(
    val bookId: String,
    val fileId: String
)

/**
 * Player-facing playback runtime seam.
 * Exposes only the playback commands and observable state required by the player scene, keeping singleton lookup and media runtime ownership outside UI code.
 */
interface PlayerPlaybackController {
    /**
     * Expose whether media is currently playing.
     * Lets player UI controls render play/pause state without depending on the concrete playback manager.
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * Expose the underlying playback lifecycle state.
     * Keeps end-of-track handling available to PlayerViewModel while hiding the media manager instance.
     */
    val playbackState: StateFlow<Int>

    /**
     * Expose the active global playback position.
     * Supports progress bars, chapter lookup, and restored-preview replacement through the player seam.
     */
    val currentPosition: StateFlow<Long>

    /**
     * Expose the active global buffered playback position.
     * Allows progress bars to render memory-buffer coverage without depending on Media3 controller internals.
     */
    val bufferedPosition: StateFlow<Long>

    /**
     * Expose the active queue duration.
     * Allows UI progress calculations without reaching into playback runtime internals.
     */
    val duration: StateFlow<Long>

    /**
     * Expose the active playback speed.
     * Keeps speed controls reactive while preserving a small controller interface.
     */
    val playbackSpeed: StateFlow<Float>

    /**
     * Read the active media identity without exposing MediaItem or encoded media ids.
     * Lets duplicate-load checks compare the prepared media queue with restored preview state.
     */
    val currentPlaybackMediaIdentity: PlaybackMediaIdentity?

    /**
     * Adjust playback-runtime volume for sleep fade-out.
     * The sleep timer needs volume attenuation but should not depend on PlaybackManager directly.
     */
    var playerVolume: Float

    /**
     * Observe active media identity changes without exposing MediaItem or encoded media ids.
     * Keeps Media3 identifier parsing inside the application adapter instead of UI state holders.
     */
    fun observeCurrentPlaybackMediaIdentity(): Flow<PlaybackMediaIdentity?>

    /**
     * Resume foreground playback.
     * Routes UI control requests through the media di adapter.
     */
    fun play()

    /**
     * Pause foreground playback.
     * Used by player controls, close actions, and sleep timer completion through a single player seam.
     */
    fun pause()

    /**
     * Move playback to a global position.
     * Keeps timeline coordinate mutation behind the playback controller interface.
     */
    fun seekTo(positionMs: Long)

    /**
     * Apply user-selected speed.
     * Lets PlayerViewModel change speed without learning playback manager implementation details.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Prepare a book playback plan.
     * The player scene supplies an application-level plan while the adapter invokes the media runtime.
     */
    fun loadPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean)

    /**
     * Repair persisted progress before preview restoration.
     * Keeps auto-rewind recovery available to the player scene without exposing AutoRewindManager.
     */
    suspend fun performColdStartSelfHealing()

    /**
     * Skip the current unavailable queue item.
     * Preserves the existing track recovery behavior behind the player playback seam.
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int)
}

/**
 * Adapts media managers to the player-facing seam.
 * MediaGraph owns the singleton managers and supplies them here so UI code never resolves those singletons directly.
 */
class DefaultPlayerPlaybackController(
    private val playbackManager: PlaybackManager,
    private val autoRewindManager: AutoRewindManager
) : PlayerPlaybackController {
    override val isPlaying: StateFlow<Boolean>
        get() = playbackManager.isPlaying

    override val playbackState: StateFlow<Int>
        get() = playbackManager.playbackState

    override val currentPosition: StateFlow<Long>
        get() = playbackManager.currentPosition

    override val bufferedPosition: StateFlow<Long>
        get() = playbackManager.bufferedPosition

    override val duration: StateFlow<Long>
        get() = playbackManager.duration

    override val playbackSpeed: StateFlow<Float>
        get() = playbackManager.playbackSpeed

    override val currentPlaybackMediaIdentity: PlaybackMediaIdentity?
        get() = playbackManager.currentMediaItem.value?.mediaId.toPlaybackMediaIdentity()

    override var playerVolume: Float
        get() = playbackManager.playerVolume
        set(value) {
            playbackManager.playerVolume = value
        }

    override fun observeCurrentPlaybackMediaIdentity(): Flow<PlaybackMediaIdentity?> {
        return playbackManager.currentMediaItem
            .map { mediaItem -> mediaItem?.mediaId.toPlaybackMediaIdentity() }
            .distinctUntilChanged()
    }

    override fun play() = playbackManager.play()

    override fun pause() = playbackManager.pause()

    override fun seekTo(positionMs: Long) = playbackManager.seekTo(positionMs)

    override fun setPlaybackSpeed(speed: Float) = playbackManager.setPlaybackSpeed(speed)

    override fun loadPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean) {
        playbackManager.setBookPlaybackPlan(plan, playWhenReady)
    }

    override suspend fun performColdStartSelfHealing() {
        autoRewindManager.performColdStartSelfHealing()
    }

    override fun skipToNextAvailableTrack(bookId: String, queueIndex: Int) {
        playbackManager.skipToNextAvailableTrack(bookId, queueIndex)
    }

    /**
     * Converts the media-owned encoded id into an application playback identity.
     */
    private fun String?.toPlaybackMediaIdentity(): PlaybackMediaIdentity? {
        val parts = PlaybackMediaId.parse(this) ?: return null
        return PlaybackMediaIdentity(bookId = parts.bookId, fileId = parts.fileId)
    }
}
