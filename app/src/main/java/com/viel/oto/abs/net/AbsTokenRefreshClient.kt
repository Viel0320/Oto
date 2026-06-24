package com.viel.oto.abs.net

sealed interface AbsTokenRefreshResult {
    data class Success(val token: String) : AbsTokenRefreshResult
    data object Failed : AbsTokenRefreshResult
    data object TimedOut : AbsTokenRefreshResult
}

interface AbsTokenRefreshClient {
    suspend fun refreshToken(credentialId: String): AbsTokenRefreshResult
}
