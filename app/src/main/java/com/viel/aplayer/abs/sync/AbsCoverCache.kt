package com.viel.aplayer.abs.sync

import android.content.Context
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.logger.AbsAuthLogger
import com.viel.aplayer.logger.AbsCoverLogger
import com.viel.aplayer.media.parser.CoverExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
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
                val bytes = httpResponse.body.bytes()
                AbsCoverLogger.logDownloadSuccess(
                    rootId = root.id,
                    remoteItemId = remoteItemId,
                    contentType = httpResponse.header("Content-Type"),
                    byteCount = bytes.size,
                    costMs = AbsCoverLogger.elapsedMs(start)
                )
                val sourceId = "abs-cover:${root.id}:$remoteItemId"
                return@withContext runCatching {
                    coverExtractor.processExternalImage(sourceId) {
                        ByteArrayInputStream(bytes)
                    }
                }.onSuccess { result ->
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
