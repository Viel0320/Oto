package com.viel.oto.library.availability

import com.viel.oto.data.db.AudiobookSchema
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Centralizes protocol error-to-availability decisions.
 * Converts HTTP statuses and transport exceptions into the shared AvailabilityStatus language while preserving protocol-specific fallback semantics.
 */
object RemoteAvailabilityMappingPolicy {
    /**
     * Keeps remote protocol status translation behind one testable rule.
     * ABS treats unknown HTTP responses as UNKNOWN, while WebDAV treats unclassified HTTP failures as NETWORK_UNAVAILABLE to preserve existing provider behavior.
     */
    fun fromHttpStatus(statusCode: Int, protocol: RemoteAvailabilityProtocol): AudiobookSchema.AvailabilityStatus =
        when {
            statusCode == HTTP_UNAUTHORIZED -> AudiobookSchema.AvailabilityStatus.AUTH_FAILED
            statusCode == HTTP_FORBIDDEN -> AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED
            statusCode == HTTP_NOT_FOUND -> AudiobookSchema.AvailabilityStatus.NOT_FOUND
            statusCode == HTTP_REQUEST_TIMEOUT -> protocol.timeoutHttpStatus()
            statusCode == HTTP_GATEWAY_TIMEOUT && protocol == RemoteAvailabilityProtocol.WEBDAV ->
                AudiobookSchema.AvailabilityStatus.TIMEOUT
            statusCode in HTTP_SERVER_ERROR_RANGE -> AudiobookSchema.AvailabilityStatus.SERVER_ERROR
            protocol == RemoteAvailabilityProtocol.WEBDAV -> AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE
            else -> AudiobookSchema.AvailabilityStatus.UNKNOWN
        }

    /**
     * Separates timeout failures from general connectivity failures.
     * Providers use the returned code and status to keep ABS/WebDAV exception types stable while sharing the classification rule.
     */
    fun fromTransportException(error: IOException): RemoteTransportFailure =
        if (error is SocketTimeoutException) {
            RemoteTransportFailure(
                errorCode = "TIMEOUT",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                isTimeout = true
            )
        } else {
            RemoteTransportFailure(
                errorCode = "NETWORK_UNAVAILABLE",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
                isTimeout = false
            )
        }

    private fun RemoteAvailabilityProtocol.timeoutHttpStatus(): AudiobookSchema.AvailabilityStatus =
        when (this) {
            RemoteAvailabilityProtocol.WEBDAV -> AudiobookSchema.AvailabilityStatus.TIMEOUT
            RemoteAvailabilityProtocol.ABS -> AudiobookSchema.AvailabilityStatus.UNKNOWN
        }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_REQUEST_TIMEOUT = 408
    private const val HTTP_GATEWAY_TIMEOUT = 504
    private val HTTP_SERVER_ERROR_RANGE = 500..599
}

/**
 * Names the provider family whose fallback semantics are being applied.
 * The policy keeps protocol differences explicit so callers cannot silently inherit the wrong default for unclassified HTTP failures.
 */
enum class RemoteAvailabilityProtocol {
    ABS,
    WEBDAV
}

/**
 * Carries the shared status and protocol exception code together.
 * ABS callers need a compact error code, while WebDAV callers only need the availabilityStatus and timeout marker for message selection.
 */
data class RemoteTransportFailure(
    val errorCode: String,
    val availabilityStatus: AudiobookSchema.AvailabilityStatus,
    val isTimeout: Boolean
)
