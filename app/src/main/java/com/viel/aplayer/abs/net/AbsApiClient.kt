package com.viel.aplayer.abs.net

import android.annotation.SuppressLint
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.viel.aplayer.abs.net.dto.AbsAuthorizeResponseDto
import com.viel.aplayer.abs.net.dto.AbsBatchGetItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsLibrariesResponseDto
import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemDto
import com.viel.aplayer.abs.net.dto.AbsLibraryItemsResponseDto
import com.viel.aplayer.abs.net.dto.AbsLoginResponseDto
import com.viel.aplayer.abs.net.dto.AbsPlayRequestDto
import com.viel.aplayer.abs.net.dto.AbsPlaybackSessionDto
import com.viel.aplayer.abs.net.dto.AbsStatusDto
import com.viel.aplayer.abs.net.dto.AbsUserProgressDto
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.library.availability.RemoteAvailabilityMappingPolicy
import com.viel.aplayer.library.availability.RemoteAvailabilityProtocol
import com.viel.aplayer.logger.AbsAuthLogger
import com.viel.aplayer.network.UnsafeNetworkPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Server Version Constraints (First ABS integration only supports server versions greater than or equal to 2.35.1)
 * This baseline is solid based on current API responses, method structures, and track parameters.
 * Lower server versions are rejected immediately during connectivity handshake checks to prevent partial incompatibilities.
 */
internal const val MIN_SUPPORTED_ABS_SERVER_VERSION: String = "2.35.1"

interface AbsApiClient {
    suspend fun status(baseUrl: String): AbsStatusDto
    suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto
    suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto
    suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto>
    suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto
    suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto>
    /**
     * Remote Progress Probe (Reads the user's item progress without mutating playback session state)
     * Implementations should return null for ABS 404 responses because a missing remote progress record is a valid no-progress state.
     */
    suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? = null
    suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto
    suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double)
    suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double)
}

/**
 * ABS API Transport Boundary (OkHttp requests and Moshi parsing rules are consolidated within this class)
 */
class RealAbsApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val appSettingsRepository: AppSettingsRepository? = null,
    private val settingsProvider: () -> AppSettings = {
        appSettingsRepository?.cachedSettings ?: AppSettings()
    },
    // Pure Codegen: Instantiate Moshi without reflection support since all DTOs have generated adapters.
    private val moshi: Moshi = Moshi.Builder().build()
) : AbsApiClient {

    // Unsafe Trust Manager: Custom trust manager that bypasses certificate path validation checks for self-signed servers.
    private val unsafeTrustManager = @SuppressLint("CustomX509TrustManager")
    object : X509TrustManager {
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        @SuppressLint("TrustAllX509TrustManager")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // Unsafe SSL Socket Factory: SSL context initialization bypassing active validation routines using the unsafe trust manager.
    private val unsafeSslSocketFactory = run {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(unsafeTrustManager), SecureRandom())
        sslContext.socketFactory
    }

    // Unsafe Hostname Verifier: Null hostname verifier accepting any server hostname.
    private val unsafeHostnameVerifier = HostnameVerifier { _, _ -> true }

    // Unsafe OkHttpClient: Reconfigured OkHttpClient using client.newBuilder() to share the connection pool but bypass SSL checks.
    private val unsafeClient = client.newBuilder()
        .sslSocketFactory(unsafeSslSocketFactory, unsafeTrustManager)
        .hostnameVerifier(unsafeHostnameVerifier)
        .build()

    // Resolve client instance: Dynamically return client or unsafeClient depending on the global unsafe network policy.
    private fun getClient(): OkHttpClient {
        val allowInsecure = UnsafeNetworkPolicy.isInsecureTlsAllowed(settingsProvider())
        return if (allowInsecure) unsafeClient else client
    }
    private val statusAdapter: JsonAdapter<AbsStatusDto> = moshi.adapter(AbsStatusDto::class.java)
    private val loginAdapter: JsonAdapter<AbsLoginResponseDto> = moshi.adapter(AbsLoginResponseDto::class.java)
    private val authorizeAdapter: JsonAdapter<AbsAuthorizeResponseDto> = moshi.adapter(AbsAuthorizeResponseDto::class.java)
    private val librariesAdapter: JsonAdapter<AbsLibrariesResponseDto> = moshi.adapter(AbsLibrariesResponseDto::class.java)
    private val libraryItemsAdapter: JsonAdapter<AbsLibraryItemsResponseDto> = moshi.adapter(AbsLibraryItemsResponseDto::class.java)
    private val batchGetItemsAdapter: JsonAdapter<AbsBatchGetItemsResponseDto> = moshi.adapter(AbsBatchGetItemsResponseDto::class.java)
    private val progressAdapter: JsonAdapter<AbsUserProgressDto> = moshi.adapter(AbsUserProgressDto::class.java)
    private val playbackSessionAdapter: JsonAdapter<AbsPlaybackSessionDto> = moshi.adapter(AbsPlaybackSessionDto::class.java)
    private val playRequestAdapter: JsonAdapter<AbsPlayRequestDto> = moshi.adapter(AbsPlayRequestDto::class.java)

    override suspend fun status(baseUrl: String): AbsStatusDto =
        executeJson(
            request = Request.Builder()
                .url(resolveBaseUrl(baseUrl).newBuilder().addPathSegment("status").build())
                .get()
                .build(),
            adapter = statusAdapter
        )

    override suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto {
        // Logging Auth Requests (Bypasses legacy logging wrappers to emit logs directly to the dedicated AbsAuthLogger)
        val start = AbsAuthLogger.mark()
        AbsAuthLogger.logLoginRequestStart(baseUrl, username)
        val body = """{"username":${username.toJsonString()},"password":${password.toJsonString()}}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        return runCatching {
            executeJson(
                request = Request.Builder()
                    .url(resolveBaseUrl(baseUrl).newBuilder().addPathSegment("login").build())
                    .post(body)
                    .build(),
                adapter = loginAdapter
            )
        }.onSuccess { response ->
            AbsAuthLogger.logLoginRequestSuccess(
                baseUrl = baseUrl,
                username = username,
                costMs = AbsAuthLogger.elapsedMs(start),
                resolvedUsername = response.user?.username
            )
        }.onFailure { error ->
            AbsAuthLogger.logLoginRequestFailure(
                baseUrl = baseUrl,
                username = username,
                costMs = AbsAuthLogger.elapsedMs(start),
                errorClass = error::class.java.simpleName,
                message = error.message
            )
        }.getOrThrow()
    }

    override suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto =
        executeJson(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "authorize"))
                // Enforce POST Request (Requires POST method explicitly as defined in integration parameters)
                .post(EMPTY_JSON_BODY)
                .header("Authorization", bearer(token))
                .build(),
            adapter = authorizeAdapter
        )

    override suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto> =
        executeJson(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "libraries"))
                .get()
                .header("Authorization", bearer(token))
                .build(),
            adapter = librariesAdapter
        ).libraries.orEmpty()

    override suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto =
        executeJson(
            request = Request.Builder()
                .url(
                    resolveApiUrl(baseUrl, "libraries")
                        .newBuilder()
                        .addPathSegment(libraryId)
                        .addPathSegment("items")
                        .addQueryParameter("limit", "0")
                        .addQueryParameter("minified", "1")
                        .addQueryParameter("collapseseries", "0")
                        .build()
                )
                .get()
                .header("Authorization", bearer(token))
                .build(),
            adapter = libraryItemsAdapter
        )

    override suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto> {
        val idsJson = itemIds.joinToString(prefix = "[", postfix = "]") { id -> id.toJsonString() }
        val body = """{"libraryItemIds":$idsJson}""".toRequestBody(JSON_MEDIA_TYPE)
        return executeJson(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "items").newBuilder().addPathSegment("batch").addPathSegment("get").build())
                .post(body)
                .header("Authorization", bearer(token))
                .build(),
            adapter = batchGetItemsAdapter
        ).libraryItems.orEmpty()
    }

    override suspend fun getProgressOrNull(baseUrl: String, token: String, itemId: String): AbsUserProgressDto? {
        return runCatching {
            executeJson(
                request = Request.Builder()
                    .url(
                        resolveApiUrl(baseUrl, "me")
                            .newBuilder()
                            .addPathSegment("progress")
                            .addPathSegment(itemId)
                            .build()
                    )
                    .get()
                    .header("Authorization", bearer(token))
                    .build(),
                adapter = progressAdapter
            )
        }.getOrElse { error ->
            if (error is AbsApiError && error.httpStatus == 404) {
                null
            } else {
                throw error
            }
        }
    }

    override suspend fun openPlaybackSession(
        baseUrl: String,
        token: String,
        itemId: String,
        request: AbsPlayRequestDto
    ): AbsPlaybackSessionDto {
        val body = playRequestAdapter.toJson(request).toRequestBody(JSON_MEDIA_TYPE)
        return executeJson(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "items").newBuilder().addPathSegment(itemId).addPathSegment("play").build())
                .post(body)
                .header("Authorization", bearer(token))
                .build(),
            adapter = playbackSessionAdapter
        )
    }

    override suspend fun syncSession(
        baseUrl: String,
        token: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        durationSec: Double
    ) {
        val body = """{"currentTime":$currentTimeSec,"timeListened":$timeListenedSec,"duration":$durationSec}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        executeUnit(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "session").newBuilder().addPathSegment(sessionId).addPathSegment("sync").build())
                .post(body)
                .header("Authorization", bearer(token))
                .build()
        )
    }

    override suspend fun closeSession(
        baseUrl: String,
        token: String,
        sessionId: String,
        currentTimeSec: Double,
        timeListenedSec: Double,
        durationSec: Double
    ) {
        val body = """{"currentTime":$currentTimeSec,"timeListened":$timeListenedSec,"duration":$durationSec}"""
            .toRequestBody(JSON_MEDIA_TYPE)
        executeUnit(
            request = Request.Builder()
                .url(resolveApiUrl(baseUrl, "session").newBuilder().addPathSegment(sessionId).addPathSegment("close").build())
                .post(body)
                .header("Authorization", bearer(token))
                .build()
        )
    }

    /**
     * Thread Context Isolation (Delegates API execution to Dispatchers.IO to protect against NetworkOnMainThreadException)
     */
    private suspend fun <T> executeJson(request: Request, adapter: JsonAdapter<T>): T {
        return withContext(Dispatchers.IO) {
                // ABS Cleartext Request Gate (Reject HTTP before credentials or bearer tokens leave the process)
                // All ABS login, token validation, catalog, playback-session, and progress requests pass this single transport check.
                validateRequestTransport(request)
                val response = try {
                    // Execute Call: Run client matching SSL certificate checks settings.
                    getClient().newCall(request).execute()
                } catch (error: IOException) {
                    val failure = RemoteAvailabilityMappingPolicy.fromTransportException(error)
                    // ABS Transport Failure Mapping (Reuses the shared timeout/connectivity classification without changing AbsApiError callers)
                    // Message text stays protocol-specific, while the availability status and compact code now come from one policy.
                    throw AbsApiError(
                        code = failure.errorCode,
                        availabilityStatus = failure.availabilityStatus,
                        message = if (failure.isTimeout) {
                            "ABS request timed out: ${request.method} ${request.url.redacted()}"
                        } else {
                            "ABS request failed: ${request.method} ${request.url.redacted()}"
                        },
                        cause = error
                    )
                }
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        throw AbsApiError(
                            code = "HTTP_${httpResponse.code}",
                            httpStatus = httpResponse.code,
                            availabilityStatus = RemoteAvailabilityMappingPolicy.fromHttpStatus(
                                statusCode = httpResponse.code,
                                protocol = RemoteAvailabilityProtocol.ABS
                            ),
                            message = "ABS request failed with HTTP ${httpResponse.code}: ${request.method} ${request.url.redacted()}"
                        )
                    }
                    val body = httpResponse.body.string()
                    adapter.fromJson(body)
                        ?: throw AbsApiError(
                            code = "EMPTY_BODY",
                            httpStatus = httpResponse.code,
                            availabilityStatus = AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                            message = "ABS response body was empty: ${request.method} ${request.url.redacted()}"
                        )
                }
        }
    }

    /**
     * Async Void Execution (Switches text responses to Dispatchers.IO context to prevent blocking state collectors)
     */
    private suspend fun executeUnit(request: Request) {
        withContext(Dispatchers.IO) {
                // ABS Cleartext Request Gate (Reject HTTP before session mutation requests leave the process)
                // The same policy protects progress sync and close-session calls, which also carry Bearer credentials.
                validateRequestTransport(request)
                val response = try {
                    // Execute Call: Run client matching SSL certificate checks settings.
                    getClient().newCall(request).execute()
                } catch (error: IOException) {
                    val failure = RemoteAvailabilityMappingPolicy.fromTransportException(error)
                    // ABS Unit Transport Failure Mapping (Shares network failure classification with JSON requests)
                    // Session mutation calls still expose AbsApiError, but no longer duplicate timeout and connectivity status constants.
                    throw AbsApiError(
                        code = failure.errorCode,
                        availabilityStatus = failure.availabilityStatus,
                        message = if (failure.isTimeout) {
                            "ABS request timed out: ${request.method} ${request.url.redacted()}"
                        } else {
                            "ABS request failed: ${request.method} ${request.url.redacted()}"
                        },
                        cause = error
                    )
                }
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        throw AbsApiError(
                            code = "HTTP_${httpResponse.code}",
                            httpStatus = httpResponse.code,
                            availabilityStatus = RemoteAvailabilityMappingPolicy.fromHttpStatus(
                                statusCode = httpResponse.code,
                                protocol = RemoteAvailabilityProtocol.ABS
                            ),
                            message = "ABS request failed with HTTP ${httpResponse.code}: ${request.method} ${request.url.redacted()}"
                        )
                    }
                }
        }
    }

    private fun resolveBaseUrl(baseUrl: String): HttpUrl {
        val normalized = baseUrl.trim().trimEnd('/')
        return normalized.toHttpUrlOrNull()
            ?: throw AbsApiError(
                code = "INVALID_BASE_URL",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                message = "Invalid ABS baseUrl: $normalized"
            )
    }

    private fun resolveApiUrl(baseUrl: String, endpoint: String): HttpUrl =
        resolveBaseUrl(baseUrl).newBuilder()
            .addPathSegment("api")
            .addPathSegment(endpoint)
            .build()

    /**
     * ABS Transport Policy Check (Applies global cleartext settings to resolved request URLs)
     *
     * RealAbsApiClient owns the final OkHttp request, making this the last reliable point before
     * login passwords or bearer tokens are attached to network traffic.
     */
    private fun validateRequestTransport(request: Request) {
        UnsafeNetworkPolicy.requireCleartextHttpAllowed(
            url = request.url.toString(),
            settings = settingsProvider(),
            operation = "ABS ${request.method}"
        )
    }

    private fun bearer(token: String): String = "Bearer $token"

    private fun HttpUrl.redacted(): String =
        newBuilder().query(null).fragment(null).build().toString()

    private fun String.toJsonString(): String =
        buildString(length + 2) {
            append('"')
            for (ch in this@toJsonString) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
            append('"')
        }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_JSON_BODY = "{}".toRequestBody(JSON_MEDIA_TYPE)

        private fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
    }
}

/**
 * Server Version Validator (Verifies server capabilities prior to syncing and throws AbsApiError on mismatch)
 * Keeps settings components unaware of presentation targets by routing errors through clean throw clauses.
 */
internal fun ensureSupportedAbsServerVersion(serverVersion: String?) {
    val comparison = compareAbsServerVersion(serverVersion, MIN_SUPPORTED_ABS_SERVER_VERSION)
    if (comparison < 0) {
        val actual = serverVersion?.takeIf { it.isNotBlank() } ?: "<unknown>"
        throw AbsApiError(
            code = "UNSUPPORTED_SERVER_VERSION",
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
            message = "ABS server version unsupported: current $actual, minimum $MIN_SUPPORTED_ABS_SERVER_VERSION"
        )
    }
}

/**
 * Version Lexicographical Comparison (Compares version string components recursively using numeric offsets)
 * Rules:
 * 1. If any input is blank or non-numeric, it is treated as incompatible and returns -1.
 * 2. Sub-segments are filled with trailing zeros when comparing inputs of differing lengths (e.g. "2.35" maps to "2.35.0").
 * 3. Returns standard Comparator integer values: < 0 if lower, 0 if equal, > 0 if higher.
 */
internal fun compareAbsServerVersion(left: String?, right: String?): Int {
    val leftParts = parseAbsServerVersion(left) ?: return -1
    val rightParts = parseAbsServerVersion(right) ?: return -1
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val leftValue = leftParts.getOrElse(index) { 0 }
        val rightValue = rightParts.getOrElse(index) { 0 }
        if (leftValue != rightValue) {
            return leftValue.compareTo(rightValue)
        }
    }
    return 0
}

/**
 * Version String Parser (Converts version blocks to numeric integer segments)
 * Returns null on parsing failures to prevent bypassing unsupported formats silently.
 */
private fun parseAbsServerVersion(version: String?): List<Int>? {
    val normalized = version?.trim()?.takeIf { value -> value.isNotEmpty() } ?: return null
    return normalized.split('.').map { segment ->
        segment.toIntOrNull() ?: return null
    }
}
