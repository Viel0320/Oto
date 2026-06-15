package com.viel.aplayer.data.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSourceCachePolicyTest {

    @Test
    fun `ttl constants should match online source fallback plan`() {
        // Online TTL Contract (Locks remote cache fallback windows in one policy object)
        // These constants keep WebDAV and ABS callers from scattering independent freshness durations across infrastructure code.
        assertEquals(60_000L, OnlineSourceCachePolicy.ONLINE_DIRECTORY_LISTING_TTL_MS)
        assertEquals(6L * 60L * 60L * 1000L, OnlineSourceCachePolicy.ONLINE_METADATA_RANGE_TTL_MS)
        assertEquals(30L * 60L * 1000L, OnlineSourceCachePolicy.ONLINE_VERSIONLESS_RANGE_TTL_MS)
        assertEquals(24L * 60L * 60L * 1000L, OnlineSourceCachePolicy.ABS_CATALOG_MIRROR_TTL_MS)
        assertEquals(7L * 24L * 60L * 60L * 1000L, OnlineSourceCachePolicy.ABS_COVER_TTL_MS)
        assertEquals(30L * 60L * 1000L, OnlineSourceCachePolicy.ABS_PLAYBACK_SESSION_TTL_MS)
        assertEquals(7L * 24L * 60L * 60L * 1000L, OnlineSourceCachePolicy.ABS_PENDING_PROGRESS_TTL_MS)
        // Match the updated 60-second TTL contract for ABS authorized progress
        assertEquals(60L * 1000L, OnlineSourceCachePolicy.ABS_AUTHORIZED_PROGRESS_TTL_MS)
    }

    @Test
    fun `freshness should use inclusive timestamp lower bound`() {
        val now = 10_000L
        val ttl = 2_500L

        // Inclusive Lower Bound (Preserves cache rows written exactly at the TTL boundary)
        // DAO predicates use >=, so the in-memory policy mirrors the same boundary for non-DAO cache decisions.
        assertEquals(7_500L, OnlineSourceCachePolicy.minCachedAt(nowMillis = now, ttlMillis = ttl))
        assertTrue(OnlineSourceCachePolicy.isFresh(cachedAtMillis = 7_500L, nowMillis = now, ttlMillis = ttl))
        assertFalse(OnlineSourceCachePolicy.isFresh(cachedAtMillis = 7_499L, nowMillis = now, ttlMillis = ttl))
        assertFalse(OnlineSourceCachePolicy.isFresh(cachedAtMillis = null, nowMillis = now, ttlMillis = ttl))
    }

    @Test
    fun `range ttl should be shorter without provider version`() {
        // Versionless Range Guard (Makes TTL carry more weight when a remote source lacks ETag or modified-time identity)
        // Versioned range blocks keep the six-hour metadata cache window, while versionless blocks fall back to thirty minutes.
        assertEquals(
            OnlineSourceCachePolicy.ONLINE_METADATA_RANGE_TTL_MS,
            OnlineSourceCachePolicy.rangeTtlMillis(hasProviderVersion = true)
        )
        assertEquals(
            OnlineSourceCachePolicy.ONLINE_VERSIONLESS_RANGE_TTL_MS,
            OnlineSourceCachePolicy.rangeTtlMillis(hasProviderVersion = false)
        )
    }
}
