package com.viel.oto.abs.vfs

import com.viel.oto.abs.net.AbsApiError
import com.viel.oto.abs.net.AbsTokenRefreshResult
import com.viel.oto.data.db.AudiobookSchema

class AbsAuthExpiredException(
    val credentialId: String,
    val rootId: String,
    val refreshResult: AbsTokenRefreshResult
) : AbsApiError(
    code = "ABS_AUTH_EXPIRED",
    httpStatus = HTTP_UNAUTHORIZED,
    availabilityStatus = AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
    message = "ABS media authorization expired for root $rootId"
) {
    private companion object {
        private const val HTTP_UNAUTHORIZED = 401
    }
}
