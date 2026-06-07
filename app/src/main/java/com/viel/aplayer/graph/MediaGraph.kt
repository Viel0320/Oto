package com.viel.aplayer.graph

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.library.vfs.VfsFileInterface
import com.viel.aplayer.library.vfs.cache.RoomDirectoryListingCache
import com.viel.aplayer.library.vfs.cache.VfsRangeCache
import com.viel.aplayer.media.PlaybackFileLookup
import com.viel.aplayer.media.PlaybackSourcePreflight

/**
 * Media Graph (Owns VFS and playback-file infrastructure)
 * Keeps media-source construction dependencies separate from library mutation and ABS catalog synchronization.
 */
@UnstableApi
internal class MediaGraph(
    val context: Context,
    val data: DataGraph
) {
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
        com.viel.aplayer.media.DefaultPlaybackFileLookup(
            data.database.bookDao()
        )
    }

    val playbackSourcePreflight: PlaybackSourcePreflight by lazy {
        // Playback Preflight Guard: Evaluates book root availability before constructing media sources.
        PlaybackSourcePreflight(data.database.libraryRootDao())
    }
}
