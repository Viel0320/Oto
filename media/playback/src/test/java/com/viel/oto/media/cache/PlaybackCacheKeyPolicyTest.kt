package com.viel.oto.media.cache

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins Robolectric to the app minimum SDK because cache-key derivation only needs Android Uri
 * parsing and should not depend on the module compile SDK selected for production builds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlaybackCacheKeyPolicyTest {
    @Test
    fun `vfs book file uri should use file-level cache key`() {
        val uri = Uri.parse("oto-vfs://book-file/file-1")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri)

        assertEquals("file-1", key)
    }

    @Test
    fun `explicit cache key should win over uri derivation`() {
        val uri = Uri.parse("oto-vfs://book-file/file-1")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri, explicitKey = "download:file-1")

        assertEquals("download:file-1", key)
    }

    @Test
    fun `non vfs uri should fall back to uri string`() {
        val uri = Uri.parse("https://example.test/audio.mp3")

        val key = PlaybackCacheKeyPolicy.cacheKeyFor(uri)

        assertEquals("https://example.test/audio.mp3", key)
    }
}
