package com.viel.aplayer.media.cache

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackCacheKeyPolicyTest {
    @Test
    fun `vfs book file uri should use file-level cache key`() {
        val uri = Uri.parse("aplayer-vfs://book-file/file-1")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri)

        // File-Level Cache Identity (Locks shared playback/download storage to BookFileEntity.id)
        // ABS content URLs and SAF document paths may change, but the imported book-file identity remains stable across playback and manual download requests.
        assertEquals("file-1", key)
    }

    @Test
    fun `explicit cache key should win over uri derivation`() {
        val uri = Uri.parse("aplayer-vfs://book-file/file-1")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri, explicitKey = "download:file-1")

        // Explicit Key Precedence (Allows Media3 requests that already carry a custom key to stay authoritative)
        // This keeps the policy compatible with future request builders while still providing a safe fallback for normal VFS playback URIs.
        assertEquals("download:file-1", key)
    }

    @Test
    fun `non vfs uri should fall back to uri string`() {
        val uri = Uri.parse("https://example.test/audio.mp3")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri)

        // Unknown URI Fallback (Preserves Media3 behavior for non-VFS requests without fabricating book identities)
        // Manual-cache playback normally expects VFS URIs, but this fallback keeps diagnostics deterministic if a future source bypasses VFS.
        assertEquals("https://example.test/audio.mp3", key)
    }
}
