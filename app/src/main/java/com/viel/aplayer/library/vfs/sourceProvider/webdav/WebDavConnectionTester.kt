package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.net.Uri
import androidx.core.net.toUri
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.network.UnsafeNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request

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
        val settings = appSettingsRepository.cachedSettings
        // Unsafe Network Preflight (Block HTTP test requests before Basic Auth headers are sent)
        // Connection testing uses the same global policy as VFS reads so a successful test cannot authorize a root that playback would later reject.
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(targetUrl, settings, "WebDAV connection test")
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

        val allowInsecureTls = UnsafeNetworkPolicy.isInsecureTlsAllowed(settings)
        clientFactory.clientFor(allowInsecureTls).newCall(request).execute().use { response ->
            if (response.isSuccessful || response.code == WebDavProtocol.HTTP_MULTI_STATUS) {
                return@withContext WebDavConnectionTestResult(
                    endpoint = normalizedEndpoint,
                    basePath = normalizedBasePath,
                    targetUrl = targetUrl
                )
            }
            throw response.toConnectionTestFailure()
        }
    }

    private fun normalizeWebDavEndpoint(url: String): String {
        val parsed = url.trim().toUri()
        val scheme = parsed.scheme?.lowercase()
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingScheme)
        val authority = parsed.encodedAuthority
            ?: throw WebDavEndpointValidationException(WebDavEndpointValidationReason.MissingHost)
        if (!parsed.encodedUserInfo.isNullOrBlank()) {
            // WebDAV Userinfo Rejection (Keep credentials in credential storage, not endpoint URLs)
            // Connection tests share root normalization rules so secret-bearing endpoint URLs are blocked before any authenticated PROPFIND can be built.
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UserInfoNotAllowed)
        }
        if (scheme != "http" && scheme != "https") {
            throw WebDavEndpointValidationException(WebDavEndpointValidationReason.UnsupportedScheme)
        }
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

    private fun okhttp3.Response.toConnectionTestFailure(): WebDavConnectionTestException =
        when (code) {
            WebDavProtocol.HTTP_UNAUTHORIZED ->
                WebDavConnectionTestException(WebDavConnectionTestFailureReason.Unauthorized, code)
            WebDavProtocol.HTTP_FORBIDDEN ->
                WebDavConnectionTestException(WebDavConnectionTestFailureReason.Forbidden, code)
            WebDavProtocol.HTTP_NOT_FOUND ->
                WebDavConnectionTestException(WebDavConnectionTestFailureReason.NotFound, code)
            else -> WebDavConnectionTestException(WebDavConnectionTestFailureReason.HttpStatus, code)
        }
}
