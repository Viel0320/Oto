package com.viel.aplayer.abs.sync

import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.ensureSupportedAbsServerVersion
import com.viel.aplayer.logger.AbsAuthLogger

data class AbsConnectionTestResult(
    val serverVersion: String?,
    val userId: String?,
    val username: String?,
    val bookLibraries: List<AbsLibraryDto>
)

/**
 * Connection Tester: Performs connection validation and library preparation.
 *
 * This tester is responsible for verifying connection details and preparing
 * library data before catalog synchronization.
 * Flow sequence:
 * 1. Request `/status` to retrieve the server version.
 * 2. Send `POST /api/authorize` to validate the authorization token.
 * 3. Fetch `/api/libraries` and filter to retain libraries with `mediaType=book`.
 */
class AbsConnectionTester(
    private val apiClient: AbsApiClient
) {
    suspend fun testConnection(baseUrl: String, token: String): AbsConnectionTestResult {
        val statusStart = AbsAuthLogger.mark()
        AbsAuthLogger.logStatusStart(baseUrl)
        val status = runCatching {
            apiClient.status(baseUrl).also { dto ->
                // Server Version Validation (Early failure check)
                // The server version must be validated immediately after the status call.
                // This ensures that unsupported, older versions of the server are rejected
                // at the connection stage, preventing half-compatibility issues later in synchronization or playback.
                ensureSupportedAbsServerVersion(dto.serverVersion)
            }
        }
            .onSuccess { dto ->
                AbsAuthLogger.logStatusSuccess(
                    baseUrl = baseUrl,
                    costMs = AbsAuthLogger.elapsedMs(statusStart),
                    serverVersion = dto.serverVersion
                )
            }
            .onFailure { error ->
                AbsAuthLogger.logStatusFailure(
                    baseUrl = baseUrl,
                    costMs = AbsAuthLogger.elapsedMs(statusStart),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
            }
            .getOrThrow()
        val authorizeStart = AbsAuthLogger.mark()
        AbsAuthLogger.logAuthorizeStart(baseUrl)
        val authorize = runCatching { apiClient.authorize(baseUrl, token) }
            .onSuccess { dto ->
                AbsAuthLogger.logAuthorizeSuccess(baseUrl, AbsAuthLogger.elapsedMs(authorizeStart), dto.user?.id)
            }
            .onFailure { error ->
                AbsAuthLogger.logAuthorizeFailure(
                    baseUrl = baseUrl,
                    costMs = AbsAuthLogger.elapsedMs(authorizeStart),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
            }
            .getOrThrow()
        val librariesStart = AbsAuthLogger.mark()
        AbsAuthLogger.logLibrariesStart(baseUrl)
        val libraries = runCatching { apiClient.getLibraries(baseUrl, token) }
            .onSuccess { result ->
                val bookLibraries = result.filter { it.mediaType.equals("book", ignoreCase = true) }
                AbsAuthLogger.logLibrariesSuccess(
                    baseUrl = baseUrl,
                    costMs = AbsAuthLogger.elapsedMs(librariesStart),
                    total = result.size,
                    books = bookLibraries.size
                )
            }
            .onFailure { error ->
                AbsAuthLogger.logLibrariesFailure(
                    baseUrl = baseUrl,
                    costMs = AbsAuthLogger.elapsedMs(librariesStart),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
            }
            .getOrThrow()
        val bookLibraries = libraries.filter { it.mediaType.equals("book", ignoreCase = true) }
        return AbsConnectionTestResult(
            serverVersion = status.serverVersion,
            userId = authorize.user?.id,
            username = authorize.user?.username,
            bookLibraries = bookLibraries
        )
    }
}
