package com.viel.oto.media

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.viel.oto.library.vfs.VfsFileInterface
import com.viel.oto.library.vfs.VfsPlaybackStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

@OptIn(UnstableApi::class)
class VfsPlaybackDataSource internal constructor(
    private val fileLookup: PlaybackFileLookup,
    private val fileReader: VfsPlaybackStreamReader,
) : BaseDataSource(false) {

    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false
    private val activeLoaderThread = AtomicReference<Thread?>()

    override fun open(dataSpec: DataSpec): Long {
        val openStart = SystemClock.elapsedRealtime()
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: throw DataSourceException("Only VFS playback URIs are supported", PlaybackException.ERROR_CODE_INVALID_STATE)

        transferInitializing(dataSpec)

        failIfLoaderInterrupted("Database lookup")

        val dbLookupStart = SystemClock.elapsedRealtime()
        val file = runInterruptibleBlocking("Database lookup") {
            fileLookup.getBookFileById(bookFileId)
        } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val dbLookupCost = SystemClock.elapsedRealtime() - dbLookupStart

        failIfLoaderInterrupted("VFS open")

        val vfsOpenStart = SystemClock.elapsedRealtime()
        val stream = try {
            runInterruptibleBlocking("VFS open") {
                fileReader.openPlaybackStream(file, dataSpec.position)
            }
        } catch (e: IOException) {
            throw PlaybackErrorPolicy.toOpenException(e, dataSpec.position)
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
        com.viel.oto.logger.PlaybackTimingLogger.logDataSourceOpen(
            bookFileId = bookFileId,
            offset = dataSpec.position,
            dbCostMs = dbLookupCost,
            vfsCostMs = vfsOpenCost,
            totalMs = totalOpenCost,
            fileSize = file.fileSize
        )
        return bytesRemaining
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
            runWithActiveLoaderThread {
                stream.read(buffer, offset, bytesToRead)
            }
        } catch (e: IOException) {
            throw PlaybackErrorPolicy.toReadException(e)
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
            activeLoaderThread.get()?.interrupt()
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
        }
    }

    private fun failIfLoaderInterrupted(phase: String) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException("$phase was interrupted before VFS playback work started")
        }
    }

    private fun <T> runInterruptibleBlocking(phase: String, block: suspend () -> T): T {
        val loaderThread = Thread.currentThread()
        activeLoaderThread.set(loaderThread)
        return try {
            runBlocking {
                val parentJob = coroutineContext[Job]
                val interruptWatcher = launch(Dispatchers.Default) {
                    while (isActive) {
                        if (loaderThread.isInterrupted) {
                            parentJob?.cancel(CancellationException("$phase canceled by loader thread interrupt"))
                            break
                        }
                        delay(INTERRUPT_WATCH_INTERVAL_MS.milliseconds)
                    }
                }
                try {
                    block()
                } finally {
                    interruptWatcher.cancel()
                }
            }
        } catch (_: InterruptedException) {
            throw InterruptedIOException("$phase was interrupted")
        } catch (_: CancellationException) {
            throw InterruptedIOException("$phase was canceled")
        } finally {
            activeLoaderThread.compareAndSet(loaderThread, null)
        }
    }

    private inline fun <T> runWithActiveLoaderThread(block: () -> T): T {
        val loaderThread = Thread.currentThread()
        activeLoaderThread.set(loaderThread)
        return try {
            block()
        } finally {
            activeLoaderThread.compareAndSet(loaderThread, null)
        }
    }

    class Factory(
        context: Context,
        private val injectedFileLookup: PlaybackFileLookup? = null,
        private val injectedFileReader: VfsPlaybackStreamReader? = null
    ) : DataSource.Factory, KoinComponent {
        private val fileLookup: PlaybackFileLookup by lazy {
            injectedFileLookup ?: get<PlaybackFileLookup>()
        }
        private val fileReader: VfsPlaybackStreamReader by lazy {
            injectedFileReader ?: get<VfsFileInterface>()
        }

        override fun createDataSource(): DataSource =
            VfsPlaybackDataSource(
                fileLookup = fileLookup,
                fileReader = fileReader
            )
    }

    private companion object {
        private const val INTERRUPT_WATCH_INTERVAL_MS = 50L
    }
}
