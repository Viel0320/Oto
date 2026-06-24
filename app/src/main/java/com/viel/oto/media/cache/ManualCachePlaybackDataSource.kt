package com.viel.oto.media.cache

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.media.PlaybackFileLookup
import com.viel.oto.media.PlaybackRootLookup
import com.viel.oto.media.VfsPlaybackUri
import kotlinx.coroutines.runBlocking

@OptIn(UnstableApi::class)
class ManualCachePlaybackDataSource(
    private val manualCache: Cache,
    private val upstreamDataSourceFactory: DataSource.Factory,
    private val playbackFileLookup: PlaybackFileLookup,
    private val playbackRootLookup: PlaybackRootLookup
) : DataSource {
    private val transferListeners = mutableListOf<TransferListener>()
    private var activeDataSource: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        activeDataSource?.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val delegate = selectDataSource(dataSpec)
        activeDataSource = delegate
        transferListeners.forEach { listener -> delegate.addTransferListener(listener) }
        return delegate.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        activeDataSource?.read(buffer, offset, length)
            ?: throw DataSourceException("Manual cache playback data source is not open", PlaybackException.ERROR_CODE_INVALID_STATE)

    override fun getUri(): Uri? = activeDataSource?.uri

    override fun close() {
        try {
            activeDataSource?.close()
        } finally {
            activeDataSource = null
        }
    }

    private fun selectDataSource(dataSpec: DataSpec): DataSource {
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: return upstreamDataSourceFactory.createDataSource()
        val file = runBlocking { playbackFileLookup.getBookFileById(bookFileId) }
            ?: return upstreamDataSourceFactory.createDataSource()
        val root = runBlocking { playbackRootLookup.getRootById(file.rootId) }
            ?: return upstreamDataSourceFactory.createDataSource()
        return if (root.sourceType == AudiobookSchema.LibrarySourceType.SAF) {
            upstreamDataSourceFactory.createDataSource()
        } else {
            createRemoteManualCacheDataSource()
        }
    }

    private fun createRemoteManualCacheDataSource(): DataSource =
        CacheDataSource.Factory()
            .setCache(manualCache)
            .setUpstreamDataSourceFactory(upstreamDataSourceFactory)
            .setCacheWriteDataSinkFactory(null)
            .setCacheKeyFactory { dataSpec ->
                PlaybackCacheKeyPolicy.cacheKeyFor(dataSpec.uri, dataSpec.key)
            }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

    class Factory(
        private val manualCache: Cache,
        private val upstreamDataSourceFactory: DataSource.Factory,
        private val playbackFileLookup: PlaybackFileLookup,
        private val playbackRootLookup: PlaybackRootLookup
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            ManualCachePlaybackDataSource(
                manualCache = manualCache,
                upstreamDataSourceFactory = upstreamDataSourceFactory,
                playbackFileLookup = playbackFileLookup,
                playbackRootLookup = playbackRootLookup
            )
    }
}
