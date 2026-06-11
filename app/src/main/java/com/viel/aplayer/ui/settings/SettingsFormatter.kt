package com.viel.aplayer.ui.settings

import android.app.Application
import android.net.Uri
import com.viel.aplayer.R
import com.viel.aplayer.application.usecase.LibraryRootSettingsSnapshot
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestFailureReason
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.aplayer.network.UnsafeNetworkPolicyViolation
import com.viel.aplayer.ui.common.formatDate

/**
 * Settings Formatter (Extracts mapping and formatting logic for Settings screen UI)
 * Converts connection states, validation failures, library root statuses and SAF URIs into user-friendly text representations.
 */
object SettingsFormatter {

    // Title: Redact ABS Authorization Token (Mask sensitive bearer credentials in error payloads for secure logging and display)
    fun redactAbsError(error: String): String {
        return error.replace(Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE), "Bearer <redacted>")
    }

    // Title: Resolve Library Root Display Title (Build first-line label using WebDAV, ABS, or decoded SAF source identifiers)
    fun resolveLibraryRootTitle(root: LibraryRootSettingsSnapshot): String =
        when (root.sourceType) {
            AudiobookSchema.LibrarySourceType.WEBDAV ->
                root.displayName.ifBlank { "${root.sourceUri}${root.basePath}" }
            AudiobookSchema.LibrarySourceType.ABS ->
                root.displayName.ifBlank { "ABS ${root.basePath}" }
            else -> root.displayName.ifBlank {
                runCatching { Uri.decode(root.sourceUri).substringAfterLast(":") }
                    .getOrDefault(root.sourceUri)
            }
        }

    // Title: Resolve Library Root Status Text (Translate server connectivity and localized reachability statuses for root layouts)
    fun resolveLibraryRootStatusText(root: LibraryRootSettingsSnapshot, app: Application): String {
        val resId = when {
            root.status != AudiobookSchema.LibraryRootStatus.ACTIVE ->
                root.availabilityStatus
                    .takeIf { status -> status != AudiobookSchema.AvailabilityStatus.UNKNOWN }
                    ?.availabilityStatusMessageRes()
                    ?: root.status.libraryRootStatusMessageRes()
            root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN &&
                root.availabilityStatus != AudiobookSchema.AvailabilityStatus.AVAILABLE ->
                root.availabilityStatus.availabilityStatusMessageRes()
            root.sourceType == AudiobookSchema.LibrarySourceType.ABS && root.absLastError?.isNotBlank() == true ->
                R.string.settings_library_status_error
            root.sourceType == AudiobookSchema.LibrarySourceType.ABS && root.absLastFullSyncAt != null ->
                R.string.settings_library_status_synced
            root.sourceType == AudiobookSchema.LibrarySourceType.ABS ->
                R.string.settings_library_status_idle
            root.availabilityStatus != AudiobookSchema.AvailabilityStatus.UNKNOWN ->
                root.availabilityStatus.availabilityStatusMessageRes()
            else -> root.status.libraryRootStatusMessageRes()
        }
        return app.getString(resId)
    }

    // Title: Resolve Library Root Physical Location (Map system providers or remote urls to distinct address indicators)
    fun resolveLibraryRootLocation(root: LibraryRootSettingsSnapshot): String =
        when (root.sourceType) {
            AudiobookSchema.LibrarySourceType.WEBDAV -> "${root.sourceUri}${root.basePath}"
            AudiobookSchema.LibrarySourceType.ABS -> root.sourceUri
            else -> formatSafDisplayPath(root.sourceUri)
        }

    // Title: Format SAF Display Path (Decodes document provider URIs and filters redundant tree components to reveal clean sub-folders)
    fun formatSafDisplayPath(sourceUri: String): String {
        val decoded = runCatching { Uri.decode(sourceUri) }.getOrDefault(sourceUri)
        val primaryIndex = decoded.indexOf("primary:")
        return if (primaryIndex >= 0) decoded.substring(primaryIndex) else decoded
    }

    // Title: Format Library Root Sync Time (Render persisted timestamps using uniform local date formats or default empty fallbacks)
    fun formatLibraryRootSyncTime(timestampMs: Long?, notSyncedText: String): String =
        timestampMs?.takeIf { it > 0L }?.let(::formatDate) ?: notSyncedText

    // Title: Resolve Connection Failure Message (Map diverse network, SSL, and WebDAV domain errors into readable user messages)
    fun resolveConnectionFailureMessage(error: Throwable, app: Application): String {
        return when (error) {
            is UnsafeNetworkPolicyViolation ->
                app.getString(R.string.feedback_settings_cleartext_http_blocked)
            is WebDavEndpointValidationException ->
                app.getString(error.reason.webDavEndpointValidationMessageRes())
            is WebDavConnectionTestException ->
                if (error.reason == WebDavConnectionTestFailureReason.HttpStatus) {
                    app.getString(R.string.feedback_settings_webdav_http_status, error.httpCode ?: 0)
                } else {
                    app.getString(error.reason.webDavConnectionTestMessageRes())
                }
            is javax.net.ssl.SSLHandshakeException ->
                app.getString(R.string.feedback_settings_ssl_certificate_untrusted)
            is javax.net.ssl.SSLPeerUnverifiedException ->
                app.getString(R.string.feedback_settings_ssl_hostname_mismatch)
            else -> error.message ?: app.getString(R.string.feedback_settings_connection_failed_fallback)
        }
    }

    // Title: Map WebDAV Endpoint Reason (Link network syntax validation checks to localized error resource files)
    private fun WebDavEndpointValidationReason.webDavEndpointValidationMessageRes(): Int =
        when (this) {
            WebDavEndpointValidationReason.MissingScheme -> R.string.feedback_settings_webdav_url_missing_scheme
            WebDavEndpointValidationReason.MissingHost -> R.string.feedback_settings_webdav_url_missing_host
            WebDavEndpointValidationReason.UserInfoNotAllowed -> R.string.feedback_settings_webdav_url_userinfo_not_allowed
            WebDavEndpointValidationReason.UnsupportedScheme -> R.string.feedback_settings_webdav_url_unsupported_scheme
        }

    // Title: Map WebDAV Connection Reason (Translate backend remote HTTP response failures to user warning texts)
    private fun WebDavConnectionTestFailureReason.webDavConnectionTestMessageRes(): Int =
        when (this) {
            WebDavConnectionTestFailureReason.Unauthorized -> R.string.feedback_settings_webdav_auth_failed
            WebDavConnectionTestFailureReason.Forbidden -> R.string.feedback_settings_webdav_forbidden
            WebDavConnectionTestFailureReason.NotFound -> R.string.feedback_settings_webdav_not_found
            WebDavConnectionTestFailureReason.HttpStatus -> R.string.feedback_settings_webdav_http_status
        }

    // Title: Map Library Root Status (Link primary Room root state markers to visual status indicators)
    private fun AudiobookSchema.LibraryRootStatus.libraryRootStatusMessageRes(): Int =
        when (this) {
            AudiobookSchema.LibraryRootStatus.ACTIVE -> R.string.settings_library_status_active
            AudiobookSchema.LibraryRootStatus.REVOKED -> R.string.settings_library_status_revoked
            AudiobookSchema.LibraryRootStatus.ERROR -> R.string.settings_library_status_error
        }

    // Title: Map Storage Availability Status (Match physical and network reachability criteria to specific localized strings)
    private fun AudiobookSchema.AvailabilityStatus.availabilityStatusMessageRes(): Int =
        when (this) {
            AudiobookSchema.AvailabilityStatus.AVAILABLE -> R.string.settings_library_status_available
            AudiobookSchema.AvailabilityStatus.REVOKED -> R.string.settings_library_status_revoked
            AudiobookSchema.AvailabilityStatus.AUTH_FAILED -> R.string.settings_library_status_auth_failed
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE -> R.string.settings_library_status_network_unavailable
            AudiobookSchema.AvailabilityStatus.NOT_FOUND -> R.string.settings_library_status_not_found
            AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED -> R.string.settings_library_status_permission_denied
            AudiobookSchema.AvailabilityStatus.SERVER_ERROR -> R.string.settings_library_status_server_error
            AudiobookSchema.AvailabilityStatus.TIMEOUT -> R.string.settings_library_status_timeout
            AudiobookSchema.AvailabilityStatus.UNSUPPORTED -> R.string.settings_library_status_unsupported
            AudiobookSchema.AvailabilityStatus.UNKNOWN -> R.string.settings_library_status_unknown
        }
}
