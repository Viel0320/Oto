package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.data.AppSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.IOException

data class WebDavConnectionTestResult(
    val endpoint: String,
    val basePath: String,
    val targetUrl: String
)

/**
 * WebDAV Connection Tester (Validates settings credentials with a single Depth-0 PROPFIND)
 * SettingsViewModel receives only success or failure outcomes, while URL normalization, TLS selection, and HTTP response mapping stay behind this application-facing module.
 */
class WebDavConnectionTester(
    private val appSettingsRepository: AppSettingsRepository,
    private val clientFactory: WebDavHttpClientFactory = WebDavHttpClientFactory(
        connectTimeoutSeconds = 10,
        readTimeoutSeconds = 15
    )
) {
    suspend fun testConnection(
        url: String,
        username: String,
        password: String,
        basePath: String
    ): WebDavConnectionTestResult = withContext(Dispatchers.IO) {
        val normalizedEndpoint = normalizeWebDavEndpoint(url)
        val normalizedBasePath = normalizeWebDavBasePath(basePath, url)
        val targetUrl = if (normalizedBasePath.isEmpty()) normalizedEndpoint else "$normalizedEndpoint$normalizedBasePath"
        val request = Request.Builder()
            .url(targetUrl)
            .method("PROPFIND", WebDavProtocol.PROPFIND_ALL_PROPERTIES_BODY)
            .header("Depth", "0")
            .apply {
                if (username.isNotBlank() || password.isNotBlank()) {
                    // WebDAV Basic Auth Header (Compiles credentials only at request time)
                    // The tester never persists or exposes the password, preserving credential ownership in WebDavCredentialStore.
                    header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
                }
            }
            .build()

        val allowInsecureTls = appSettingsRepository.cachedSettings.isAllowInsecureTls
        clientFactory.clientFor(allowInsecureTls).newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == WebDavProtocol.HTTP_MULTI_STATUS) {
                return@withContext WebDavConnectionTestResult(
                    endpoint = normalizedEndpoint,
                    basePath = normalizedBasePath,
                    targetUrl = targetUrl
                )
            }
            throw IOException(response.toConnectionTestMessage())
        }
    }

    private fun normalizeWebDavEndpoint(url: String): String {
        val parsed = url.trim().toUri()
        val scheme = parsed.scheme?.lowercase() ?: throw IllegalArgumentException("WebDAV URL 缺少协议")
        val authority = parsed.encodedAuthority ?: throw IllegalArgumentException("WebDAV URL 缺少主机")
        require(scheme == "http" || scheme == "https") { "WebDAV URL 仅支持 http/https" }
        return "$scheme://$authority"
    }

    private fun normalizeWebDavBasePath(basePath: String, url: String): String {
        val parsed = url.trim().toUri()
        val rawPath = basePath.ifBlank { parsed.path.orEmpty() }
        return Uri.decode(rawPath)
            .replace('\\', '/')
            .trim()
            .trim('/')
            .takeIf { it.isNotBlank() }
            ?.let { "/$it" }
            .orEmpty()
    }

    private fun okhttp3.Response.toConnectionTestMessage(): String =
        when (code) {
            WebDavProtocol.HTTP_UNAUTHORIZED -> "认证失败（用户名或密码错误）"
            WebDavProtocol.HTTP_FORBIDDEN -> "服务器拒绝访问（403 禁止访问）"
            WebDavProtocol.HTTP_NOT_FOUND -> "未找到路径，请检查 URL 和库内路径"
            else -> "连接失败，HTTP 状态码: $code"
        }
}
