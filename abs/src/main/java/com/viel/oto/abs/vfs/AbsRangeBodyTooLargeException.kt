package com.viel.oto.abs.vfs

import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.data.db.AudiobookSchema

/**
 * Reports an ABS byte-range response that kept streaming past the requested window.
 *
 * The exception is separate from generic HTTP failures so playback and diagnostics can identify a
 * protocol-size violation without reading the rest of a potentially large media response into heap.
 */
class AbsRangeBodyTooLargeException(
    val requestedLength: Int,
    val observedBytes: Long,
    httpStatus: Int?
) : AbsApiError(
    code = CODE,
    httpStatus = httpStatus,
    availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
    message = "ABS range response body exceeded requested length: requested=$requestedLength, observedAtLeast=$observedBytes"
) {
    companion object {
        const val CODE = "RANGE_BODY_TOO_LARGE"
    }
}
