package com.viel.aplayer.media

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.library.vfs.VfsFileReader
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

@OptIn(UnstableApi::class)
class VfsPlaybackDataSource private constructor(
    private val context: Context
) : BaseDataSource(false) {
    private val database = AppDatabase.getInstance(context.applicationContext)
    private val fileReader = VfsFileReader(context.applicationContext, database.libraryRootDao())

    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: throw DataSourceException("Only VFS playback URIs are supported", PlaybackException.ERROR_CODE_INVALID_STATE)

        transferInitializing(dataSpec)
        val file = runBlocking { database.bookDao().getBookFileById(bookFileId) }
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val stream = runBlocking { fileReader.open(file) }
            ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        try {
            // 为每一次改动添加详尽的中文注释：Media3 的随机 seek 仍然通过 VFS 流完成，先跳过请求起点，后续 WebDAV 可在同一接口下替换为 Range 读。
            skipFully(stream, dataSpec.position)
        } catch (e: IOException) {
            stream.closeQuietly()
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }

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

    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = stream.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (stream.read() == -1) {
                throw EOFException("VFS playback seek position is out of range")
            } else {
                remaining--
            }
        }
    }

    private fun InputStream.closeQuietly() {
        try {
            close()
        } catch (_: IOException) {
            // 为每一次改动添加详尽的中文注释：关闭 VFS 播放流失败不应覆盖前面已抛出的真实播放错误。
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext

        override fun createDataSource(): DataSource =
            VfsPlaybackDataSource(appContext)
    }
}
