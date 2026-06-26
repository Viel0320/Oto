package com.viel.oto.ui.settings

import android.content.Context
import android.net.Uri
import com.viel.oto.shared.R
import com.viel.oto.application.library.settings.SettingsRootItem
import com.viel.oto.application.usecase.DuplicateLibraryRootException
import com.viel.oto.application.usecase.DuplicateLibraryRootSource
import com.viel.oto.application.usecase.LibraryRootSettingsSnapshot
import com.viel.oto.application.usecase.SettingsRootAvailabilityKind
import com.viel.oto.application.usecase.SettingsRootStatusKind
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavConnectionTestException
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavConnectionTestFailureReason
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.oto.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.oto.logger.AbsLogSanitizer
import com.viel.oto.network.UnsafeNetworkPolicyViolation
import java.util.Locale

/**
 * UI-owned formatter for settings root rows and connection failure copy.
 *
 * Application exposes structured snapshots and exceptions; this formatter owns Android resources,
 * locale-sensitive dates, and redacted display text for settings presentation.
 */
class SettingsRootFormatter(private val context: Context) {
    fun redactAbsError(error: String): String {
        return AbsLogSanitizer.sanitizeText(error)
    }

    fun resolveLibraryRootTitle(root: LibraryRootSettingsSnapshot): String =
        when {
            root.isWebDavRoot -> root.displayName.ifBlank { "${root.sourceUri}${root.basePath}" }
            root.isAbsRoot -> root.displayName.ifBlank { "ABS ${root.basePath}" }
            else -> root.displayName.ifBlank {
                runCatching { Uri.decode(root.sourceUri).substringAfterLast(":") }
                    .getOrDefault(root.sourceUri)
            }
        }

    fun resolveLibraryRootStatusText(root: LibraryRootSettingsSnapshot): String {
        val resId = when {
            !root.isActive ->
                root.availabilityKind
                    .takeIf { status -> status != SettingsRootAvailabilityKind.UNKNOWN }
                    ?.availabilityStatusMessageRes()
                    ?: root.statusKind.libraryRootStatusMessageRes()
            root.hasKnownAvailability && !root.isAvailable ->
                root.availabilityKind.availabilityStatusMessageRes()
            root.isAbsRoot && root.absLastError?.isNotBlank() == true ->
                R.string.settings_library_status_error
            root.isAbsRoot && root.absLastFullSyncAt != null ->
                R.string.settings_library_status_synced
            root.isAbsRoot ->
                R.string.settings_library_status_idle
            root.hasKnownAvailability ->
                root.availabilityKind.availabilityStatusMessageRes()
            else -> root.statusKind.libraryRootStatusMessageRes()
        }
        return context.getString(resId)
    }

    fun resolveLibraryRootLocation(root: LibraryRootSettingsSnapshot): String =
        when {
            root.isWebDavRoot -> "${root.sourceUri}${root.basePath}"
            root.isAbsRoot -> root.sourceUri
            else -> formatSafDisplayPath(root.sourceUri)
        }

    fun formatSafDisplayPath(sourceUri: String): String {
        val decoded = runCatching { Uri.decode(sourceUri) }.getOrDefault(sourceUri)
        val primaryIndex = decoded.indexOf("primary:")
        return if (primaryIndex >= 0) decoded.substring(primaryIndex) else decoded
    }

    fun formatLibraryRootSyncTime(timestampMs: Long?, notSyncedText: String): String =
        timestampMs?.takeIf { it > 0L }?.let(::formatDate) ?: notSyncedText

    /**
     * Maps connection-test failures to user-visible settings copy.
     *
     * Duplicate root guards are treated as local validation failures here so the form error and
     * toast use the same resource-backed message without pretending a network request failed.
     */
    fun resolveConnectionFailureMessage(error: Throwable): String {
        return when (error) {
            is DuplicateLibraryRootException ->
                context.getString(error.sourceType.duplicateRootMessageRes())
            is UnsafeNetworkPolicyViolation ->
                context.getString(R.string.feedback_settings_cleartext_http_blocked)
            is WebDavEndpointValidationException ->
                context.getString(error.reason.webDavEndpointValidationMessageRes())
            is WebDavConnectionTestException ->
                if (error.reason == WebDavConnectionTestFailureReason.HttpStatus) {
                    context.getString(R.string.feedback_settings_webdav_http_status, error.httpCode ?: 0)
                } else {
                    context.getString(error.reason.webDavConnectionTestMessageRes())
                }
            is javax.net.ssl.SSLHandshakeException ->
                context.getString(R.string.feedback_settings_ssl_certificate_untrusted)
            is javax.net.ssl.SSLPeerUnverifiedException ->
                context.getString(R.string.feedback_settings_ssl_hostname_mismatch)
            else -> error.message ?: context.getString(R.string.feedback_settings_connection_failed_fallback)
        }
    }

    fun formatSnapshot(snapshot: LibraryRootSettingsSnapshot): SettingsRootItem {
        val isAbsRoot = snapshot.isAbsRoot
        return SettingsRootItem(
            rootId = snapshot.rootId,
            sourceType = snapshot.sourceType,
            sourceUri = snapshot.sourceUri,
            basePath = snapshot.basePath,
            credentialId = snapshot.credentialId,
            displayName = snapshot.displayName,
            title = resolveLibraryRootTitle(snapshot),
            statusText = resolveLibraryRootStatusText(snapshot),
            locationText = resolveLibraryRootLocation(snapshot),
            selectedLibraryText = if (isAbsRoot) snapshot.displayName.ifBlank { snapshot.basePath } else null,
            lastSyncText = formatLibraryRootSyncTime(
                timestampMs = if (isAbsRoot) snapshot.absLastFullSyncAt else snapshot.lastScannedAt.takeIf { it > 0L },
                notSyncedText = context.getString(R.string.settings_library_not_synced)
            ),
            importedBookCount = snapshot.importedBookCount,
            lastError = snapshot.absLastError?.let(::redactAbsError)
        )
    }

    private fun formatDate(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(java.util.Date(ms))
    }

    private fun WebDavEndpointValidationReason.webDavEndpointValidationMessageRes(): Int =
        when (this) {
            WebDavEndpointValidationReason.MissingScheme -> R.string.feedback_settings_webdav_url_missing_scheme
            WebDavEndpointValidationReason.MissingHost -> R.string.feedback_settings_webdav_url_missing_host
            WebDavEndpointValidationReason.UserInfoNotAllowed -> R.string.feedback_settings_webdav_url_userinfo_not_allowed
            WebDavEndpointValidationReason.UnsupportedScheme -> R.string.feedback_settings_webdav_url_unsupported_scheme
        }

    private fun WebDavConnectionTestFailureReason.webDavConnectionTestMessageRes(): Int =
        when (this) {
            WebDavConnectionTestFailureReason.Unauthorized -> R.string.feedback_settings_webdav_auth_failed
            WebDavConnectionTestFailureReason.Forbidden -> R.string.feedback_settings_webdav_forbidden
            WebDavConnectionTestFailureReason.NotFound -> R.string.feedback_settings_webdav_not_found
            WebDavConnectionTestFailureReason.HttpStatus -> R.string.feedback_settings_webdav_http_status
        }

    private fun DuplicateLibraryRootSource.duplicateRootMessageRes(): Int =
        when (this) {
            DuplicateLibraryRootSource.WEBDAV -> R.string.feedback_settings_webdav_root_already_exists
            DuplicateLibraryRootSource.ABS -> R.string.feedback_settings_abs_root_already_exists
            DuplicateLibraryRootSource.SAF -> R.string.feedback_settings_connection_failed_fallback
        }

    private fun SettingsRootStatusKind.libraryRootStatusMessageRes(): Int =
        when (this) {
            SettingsRootStatusKind.ACTIVE -> R.string.settings_library_status_active
            SettingsRootStatusKind.REVOKED -> R.string.settings_library_status_revoked
            SettingsRootStatusKind.ERROR -> R.string.settings_library_status_error
        }

    private fun SettingsRootAvailabilityKind.availabilityStatusMessageRes(): Int =
        when (this) {
            SettingsRootAvailabilityKind.AVAILABLE -> R.string.settings_library_status_available
            SettingsRootAvailabilityKind.REVOKED -> R.string.settings_library_status_revoked
            SettingsRootAvailabilityKind.AUTH_FAILED -> R.string.settings_library_status_auth_failed
            SettingsRootAvailabilityKind.NETWORK_UNAVAILABLE -> R.string.settings_library_status_network_unavailable
            SettingsRootAvailabilityKind.NOT_FOUND -> R.string.settings_library_status_not_found
            SettingsRootAvailabilityKind.PERMISSION_DENIED -> R.string.settings_library_status_permission_denied
            SettingsRootAvailabilityKind.SERVER_ERROR -> R.string.settings_library_status_server_error
            SettingsRootAvailabilityKind.TIMEOUT -> R.string.settings_library_status_timeout
            SettingsRootAvailabilityKind.UNSUPPORTED -> R.string.settings_library_status_unsupported
            SettingsRootAvailabilityKind.UNKNOWN -> R.string.common_unknown
        }
}
