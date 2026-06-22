package com.viel.aplayer.application.usecase

import androidx.core.net.toUri
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason

/**
 * Marks a new-source action as blocked because the exact library root is already registered.
 *
 * The exception carries only the source kind, keeping the duplicated location out of feedback aggregation,
 * logs, and toast identity while still letting the UI pick the correct resource-backed message.
 */
class DuplicateLibraryRootException(
    val sourceType: AudiobookSchema.LibrarySourceType
) : IllegalStateException("DUPLICATE_LIBRARY_ROOT:${sourceType.name}")

/**
 * Throws when a new WebDAV connection test targets an already registered endpoint and mount path.
 *
 * Edit flows pass the edited root id and deliberately bypass this guard so users can re-test an
 * existing source or move it to another endpoint without being blocked by the current row.
 */
internal fun requireUniqueWebDavRootForNewConnection(
    roots: List<LibraryRootEntity>,
    url: String,
    basePath: String,
    editingRootId: String?
) {
    if (hasExistingWebDavRootForNewConnection(roots, url, basePath, editingRootId)) {
        throw DuplicateLibraryRootException(AudiobookSchema.LibrarySourceType.WEBDAV)
    }
}

/**
 * Checks duplicate WebDAV roots using the same endpoint and base-path shape stored by registration.
 *
 * Different directories under the same WebDAV server remain valid separate roots, matching
 * LibraryRootStore's endpoint-plus-basePath deduplication behavior.
 */
internal fun hasExistingWebDavRootForNewConnection(
    roots: List<LibraryRootEntity>,
    url: String,
    basePath: String,
    editingRootId: String?
): Boolean {
    if (!editingRootId.isNullOrBlank()) return false
    val candidateEndpoint = normalizeWebDavEndpointForDuplicateGuard(url)
    val candidateBasePath = normalizeWebDavBasePathForDuplicateGuard(basePath, url)
    return roots.any { root ->
        root.sourceType == AudiobookSchema.LibrarySourceType.WEBDAV &&
            normalizeExistingWebDavEndpoint(root.sourceUri) == candidateEndpoint &&
            normalizeExistingWebDavBasePath(root.basePath) == candidateBasePath
    }
}

/**
 * Throws when a new Audiobookshelf save targets an already registered server and library.
 *
 * Audiobookshelf exposes several libraries from the same server, so duplicate detection must include
 * the selected library id and run after connection testing has populated the library picker.
 */
internal fun requireUniqueAbsRootForNewConnection(
    roots: List<LibraryRootEntity>,
    baseUrl: String,
    libraryId: String,
    editingRootId: String?
) {
    if (hasExistingAbsRootForNewConnection(roots, baseUrl, libraryId, editingRootId)) {
        throw DuplicateLibraryRootException(AudiobookSchema.LibrarySourceType.ABS)
    }
}

/**
 * Checks duplicate Audiobookshelf roots after canonical server URL normalization.
 *
 * Existing rows are normalized defensively so legacy rows with trailing slashes still participate, but
 * different library ids under the same server are allowed.
 */
internal fun hasExistingAbsRootForNewConnection(
    roots: List<LibraryRootEntity>,
    baseUrl: String,
    libraryId: String,
    editingRootId: String?
): Boolean {
    if (!editingRootId.isNullOrBlank()) return false
    val candidateBaseUrl = normalizeAbsBaseUrlForReuse(baseUrl)
    val candidateLibraryId = libraryId.trim()
    return roots.any { root ->
        root.sourceType == AudiobookSchema.LibrarySourceType.ABS &&
            normalizeExistingAbsBaseUrl(root.sourceUri) == candidateBaseUrl &&
            root.basePath == candidateLibraryId
    }
}

/**
 * Normalizes a WebDAV input URL to the stored endpoint form used by duplicate detection.
 *
 * The validation exceptions intentionally match WebDAV connection testing so invalid URLs still produce
 * the existing settings feedback before any duplicate comparison is attempted.
 */
internal fun normalizeWebDavEndpointForDuplicateGuard(url: String): String {
    val parsed = url.trim().toUri()
    val scheme = parsed.scheme?.lowercase()
        ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingScheme)
    val authority = parsed.encodedAuthority
        ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingHost)
    if (!parsed.encodedUserInfo.isNullOrBlank()) {
        throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UserInfoNotAllowed)
    }
    if (scheme != "http" && scheme != "https") {
        throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UnsupportedScheme)
    }
    return "$scheme://$authority"
}

/**
 * Normalizes the WebDAV mount path exactly like connection testing and root registration.
 *
 * A blank basePath inherits the URL path, allowing users to enter either "host + basePath" or a full
 * URL with a path while duplicate detection still compares the same stored root identity.
 */
internal fun normalizeWebDavBasePathForDuplicateGuard(basePath: String, url: String): String {
    val parsed = url.trim().toUri()
    val rawPath = basePath.ifBlank { parsed.path.orEmpty() }
    return android.net.Uri.decode(rawPath)
        .replace('\\', '/')
        .trim()
        .trim('/')
        .takeIf { it.isNotBlank() }
        ?.let { "/$it" }
        .orEmpty()
}

private fun normalizeExistingWebDavEndpoint(url: String): String =
    runCatching { normalizeWebDavEndpointForDuplicateGuard(url) }
        .getOrElse { url.trim().trimEnd('/') }

private fun normalizeExistingWebDavBasePath(basePath: String): String =
    android.net.Uri.decode(basePath)
        .replace('\\', '/')
        .trim()
        .trim('/')
        .takeIf { it.isNotBlank() }
        ?.let { "/$it" }
        .orEmpty()

private fun normalizeExistingAbsBaseUrl(baseUrl: String): String =
    runCatching { normalizeAbsBaseUrlForReuse(baseUrl) }
        .getOrElse { baseUrl.trim().trimEnd('/') }
