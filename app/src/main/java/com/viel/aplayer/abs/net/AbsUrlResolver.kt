package com.viel.aplayer.abs.net

import com.viel.aplayer.data.db.AudiobookSchema
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Unified helper to resolve and normalize AudiobookShelf URLs.
 *
 * REST client, Cover Cache, and VFS provider. share the same URL sanitization,
 * trailing-slash trimming, and structural endpoint construction logic.
 */
internal object AbsUrlResolver {

    /**
     * Normalizes and parses the ABS baseUrl into a structured HttpUrl object.
     * Throws an AbsApiError if the URL is invalid or blank.
     */
    fun resolveBaseUrl(baseUrl: String): HttpUrl {
        val trimmed = baseUrl.trim()
        require(trimmed.isNotBlank()) { "ABS baseUrl 不能为空" }
        val normalized = trimmed.trimEnd('/')
        return normalized.toHttpUrlOrNull()
            ?: throw AbsApiError(
                code = "INVALID_BASE_URL",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "Invalid ABS baseUrl: $normalized"
            )
    }

    /**
     * Resolves a standard ABS API endpoint URL.
     */
    fun resolveApiUrl(baseUrl: String, endpoint: String): HttpUrl =
        resolveBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment(endpoint)
            .build()

    /**
     * Resolves the ABS cover endpoint URL for a given item ID.
     * Centralizes the cover URL structure to avoid scattered string concatenation.
     */
    fun resolveCoverUrl(baseUrl: String, itemId: String): HttpUrl =
        resolveBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment("items")
            .addPathSegment(itemId)
            .addPathSegment("cover")
            .build()
}
