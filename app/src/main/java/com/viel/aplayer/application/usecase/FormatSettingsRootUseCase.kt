package com.viel.aplayer.application.usecase

import android.content.Context
import android.net.Uri
import com.viel.aplayer.R
import com.viel.aplayer.application.library.settings.SettingsRootItem
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavConnectionTestFailureReason
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationException
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavEndpointValidationReason
import com.viel.aplayer.logger.AbsLogSanitizer
import com.viel.aplayer.network.UnsafeNetworkPolicyViolation
import java.util.Locale

/**
 * FormatSettingsRootUseCase: UseCase in the application layer to translate and format persisted
 * library root states into UI-friendly representational item data.
 */
class FormatSettingsRootUseCase(private val context: Context) {

    fun redactAbsError(error: String): String {
        return AbsLogSanitizer.sanitizeText(error)
    }

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

    fun resolveLibraryRootStatusText(root: LibraryRootSettingsSnapshot): String {
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
        return context.getString(resId)
    }

    fun resolveLibraryRootLocation(root: LibraryRootSettingsSnapshot): String =
        when (root.sourceType) {
            AudiobookSchema.LibrarySourceType.WEBDAV -> "${root.sourceUri}${root.basePath}"
            AudiobookSchema.LibrarySourceType.ABS -> root.sourceUri
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
        val isAbsRoot = snapshot.sourceType == AudiobookSchema.LibrarySourceType.ABS
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

    private fun AudiobookSchema.LibrarySourceType.duplicateRootMessageRes(): Int =
        when (this) {
            AudiobookSchema.LibrarySourceType.WEBDAV -> R.string.feedback_settings_webdav_root_already_exists
            AudiobookSchema.LibrarySourceType.ABS -> R.string.feedback_settings_abs_root_already_exists
            AudiobookSchema.LibrarySourceType.SAF -> R.string.feedback_settings_connection_failed_fallback
        }

    private fun AudiobookSchema.LibraryRootStatus.libraryRootStatusMessageRes(): Int =
        when (this) {
            AudiobookSchema.LibraryRootStatus.ACTIVE -> R.string.settings_library_status_active
            AudiobookSchema.LibraryRootStatus.REVOKED -> R.string.settings_library_status_revoked
            AudiobookSchema.LibraryRootStatus.ERROR -> R.string.settings_library_status_error
        }

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
            AudiobookSchema.AvailabilityStatus.UNKNOWN -> R.string.common_unknown
        }
}
