package com.viel.aplayer.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.library.vfs.VfsFileInterface
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

@OptIn(UnstableApi::class)
class VfsPlaybackDataSource private constructor(
    private val context: Context
) : BaseDataSource(false) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val fileReader = VfsFileInterface(context.applicationContext, database.libraryRootDao())

    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        // 为播放慢定位添加详细中文注释：
        // 把 DataSource.open 拆成“查 BookFileEntity”和“通过 VFS 打开流”两段，
        // 便于确认首包读流前的固定成本到底落在数据库还是存储层。
        val openStart = SystemClock.elapsedRealtime()
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: throw DataSourceException("Only VFS playback URIs are supported", PlaybackException.ERROR_CODE_INVALID_STATE)

        transferInitializing(dataSpec)
        val dbLookupStart = SystemClock.elapsedRealtime()
        val file = runBlocking { database.bookDao().getBookFileById(bookFileId) }
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val dbLookupCost = SystemClock.elapsedRealtime() - dbLookupStart
        // 播放层只传 offset 给 VFS，SAF 由默认 skip 处理，WebDAV 由 Provider 转成 Range 请求。
        val vfsOpenStart = SystemClock.elapsedRealtime()
        val stream = try {
            runBlocking { fileReader.open(file, dataSpec.position) }
        } catch (e: IOException) {
            // Provider offset 打开失败统一映射成 Media3 可理解的读位置错误，避免远程异常穿透播放器。
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val vfsOpenCost = SystemClock.elapsedRealtime() - vfsOpenStart

        inputStream = stream
        openedUri = dataSpec.uri
        val knownSize = file.fileSize.takeIf { it > 0L }
        val availableFromPosition = knownSize?.let { (it - dataSpec.position).coerceAtLeast(0L) }
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> availableFromPosition?.let { min(dataSpec.length, it) } ?: dataSpec.length
            availableFromPosition != null -> availableFromPosition
            else -> C.LENGTH_UNSET.toLong()
        }
        opened = true
        transferStarted(dataSpec)
        val totalOpenCost = SystemClock.elapsedRealtime() - openStart
        com.viel.aplayer.logger.PlaybackTimingLogger.logDataSourceOpen(
            bookFileId = bookFileId,
            offset = dataSpec.position,
            dbCostMs = dbLookupCost,
            vfsCostMs = vfsOpenCost,
            totalMs = totalOpenCost,
            fileSize = file.fileSize
        )
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val stream = inputStream
            ?: throw DataSourceException("VFS playback stream is not open", PlaybackException.ERROR_CODE_INVALID_STATE)
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesRemaining).toInt()
        }
        val bytesRead = try {
            stream.read(buffer, offset, bytesToRead)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        try {
            inputStream?.closeQuietly()
        } finally {
            inputStream = null
            openedUri = null
            bytesRemaining = 0L
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // 关闭 VFS 播放流失败不应覆盖前面已抛出的真实播放错误。
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext

        override fun createDataSource(): DataSource =
            VfsPlaybackDataSource(appContext)
    }
}
