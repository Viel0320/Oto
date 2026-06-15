package com.viel.aplayer.abs.sync

import android.content.Context
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.abs.net.AbsAuth
import com.viel.aplayer.abs.net.AbsAuthInterceptor
import com.viel.aplayer.abs.net.AbsUrlResolver
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.store.AppSettings
import com.viel.aplayer.logger.AbsAuthLogger
import com.viel.aplayer.logger.AbsCoverLogger
import com.viel.aplayer.logger.CoverImageCacheLogger
import com.viel.aplayer.media.parser.CoverExtractor
import com.viel.aplayer.network.UnsafeNetworkPolicy
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
        .addInterceptor(AbsAuthInterceptor(credentialStore))
        .build(),
    // ABS Cover Settings Provider (Supplies cached unsafe-network settings for cover transport checks)
    // AbsCoverCache sends its own OkHttp requests outside RealAbsApiClient, so constructor injection keeps the runtime policy dependency explicit and testable.
    private val settingsProvider: () -> AppSettings = {
        AppSettingsRepository.getInstance(context.applicationContext).cachedSettings
    }
) : AbsCoverStore {
    override suspend fun downloadCover(root: com.viel.aplayer.data.entity.LibraryRootEntity, remoteItemId: String): CoverExtractor.CoverResult =
        withContext(Dispatchers.IO) {
            val start = AbsCoverLogger.mark()
            // Segregated Cover Log Stages (Differentiate download vs processing issues)
            // Cover download and cover processing are logged as two distinct phases.
            // This aids in diagnosing whether an error stems from network retrieval failure or local processing/saving failure.
            AbsCoverLogger.logDownloadStart(rootId = root.id, remoteItemId = remoteItemId)
            val credential = requireNotNull(credentialStore.get(root.credentialId)) {
                "Missing ABS credential for root ${root.id}"
            }
            if (credential.token.isBlank()) {
                AbsAuthLogger.logMissingCredential(path = "AbsCoverCache.downloadCover", rootId = root.id, credentialId = root.credentialId)
            }
            // ABS Cover Endpoint (Build the cover URL structurally using unified AbsUrlResolver)
            val coverUrl = AbsUrlResolver.resolveCoverUrl(credential.baseUrl, remoteItemId)
            // ABS Cover Cleartext Guard (Reject HTTP before bearer credentials leave the process)
            // The cover cache attaches Authorization itself, so the global transport policy must run before the request builder adds the bearer token header.
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
                    // Heap Memory Protection (Avoid reading full body into memory for logging)
                    // Do not call `body.bytes()` solely for logging purposes, as it reads the entire remote cover image into the JVM heap.
                    // Instead, use Content-Length or default to -1 if missing; the final file size on disk is recorded after processing.
                    byteCount = contentLength.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt() ?: -1,
                    costMs = AbsCoverLogger.elapsedMs(start)
                )
                val sourceId = "abs-cover:${root.id}:$remoteItemId"
                return@withContext runCatching {
                    // Stream-based Image Processing (Mitigate peak heap allocation during batch synchronization)
                    // The OkHttp response body byte stream is passed directly to the local image processor.
                    // This prevents holding both the full raw cover ByteArray and the decoded thumbnail Bitmap in memory simultaneously,
                    // significantly reducing peak heap memory usage during batch catalog synchronization.
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
