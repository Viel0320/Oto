package com.viel.aplayer.abs.vfs

import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.abs.net.AbsTokenRefreshResult
import com.viel.aplayer.data.db.AudiobookSchema

// Stream Authentication Expiration (Carries a typed 401 failure after the provider attempts token refresh)
// Download orchestration can detect this exception in the Media3 failure cause chain and mark the current book task as FAILED without deleting partial cache data.
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
