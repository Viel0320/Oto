package com.viel.aplayer.application.playback

import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.BookPlaybackPlan
import com.viel.aplayer.media.PlaybackManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Player Playback Controller (Player-facing playback runtime seam)
 * Exposes only the playback commands and observable state required by the player scene, keeping singleton lookup and media runtime ownership outside UI code.
 */
interface PlayerPlaybackController {
    /**
     * Playback Activity Stream (Expose whether media is currently playing)
     * Lets player UI controls render play/pause state without depending on the concrete playback manager.
     */
    val isPlaying: StateFlow<Boolean>

    /**
     * Playback Lifecycle State Stream (Expose the underlying playback lifecycle state)
     * Keeps end-of-track handling available to PlayerViewModel while hiding the media manager instance.
     */
    val playbackState: StateFlow<Int>

    /**
     * Playback Position Stream (Expose the active global playback position)
     * Supports progress bars, chapter lookup, and restored-preview replacement through the player seam.
     */
    val currentPosition: StateFlow<Long>

    /**
     * Playback Duration Stream (Expose the active queue duration)
     * Allows UI progress calculations without reaching into playback runtime internals.
     */
    val duration: StateFlow<Long>

    /**
     * Playback Speed Stream (Expose the active playback speed)
     * Keeps speed controls reactive while preserving a small controller interface.
     */
    val playbackSpeed: StateFlow<Float>

    /**
     * Current Media Identifier Snapshot (Read the active media id without exposing MediaItem)
     * Lets duplicate-load checks compare the prepared media queue with restored preview state.
     */
    val currentMediaItemId: String?

    /**
     * Player Volume Bridge (Adjust playback-runtime volume for sleep fade-out)
     * The sleep timer needs volume attenuation but should not depend on PlaybackManager directly.
     */
    var playerVolume: Float

    /**
     * Current Media Identifier Stream (Observe active media id changes without exposing MediaItem)
     * PlayerViewModel parses the stable app media id while media-core details stay behind the adapter.
     */
    fun observeCurrentMediaItemId(): Flow<String?>

    /**
     * Play Command (Resume foreground playback)
     * Routes UI control requests through the media di adapter.
     */
    fun play()

    /**
     * Pause Command (Pause foreground playback)
     * Used by player controls, close actions, and sleep timer completion through a single player seam.
     */
    fun pause()

    /**
     * Seek Command (Move playback to a global position)
     * Keeps timeline coordinate mutation behind the playback controller interface.
     */
    fun seekTo(positionMs: Long)

    /**
     * Playback Speed Command (Apply user-selected speed)
     * Lets PlayerViewModel change speed without learning playback manager implementation details.
     */
    fun setPlaybackSpeed(speed: Float)

    /**
     * Playback Plan Load Command (Prepare a book playback plan)
     * The player scene supplies an application-level plan while the adapter invokes the media runtime.
     */
    fun loadPlaybackPlan(plan: BookPlaybackPlan, playWhenReady: Boolean)

    /**
     * Cold-Start Self-Healing Command (Repair persisted progress before preview restoration)
     * Keeps auto-rewind recovery available to the player scene without exposing AutoRewindManager.
     */
    suspend fun performColdStartSelfHealing()

    /**
     * Damaged Track Failover Command (Skip the current unavailable queue item)
     * Preserves the existing track recovery behavior behind the player playback seam.
     */
    fun skipToNextAvailableTrack(bookId: String, queueIndex: Int)
}

/**
 * Default Player Playback Controller (Adapts media managers to the player-facing seam)
 * MediaGraph owns the singleton managers and supplies them here so UI code never resolves those singletons directly.
 */
internal class DefaultPlayerPlaybackController(
    private val playbackManager: PlaybackManager,
    private val autoRewindManager: AutoRewindManager
) : PlayerPlaybackController {
    override val isPlaying: StateFlow<Boolean>
        get() = playbackManager.isPlaying

    override val playbackState: StateFlow<Int>
        get() = playbackManager.playbackState

    override val currentPosition: StateFlow<Long>
        get() = playbackManager.currentPosition

    override val duration: StateFlow<Long>
        get() = playbackManager.duration

    override val playbackSpeed: StateFlow<Float>
        get() = playbackManager.playbackSpeed

    override val currentMediaItemId: String?
        get() = playbackManager.currentMediaItem.value?.mediaId

    override var playerVolume: Float
        get() = playbackManager.playerVolume
        set(value) {
            playbackManager.playerVolume = value
        }

    override fun observeCurrentMediaItemId(): Flow<String?> {
        // Current Media Mapping (Expose only app media ids to player UI observers)
        // Mapping here avoids leaking Media3 MediaItem into PlayerViewModel and settings helpers.
        return playbackManager.currentMediaItem
            .map { mediaItem -> mediaItem?.mediaId }
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
}
