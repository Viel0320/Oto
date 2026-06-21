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
 * Owns VFS, playback runtime, and playback-file infrastructure.
 * Keeps media lifecycle and source construction dependencies separate from library mutation and durable data storage.
 */
@UnstableApi
internal class MediaGraph(
    val context: Context,
    val data: DataGraph
) : Closeable {
    val vfsRangeCache by lazy {
        VfsRangeCache(context.applicationContext)
    }

    val directoryListingCache by lazy {
        RoomDirectoryListingCache(
            directoryChildCacheDao = data.database.directoryChildCacheDao()
        )
    }

    private val playbackManagerLazy = lazy {
        PlaybackManager.getInstance(context)
    }

    val playbackManager: PlaybackManager by playbackManagerLazy

    val autoRewindManager: AutoRewindManager by lazy {
        AutoRewindManager.getInstance(context)
    }

    val playerPlaybackController: PlayerPlaybackController by lazy {
        DefaultPlayerPlaybackController(
            playbackManager = playbackManager,
            autoRewindManager = autoRewindManager
        )
    }

    val playbackStopper: PlaybackStopper by lazy {
        object : PlaybackStopper {
            override val currentPlayingBookId: String?
                get() = playbackManager.currentPlayingBookId

            override suspend fun stopPlayback() {
                playbackManager.stopPlayback()
            }
        }
    }

    val vfsFileInterface: VfsFileInterface by lazy {
        VfsFileInterface(
            context.applicationContext,
            libraryRootDao = data.database.libraryRootDao(),
            rangeCache = vfsRangeCache
        )
    }

    val playbackFileLookup: PlaybackFileLookup by lazy {
        DefaultPlaybackFileLookup(
            data.database.bookDao()
        )
    }

    val playbackRootLookup: PlaybackRootLookup by lazy {
        DefaultPlaybackRootLookup(
            data.database.libraryRootDao()
        )
    }

    val playbackSourcePreflight: PlaybackSourcePreflight by lazy {
        PlaybackSourcePreflight(data.database.libraryRootDao())
    }

    override fun close() {
        releaseInitializedMediaGraphResource(playbackManagerLazy) { playbackRuntime ->
            playbackRuntime.release()
        }
    }
}
