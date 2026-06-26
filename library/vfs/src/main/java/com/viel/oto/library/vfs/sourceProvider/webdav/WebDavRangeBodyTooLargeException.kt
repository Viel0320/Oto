package com.viel.oto.library.vfs.sourceProvider.webdav

import com.viel.oto.data.db.AudiobookSchema

/**
 * Reports a WebDAV byte-range response that kept streaming past the requested window.
 *
 * Keeping this as a typed WebDAV failure lets range diagnostics distinguish protocol-size
 * violations from ordinary HTTP or transport failures while still preserving the provider-specific
 * exception family expected by WebDAV callers.
 */
class WebDavRangeBodyTooLargeException(
    val requestedLength: Int,
    val observedBytes: Long
) : WebDavException(
    availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
    message = "RANGE_BODY_TOO_LARGE: WebDAV range response body exceeded requested length: requested=$requestedLength, observedAtLeast=$observedBytes"
) {
    val code: String = CODE

    companion object {
        const val CODE = "RANGE_BODY_TOO_LARGE"
    }
}
