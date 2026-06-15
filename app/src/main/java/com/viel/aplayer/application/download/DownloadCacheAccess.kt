package com.viel.aplayer.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache

@OptIn(UnstableApi::class)
interface DownloadCacheAccess {
    /**
     * Manual Download Cache (L1 persistent cache owned by explicit user download commands)
     * Playback may read this cache first, but only DownloadManager is allowed to write manual offline bytes into it.
     */
    val manualCache: Cache

}

@OptIn(UnstableApi::class)
class DefaultDownloadCacheAccess(
    private val manualCacheProvider: () -> Cache
) : DownloadCacheAccess {
    // Manual Cache Lazy Boundary (Resolve L1 cache only when playback or download code asks for cache handles)
    // This keeps app startup from locking cache directories before any remote media path needs them.
    override val manualCache: Cache
        get() = manualCacheProvider()
}
