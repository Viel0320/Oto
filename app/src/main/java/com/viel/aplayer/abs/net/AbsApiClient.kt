package com.viel.aplayer.abs.net

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
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
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.logger.AbsAuthLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * 详尽的中文注释：首版 ABS 接入只支持大于等于 2.35.1 的服务端版本。
 * 这个下限来自当前文档与 demo 实测已经固化下来的 API 事实，
 * 例如 `POST /api/items/batch/get` 的响应形态、`media.tracks[].contentUrl` 的主链语义以及若干 DTO 字段假设都建立在该版本基线之上。
 * 因此连接阶段必须尽早拒绝更低版本，避免后续 catalog mirror、播放与进度同步在运行时出现半兼容状态。
 */
internal const val MIN_SUPPORTED_ABS_SERVER_VERSION: String = "2.35.1"

interface AbsApiClient {
    suspend fun status(baseUrl: String): AbsStatusDto
    suspend fun login(baseUrl: String, username: String, password: String): AbsLoginResponseDto
    suspend fun authorize(baseUrl: String, token: String): AbsAuthorizeResponseDto
    suspend fun getLibraries(baseUrl: String, token: String): List<AbsLibraryDto>
    suspend fun getLibraryItemsMinified(baseUrl: String, token: String, libraryId: String): AbsLibraryItemsResponseDto
    suspend fun batchGetItems(baseUrl: String, token: String, itemIds: List<String>): List<AbsLibraryItemDto>
    suspend fun openPlaybackSession(baseUrl: String, token: String, itemId: String, request: AbsPlayRequestDto): AbsPlaybackSessionDto
    suspend fun syncSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double)
    suspend fun closeSession(baseUrl: String, token: String, sessionId: String, currentTimeSec: Double, timeListenedSec: Double, durationSec: Double)
}

/**
 * ABS HTTP 细节只允许集中在这里。
 *
 * 阶段 1 只实现连接测试所需的方法：
 * 1. `status()`
 * 2. `login()`
 * 3. `authorize()`，且只允许 POST
 * 4. `getLibraries()`
 */
class RealAbsApiClient(
    private val client: OkHttpClient = defaultClient(),
    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
) : AbsApiClient {
    private val statusAdapter: JsonAdapter<AbsStatusDto> = moshi.adapter(AbsStatusDto::class.java)
    private val loginAdapter: JsonAdapter<AbsLoginResponseDto> = moshi.adapter(AbsLoginResponseDto::class.java)
    private val authorizeAdapter: JsonAdapter<AbsAuthorizeResponseDto> = moshi.adapter(AbsAuthorizeResponseDto::class.java)
    private val librariesAdapter: JsonAdapter<AbsLibrariesResponseDto> = moshi.adapter(AbsLibrariesResponseDto::class.java)
    private val libraryItemsAdapter: JsonAdapter<AbsLibraryItemsResponseDto> = moshi.adapter(AbsLibraryItemsResponseDto::class.java)
    private val batchGetItemsAdapter: JsonAdapter<AbsBatchGetItemsResponseDto> = moshi.adapter(AbsBatchGetItemsResponseDto::class.java)
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
        // 详尽的中文注释：登录请求现在直接归档到认证日志边界 `AbsAuthLogger`，
        // 不再经过历史兼容层二次转发，避免日志路径继续维持一层无意义的中间抽象。
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
                // 任务表要求 `authorize()` 固定走 POST，禁止 GET 兜底。
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
     * 所有 JSON 接口都强制切到 IO 线程执行。
     * 这样设置页即使从主线程触发连接测试，也不会因为 OkHttp 的同步 `execute()` 触发 `NetworkOnMainThreadException`。
     */
    private suspend fun <T> executeJson(request: Request, adapter: JsonAdapter<T>): T {
        return withContext(Dispatchers.IO) {
                val response = try {
                    client.newCall(request).execute()
                } catch (error: SocketTimeoutException) {
                    throw AbsApiError(
                        code = "TIMEOUT",
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                        message = "ABS request timed out: ${request.method} ${request.url.redacted()}",
                        cause = error
                    )
                } catch (error: IOException) {
                    throw AbsApiError(
                        code = "NETWORK_UNAVAILABLE",
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
                        message = "ABS request failed: ${request.method} ${request.url.redacted()}",
                        cause = error
                    )
                }
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        throw AbsApiError(
                            code = "HTTP_${httpResponse.code}",
                            httpStatus = httpResponse.code,
                            availabilityStatus = httpResponse.code.toAvailabilityStatus(),
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
     * 纯文本 `OK` 接口与 JSON 接口保持同一线程策略，避免 `/sync`、`/close` 一类会话接口重新把阻塞网络带回调用线程。
     */
    private suspend fun executeUnit(request: Request) {
        withContext(Dispatchers.IO) {
                val response = try {
                    client.newCall(request).execute()
                } catch (error: SocketTimeoutException) {
                    throw AbsApiError(
                        code = "TIMEOUT",
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                        message = "ABS request timed out: ${request.method} ${request.url.redacted()}",
                        cause = error
                    )
                } catch (error: IOException) {
                    throw AbsApiError(
                        code = "NETWORK_UNAVAILABLE",
                        availabilityStatus = AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
                        message = "ABS request failed: ${request.method} ${request.url.redacted()}",
                        cause = error
                    )
                }
                response.use { httpResponse ->
                    if (!httpResponse.isSuccessful) {
                        throw AbsApiError(
                            code = "HTTP_${httpResponse.code}",
                            httpStatus = httpResponse.code,
                            availabilityStatus = httpResponse.code.toAvailabilityStatus(),
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

    private fun bearer(token: String): String = "Bearer $token"

    private fun Int.toAvailabilityStatus(): String =
        when (this) {
            401 -> AudiobookSchema.AvailabilityStatus.AUTH_FAILED
            403 -> AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED
            404 -> AudiobookSchema.AvailabilityStatus.NOT_FOUND
            in 500..599 -> AudiobookSchema.AvailabilityStatus.SERVER_ERROR
            else -> AudiobookSchema.AvailabilityStatus.UNKNOWN
        }

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
 * 详尽的中文注释：在连接阶段统一校验服务端版本是否满足当前实现的最低兼容要求。
 * 这里故意抛出 `AbsApiError`，这样上层可以继续沿用现有的“连接失败 -> SettingsViewModel toast”链路，
 * 而不需要让 `AbsApiClient` 直接依赖任何 UI 组件。
 */
internal fun ensureSupportedAbsServerVersion(serverVersion: String?) {
    val comparison = compareAbsServerVersion(serverVersion, MIN_SUPPORTED_ABS_SERVER_VERSION)
    if (comparison < 0) {
        val actual = serverVersion?.takeIf { it.isNotBlank() } ?: "<unknown>"
        throw AbsApiError(
            code = "UNSUPPORTED_SERVER_VERSION",
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNSUPPORTED,
            message = "ABS server 版本过低：当前 $actual，最低要求 $MIN_SUPPORTED_ABS_SERVER_VERSION"
        )
    }
}

/**
 * 详尽的中文注释：将形如 `2.35.1` 的 ABS 版本号做成可比较的整型列表，并以词典序比较。
 * 规则说明：
 * 1. 任一版本为空、空白或含有非数字片段时，都视为不满足最低版本要求，返回 -1。
 * 2. 允许长度不同的版本号参与比较，缺失的片段按 0 处理，例如 `2.35` 会按 `2.35.0` 参与比较。
 * 3. 返回值语义与 `Comparator` 一致：小于 0 表示左值更低，等于 0 表示相等，大于 0 表示左值更高。
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
 * 详尽的中文注释：把服务端版本号解析成纯数字片段列表。
 * 解析失败直接返回 `null`，交由上层统一按“不支持该版本”处理，
 * 这样可以避免把未知格式的 serverVersion 当成兼容版本放行。
 */
private fun parseAbsServerVersion(version: String?): List<Int>? {
    val normalized = version?.trim()?.takeIf { value -> value.isNotEmpty() } ?: return null
    return normalized.split('.').map { segment ->
        segment.toIntOrNull() ?: return null
    }
}
