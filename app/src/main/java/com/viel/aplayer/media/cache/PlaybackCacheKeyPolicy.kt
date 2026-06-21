package com.viel.aplayer.media.cache

import android.net.Uri
import com.viel.aplayer.media.VfsPlaybackUri

object PlaybackCacheKeyPolicy {
    fun cacheKeyFor(uri: Uri, explicitKey: String? = null): String {
        val normalizedExplicitKey = explicitKey?.trim().takeUnless { it.isNullOrEmpty() }
        if (normalizedExplicitKey != null) return normalizedExplicitKey
        val bookFileId = VfsPlaybackUri.bookFileId(uri)
        return bookFileId ?: uri.toString()
    }
}
