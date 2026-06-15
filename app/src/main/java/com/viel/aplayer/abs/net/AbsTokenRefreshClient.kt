package com.viel.aplayer.abs.net

sealed interface AbsTokenRefreshResult {
    data class Success(val token: String) : AbsTokenRefreshResult
    data object Failed : AbsTokenRefreshResult
    data object TimedOut : AbsTokenRefreshResult
}

interface AbsTokenRefreshClient {
    // Token Refresh Boundary (Refreshes one credential record without exposing broader ABS API operations)
    // Stream and REST retry paths depend on this narrow contract so media providers do not learn catalog or playback APIs.
    suspend fun refreshToken(credentialId: String): AbsTokenRefreshResult
}
