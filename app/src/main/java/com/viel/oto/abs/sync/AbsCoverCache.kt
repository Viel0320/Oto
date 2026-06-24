package com.viel.oto.abs.sync

import android.content.Context
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsAuth
import com.viel.oto.abs.net.AbsAuthInterceptor
import com.viel.oto.abs.net.AbsUrlResolver
import com.viel.oto.logger.AbsAuthLogger
import com.viel.oto.logger.AbsCoverLogger
import com.viel.oto.logger.CoverImageCacheLogger
import com.viel.oto.media.parser.CoverExtractor
import com.viel.oto.network.UnsafeNetworkPolicy
import com.viel.oto.shared.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

interface AbsCoverStore {
    suspend fun downloadCover(root: com.viel.oto.data.entity.LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult
}

class AbsCoverCache(
    context: Context,
    private val credentialStore: AbsCredentialStore,
    private val coverExtractor: CoverExtractor = CoverExtractor(context.applicationContext),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(AbsAuthInterceptor(credentialStore))
        .build(),
    private val settingsProvider: () -> AppSettings
) : AbsCoverStore {
    override suspend fun downloadCover(root: com.viel.oto.data.entity.LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult =
        withContext(Dispatchers.IO) {
            val start = AbsCoverLogger.mark()
            AbsCoverLogger.logDownloadStart(rootId = root.id, remoteItemId = remoteItemId)
            val credential = requireNotNull(credentialStore.get(root.credentialId)) {
                "Missing ABS credential for root ${root.id}"
            }
            if (credential.token.isBlank()) {
                AbsAuthLogger.logMissingCredential(path = "AbsCoverCache.downloadCover", rootId = root.id, credentialId = root.credentialId)
            }
            val coverUrl = AbsUrlResolver.resolveCoverUrl(credential.baseUrl, remoteItemId)
            UnsafeNetworkPolicy.requireCleartextHttpAllowed(
                url = coverUrl.toString(),
                settings = settingsProvider(),
                operation = "ABS cover download"
            )
            val request = Request.Builder()
                .url(coverUrl)
                .get()
                .tag(AbsAuth::class.java, AbsAuth(token = credential.token, credentialId = root.credentialId))
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
                    byteCount = contentLength.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1,
                    costMs = AbsCoverLogger.elapsedMs(start)
                )
                val sourceId = "abs-cover:${root.id}:$remoteItemId"
                return@withContext runCatching {
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
