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
 * 阶段 1 的连接测试器只负责连接验证与选库前数据准备。
 *
 * 流程固定：
 * 1. 读取 `/status` 获取 serverVersion。
 * 2. 用 POST `/api/authorize` 验证 token。
 * 3. 拉取 `/api/libraries` 并只保留 `mediaType=book`。
 */
class AbsConnectionTester(
    private val apiClient: AbsApiClient
) {
    suspend fun testConnection(baseUrl: String, token: String): AbsConnectionTestResult {
        val statusStart = AbsAuthLogger.mark()
        AbsAuthLogger.logStatusStart(baseUrl)
        val status = runCatching {
            apiClient.status(baseUrl).also { dto ->
                // 详尽的中文注释：连接阶段必须在 `/status` 返回后立刻校验版本下限，
                // 这样低版本服务器会在“测试连接”这一步直接被拒绝，而不是等到后面的 catalog 或播放链路才出现半兼容故障。
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
