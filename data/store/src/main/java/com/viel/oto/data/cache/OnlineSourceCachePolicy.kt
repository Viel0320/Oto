package com.viel.oto.data.cache

/**
 * Centralizes TTL fallback rules for remote-derived cache rows.
 * Version checks, fingerprints, and ETags remain the first invalidation layer; these limits only prevent online caches
 * from being trusted forever when a provider omits or misreports its freshness metadata.
 */
object OnlineSourceCachePolicy {
    const val ONLINE_DIRECTORY_LISTING_TTL_MS: Long = 60_000L
    const val LIBRARY_ROOT_CACHE_TTL_MS: Long = 60_000L
    const val ONLINE_METADATA_RANGE_TTL_MS: Long = 6L * 60L * 60L * 1000L
    const val ONLINE_VERSIONLESS_RANGE_TTL_MS: Long = 30L * 60L * 1000L
    const val ABS_CATALOG_MIRROR_TTL_MS: Long = 24L * 60L * 60L * 1000L
    const val ABS_COVER_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val ABS_PLAYBACK_SESSION_TTL_MS: Long = 30L * 60L * 1000L
    const val ABS_PENDING_PROGRESS_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L
    const val ABS_AUTHORIZED_PROGRESS_TTL_MS: Long = 60L  * 1000L

    /**
     * Converts a TTL into a persisted timestamp floor.
     * DAO callers can compare cachedAt or updatedAt with the returned value without duplicating overflow and negative-clock guards.
     */
    fun minCachedAt(nowMillis: Long, ttlMillis: Long): Long =
        nowMillis.minus(ttlMillis).coerceAtLeast(0L)

    /**
     * Returns whether a persisted online snapshot is still inside its TTL window.
     * Null timestamps are treated as stale because online caches without a write time cannot be safely bounded.
     */
    fun isFresh(cachedAtMillis: Long?, nowMillis: Long, ttlMillis: Long): Boolean {
        val cachedAt = cachedAtMillis ?: return false
        return cachedAt >= minCachedAt(nowMillis = nowMillis, ttlMillis = ttlMillis)
    }

    /**
     * Chooses stricter expiry when no provider version token is present.
     * ETag-backed blocks may live longer because the key already changes with server versions, while versionless blocks rely on TTL more heavily.
     */
    fun rangeTtlMillis(hasProviderVersion: Boolean): Long =
        if (hasProviderVersion) ONLINE_METADATA_RANGE_TTL_MS else ONLINE_VERSIONLESS_RANGE_TTL_MS
}
