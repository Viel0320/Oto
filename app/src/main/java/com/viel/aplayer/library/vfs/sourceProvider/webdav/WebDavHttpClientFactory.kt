package com.viel.aplayer.library.vfs.sourceProvider.webdav

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebDAV HTTP Client Factory (Builds trusted and explicitly unsafe clients for every WebDAV caller)
 * Settings connection tests and VFS reads share the same TLS policy implementation, preventing certificate bypass logic from living in multiple modules.
 */
class WebDavHttpClientFactory(
    connectTimeoutSeconds: Long,
    readTimeoutSeconds: Long,
    callTimeoutSeconds: Long? = null
) {
    // Unsafe Trust Manager (Accepts self-signed certificates only when the caller explicitly opts into insecure TLS)
    // The instance is private to this factory so certificate bypass behavior cannot be enabled accidentally by unrelated network code.
    private val unsafeTrustManager = @SuppressLint("CustomX509TrustManager")
    object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // Trusted Client Baseline (Owns timeout policy while preserving OkHttp connection-pool reuse)
    // The unsafe client is derived from this client so both variants share transport tuning and differ only in TLS verification.
    private val trustedClient = OkHttpClient.Builder()
        .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
        .apply {
            callTimeoutSeconds?.let { seconds -> callTimeout(seconds, TimeUnit.SECONDS) }
        }
        .build()

    private val unsafeClient = trustedClient.newBuilder()
        .sslSocketFactory(createUnsafeSslSocketFactory(), unsafeTrustManager)
        .hostnameVerifier { _, _ -> true }
        .build()

    fun clientFor(allowInsecureTls: Boolean): OkHttpClient =
        if (allowInsecureTls) unsafeClient else trustedClient

    private fun createUnsafeSslSocketFactory() =
        SSLContext.getInstance("TLS").apply {
            // Unsafe TLS Context (Scopes certificate bypass to WebDAV endpoints only)
            // The caller still has to request the unsafe client explicitly through clientFor(true).
            init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
        }.socketFactory
}
