package com.viel.aplayer.abs.sync

import android.content.Context
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.logger.AbsAuthLogger
import com.viel.aplayer.logger.AbsCoverLogger
import com.viel.aplayer.logger.CoverImageCacheLogger
import com.viel.aplayer.media.parser.CoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

interface AbsCoverStore {
    suspend fun downloadCover(root: com.viel.aplayer.data.entity.LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult
}

class AbsCoverCache(
    context: Context,
    private val credentialStore: AbsCredentialStore = AbsCredentialStore.getInstance(context.applicationContext),
    private val coverExtractor: CoverExtractor = CoverExtractor(context.applicationContext),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) : AbsCoverStore {
    override suspend fun downloadCover(root: com.viel.aplayer.data.entity.LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult =
        withContext(Dispatchers.IO) {
            val start = AbsCoverLogger.mark()
            // 详尽中文注释：封面下载和封面处理分成两段日志，便于区分“网络没拿到封面”和“拿到了但本地处理失败”。
            AbsCoverLogger.logDownloadStart(rootId = root.id, remoteItemId = remoteItemId)
            val credential = requireNotNull(credentialStore.get(root.credentialId)) {
                "Missing ABS credential for root ${root.id}"
            }
            if (credential.token.isBlank()) {
                AbsAuthLogger.logMissingCredential(path = "AbsCoverCache.downloadCover", rootId = root.id, credentialId = root.credentialId)
            }
            val request = Request.Builder()
                .url("${credential.baseUrl}/api/items/$remoteItemId/cover")
                .get()
                .header("Authorization", "Bearer ${credential.token}")
                .build()
            val response = try {
                client.newCall(request).execute()
            } catch (error: SocketTimeoutException) {
                AbsCoverLogger.logDownloadFailure(
                    rootId = root.id,
                    remoteItemId = remoteItemId,
                    costMs = AbsCoverLogger.elapsedMs(start),
                    errorClass = error::class.java.simpleName,
                    message = error.message
                )
                throw IOException("ABS cover request timed out", error)
            }
            response.use { httpResponse ->
                if (!httpResponse.isSuccessful) {
                    AbsCoverLogger.logDownloadFailure(
                        rootId = root.id,
                        remoteItemId = remoteItemId,
                        costMs = AbsCoverLogger.elapsedMs(start),
                        errorClass = "IOException",
                        message = "HTTP_${httpResponse.code}"
                    )
                    throw IOException("ABS cover request failed with HTTP ${httpResponse.code}")
                }
                val contentLength = httpResponse.body.contentLength()
                CoverImageCacheLogger.logAbsCoverStreamStart(
                    rootId = root.id,
                    remoteItemId = remoteItemId,
                    contentLength = contentLength.takeIf { it >= 0L }
                )
                AbsCoverLogger.logDownloadSuccess(
                    rootId = root.id,
                    remoteItemId = remoteItemId,
                    contentType = httpResponse.header("Content-Type"),
                    // 详尽注释：这里不再为了日志调用 body.bytes()，否则会把完整远端封面先读入堆内存。
                    // Content-Length 不存在时记录 -1，实际落盘大小在本地处理成功后由文件长度再次补充记录。
                    byteCount = contentLength.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1,
                    costMs = AbsCoverLogger.elapsedMs(start)
                )
                val sourceId = "abs-cover:${root.id}:$remoteItemId"
                return@withContext runCatching {
                    // 详尽注释：直接把 OkHttp 响应体流交给 ImageProcessor 的落盘链路，避免“远端封面 ByteArray”
                    // 与后续缩略图解码 Bitmap 同时存在于内存中，降低 ABS 批量同步时的峰值 heap 压力。
                    coverExtractor.processExternalImage(sourceId) {
                        httpResponse.body.byteStream()
                    }
                }.onSuccess { result ->
                    CoverImageCacheLogger.logAbsCoverStreamReady(
                        rootId = root.id,
                        remoteItemId = remoteItemId,
                        fileSize = result.originalPath?.let { path -> File(path).takeIf { it.exists() }?.length() }
                    )
                    AbsCoverLogger.logProcessSuccess(
                        rootId = root.id,
                        remoteItemId = remoteItemId,
                        originalPath = result.originalPath,
                        thumbnailPath = result.thumbnailPath,
                        backgroundColor = result.backgroundColor
                    )
                }.onFailure { error ->
                    AbsCoverLogger.logProcessFailure(
                        rootId = root.id,
                        remoteItemId = remoteItemId,
                        errorClass = error::class.java.simpleName,
                        message = error.message
                    )
                }.getOrThrow()
            }
        }
}
