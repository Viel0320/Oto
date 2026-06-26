package com.viel.oto.application.download

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import com.viel.oto.data.dao.DownloadMetadataDao
import com.viel.oto.data.entity.DownloadMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.util.NavigableSet
import java.util.TreeSet

class CacheStatisticsProviderTest {
    @Test
    fun `snapshot should combine manual cache size and completed book count`() = runBlocking {
        val manualCache = SizedKeyCache(
            linkedMapOf(
                "manual-1" to 100L,
                "manual-2" to 150L
            )
        )
        val statisticsProvider = CacheStatisticsProvider(
            downloadCacheAccess = StaticDownloadCacheAccess(manualCache),
            downloadMetadataDao = CompletedCountDownloadMetadataDao(completedCount = 3)
        )

        val snapshot = statisticsProvider.snapshot()

        assertEquals(250L, snapshot.manualCacheBytes)
        assertEquals(3, snapshot.completedManualBooks)
        assertEquals(2, snapshot.manualCacheFileCount)
    }

    private class StaticDownloadCacheAccess(
        override val manualCache: Cache
    ) : DownloadCacheAccess

    private class CompletedCountDownloadMetadataDao(
        private val completedCount: Int
    ) : DownloadMetadataDao {
        override suspend fun insertOrReplace(metadata: DownloadMetadataEntity) = Unit
        override suspend fun getMetadata(bookId: String): DownloadMetadataEntity? = null
        override fun observeMetadata(bookId: String): Flow<DownloadMetadataEntity?> = flowOf(null)
        override fun observeAllMetadata(): Flow<List<DownloadMetadataEntity>> = flowOf(emptyList())
        override suspend fun getAllMetadata(): List<DownloadMetadataEntity> = emptyList()
        override suspend fun getRecoverableTasks(): List<DownloadMetadataEntity> = emptyList()
        override suspend fun hasRecoverableTasks(): Boolean = false
        override suspend fun getCompletedTaskCount(): Int = completedCount
        override suspend fun deleteByBookId(bookId: String) = Unit
        override suspend fun delete(metadata: DownloadMetadataEntity) = Unit
    }

    private class SizedKeyCache(
        private val keyBytes: MutableMap<String, Long>
    ) : Cache {
        override fun getUid(): Long = 1L
        override fun release() = Unit
        override fun addListener(key: String, listener: Cache.Listener): NavigableSet<CacheSpan> = TreeSet()
        override fun removeListener(key: String, listener: Cache.Listener) = Unit
        override fun getCachedSpans(key: String): NavigableSet<CacheSpan> = TreeSet()
        override fun getKeys(): Set<String> = keyBytes.keys.toSet()
        override fun getCacheSpace(): Long = keyBytes.values.sum()
        override fun startReadWrite(key: String, position: Long, length: Long): CacheSpan = unexpected()
        override fun startReadWriteNonBlocking(key: String, position: Long, length: Long): CacheSpan? = unexpected()
        override fun startFile(key: String, position: Long, length: Long): File = unexpected()
        override fun commitFile(file: File, length: Long) = unexpected()
        override fun releaseHoleSpan(holeSpan: CacheSpan) = unexpected()
        override fun removeResource(key: String) {
            keyBytes.remove(key)
        }

        override fun removeSpan(span: CacheSpan) = unexpected()
        override fun isCached(key: String, position: Long, length: Long): Boolean = key in keyBytes
        override fun getCachedLength(key: String, position: Long, length: Long): Long = keyBytes[key] ?: -length
        override fun getCachedBytes(key: String, position: Long, length: Long): Long = keyBytes[key] ?: 0L
        override fun applyContentMetadataMutations(key: String, mutations: ContentMetadataMutations) = Unit
        override fun getContentMetadata(key: String): ContentMetadata = DefaultContentMetadata.EMPTY

        private fun unexpected(): Nothing {
            error("Unexpected Cache method in CacheStatisticsProviderTest")
        }
    }
}
