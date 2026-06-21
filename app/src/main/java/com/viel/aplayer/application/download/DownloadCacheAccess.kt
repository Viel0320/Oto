package com.viel.aplayer.application.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache

@OptIn(UnstableApi::class)
interface DownloadCacheAccess {
    /**
     * L1 persistent cache owned by explicit user download commands.
     * Playback may read this cache first, but only DownloadManager is allowed to write manual offline bytes into it.
     */
    val manualCache: Cache

}

@OptIn(UnstableApi::class)
class DefaultDownloadCacheAccess(
    private val manualCacheProvider: () -> Cache
) : DownloadCacheAccess {
    override val manualCache: Cache
        get() = manualCacheProvider()
}
