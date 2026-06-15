package com.viel.aplayer.media.cache

import android.net.Uri
import com.viel.aplayer.media.VfsPlaybackUri

object PlaybackCacheKeyPolicy {
    // Manual Cache Key Derivation (Maps internal VFS playback URIs to stable file-level cache keys)
    // Media3 downloads and manual-cache playback must share the same cached bytes, so the key is derived from BookFileEntity.id instead of transient ABS URLs or SAF paths.
    fun cacheKeyFor(uri: Uri, explicitKey: String? = null): String {
        val normalizedExplicitKey = explicitKey?.trim().takeUnless { it.isNullOrEmpty() }
        if (normalizedExplicitKey != null) return normalizedExplicitKey
        val bookFileId = VfsPlaybackUri.bookFileId(uri)
        return bookFileId ?: uri.toString()
    }
}
