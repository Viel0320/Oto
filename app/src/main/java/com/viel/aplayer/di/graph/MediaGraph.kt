package com.viel.aplayer.di.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.application.playback.DefaultPlayerPlaybackController
import com.viel.aplayer.application.playback.PlaybackStopper
import com.viel.aplayer.application.playback.PlayerPlaybackController
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.media.AutoRewindManager
import com.viel.aplayer.media.DefaultPlaybackFileLookup
import com.viel.aplayer.media.DefaultPlaybackRootLookup
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackManager
import com.viel.aplayer.media.PlaybackRootLookup
import com.viel.aplayer.media.PlaybackSourcePreflight
import java.io.Closeable

/**
 * Media Graph (Owns VFS, playback runtime, and playback-file infrastructure)
 * Keeps media lifecycle and source construction dependencies separate from library mutation and durable data storage.
 */
@UnstableApi
internal class MediaGraph(
    val context: Context,
    val data: DataGraph
) : Closeable {
    val vfsRangeCache by lazy {
        // Range Cache Initialization: Prepares a dedicated cache registry for metadata extraction blocks.
        VfsRangeCache(context.applicationContext)
    }

    val directoryListingCache by lazy {
        // Directory Listing Cache: Cache for directory child folders to optimize scan latency.
        RoomDirectoryListingCache(
            directoryChildCacheDao = data.database.directoryChildCacheDao()
        )
    }

    private val playbackManagerLazy = lazy {
        // Playback Manager Initialization (Own media-runtime singleton in the media di)
        // This keeps foreground playback lifecycle ownership out of DataGraph's persistence-focused responsibilities.
        PlaybackManager.getInstance(context)
    }

    val playbackManager: PlaybackManager by playbackManagerLazy

    val autoRewindManager: AutoRewindManager by lazy {
        // Auto Rewind Manager Initialization (Own recovery playback collaborator in the media di)
        // Self-healing playback progress belongs beside the playback runtime rather than the durable data di.
        AutoRewindManager.getInstance(context)
    }

    val playerPlaybackController: PlayerPlaybackController by lazy {
        // Player Playback Controller Adapter (Expose player-scene playback operations through an application seam)
        // MediaGraph owns the media singletons, so UI callers receive a compact controller instead of resolving managers from Context.
        DefaultPlayerPlaybackController(
            playbackManager = playbackManager,
            autoRewindManager = autoRewindManager
        )
    }

    val playbackStopper: PlaybackStopper by lazy {
        // Playback Stopper Adapter (Expose only deletion-safe playback lifecycle control)
        // Deletion use cases can stop active playback without importing PlaybackManager or learning media runtime details.
        object : PlaybackStopper {
            override val currentPlayingBookId: String?
                get() = playbackManager.currentPlayingBookId

            override suspend fun stopPlayback() {
                playbackManager.stopPlayback()
            }
        }
    }

    val vfsFileInterface: VfsFileInterface by lazy {
        // Vfs File Interface: Interconnects local files and remote directories with unified VFS logic.
        VfsFileInterface(
            context.applicationContext,
            libraryRootDao = data.database.libraryRootDao(),
            rangeCache = vfsRangeCache
        )
    }

    val playbackFileLookup: PlaybackFileLookup by lazy {
        // Playback File Lookup: Associates book identifiers to their respective audio files.
        DefaultPlaybackFileLookup(
            data.database.bookDao()
        )
    }

    val playbackRootLookup: PlaybackRootLookup by lazy {
        // Playback Root Lookup (Resolve root source type for manual-cache routing)
        // Manual-cache playback needs this read-only source classification without receiving mutable library-root commands.
        DefaultPlaybackRootLookup(
            data.database.libraryRootDao()
        )
    }

    val playbackSourcePreflight: PlaybackSourcePreflight by lazy {
        // Playback Preflight Guard: Evaluates book root availability before constructing media sources.
        PlaybackSourcePreflight(data.database.libraryRootDao())
    }

    override fun close() {
        // Playback Runtime Teardown (Release initialized playback resources during container close)
        // The lazy guard prevents diagnostics or tests from constructing PlaybackManager solely to shut it down.
        releaseInitializedMediaGraphResource(playbackManagerLazy) { playbackRuntime ->
            playbackRuntime.release()
        }
    }
}
