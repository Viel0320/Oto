package com.viel.aplayer.media

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
import com.viel.aplayer.library.vfs.VfsFileInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

@OptIn(UnstableApi::class)
class VfsPlaybackDataSource private constructor(
    private val fileLookup: PlaybackFileLookup,
    private val fileReader: VfsFileInterface,
) : BaseDataSource(false) {

    private var inputStream: InputStream? = null
    private var openedUri: Uri? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        // Open Pipeline Separation (Split DataSource.open into database query and VFS stream acquisition phases)
        // This distinguishes first-byte network overheads from local SQL search costs during profiling.
        val openStart = SystemClock.elapsedRealtime()
        val bookFileId = VfsPlaybackUri.bookFileId(dataSpec.uri)
            ?: throw DataSourceException("Only VFS playback URIs are supported", PlaybackException.ERROR_CODE_INVALID_STATE)

        transferInitializing(dataSpec)

        // Interrupted Thread Check (Fast-fail if the loading thread was interrupted prior to database lookup)
        // Short-circuits execution immediately to avoid executing unnecessary operations.
        if (Thread.currentThread().isInterrupted) {
            throw DataSourceException(
                java.io.InterruptedIOException("Loader thread was interrupted before database lookup"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        val dbLookupStart = SystemClock.elapsedRealtime()
        // Fast File Resolution (Resolves the target audiobook track using the decoupled playback lookup service)
        val file = try {
            runBlocking {
                // Interrupt Watcher (Run active guard coroutine on the Default dispatcher to poll current thread interrupt status)
                // Instantly cancels the coroutine context when interrupted, forcing underlying blocks to throw CancellationException.
                val interruptWatcher = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val currentThread = Thread.currentThread()
                    while (isActive) {
                        if (currentThread.isInterrupted) {
                            this@runBlocking.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                                kotlinx.coroutines.CancellationException("Database lookup interrupted physically")
                            )
                            break
                        }
                        delay(50)
                    }
                }
                try {
                    fileLookup.getBookFileById(bookFileId)
                } finally {
                    interruptWatcher.cancel() // Resource Clean Guard (Clean up active watcher coroutines to prevent Job leaks)
                }
            }
        } catch (_: InterruptedException) {
            // Interrupt Conversion (Map thread cancellations to Media3-compatible InterruptedIOException)
            // Standardizes exceptions to prevent ExoPlayer throwing "Unexpected exception loading stream" messages.
            throw DataSourceException(
                java.io.InterruptedIOException("Database lookup was interrupted"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw DataSourceException(
                java.io.InterruptedIOException("Database lookup was canceled by thread interrupt"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

        val dbLookupCost = SystemClock.elapsedRealtime() - dbLookupStart

        // Post-Query Interrupt Check (Inspects thread interrupt status again after database lookup compiles)
        // Avoids launching slow and expensive remote network handshakes if the playback load task is already obsolete.
        if (Thread.currentThread().isInterrupted) {
            throw DataSourceException(
                java.io.InterruptedIOException("Loader thread was interrupted after database lookup"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        }

        // Offset Delegation (Delegates seek offsets to VFS, allowing SAF to skip bytes and WebDAV to use Range HTTP headers)
        val vfsOpenStart = SystemClock.elapsedRealtime()
        val stream = try {
            runBlocking {
                // Network Interrupt Guard (Spawns a guardian coroutine monitoring the loader thread during WebDAV socket handshakes)
                // Forces network connection calls to throw CancellationException immediately if the player cancels the load.
                val interruptWatcher = launch(kotlinx.coroutines.Dispatchers.Default) {
                    val currentThread = Thread.currentThread()
                    while (isActive) {
                        if (currentThread.isInterrupted) {
                            this@runBlocking.coroutineContext[kotlinx.coroutines.Job]?.cancel(
                                kotlinx.coroutines.CancellationException("VFS open stream was physically interrupted")
                            )
                          break
                        }
                        delay(50)
                    }
                }
                try {
                    fileReader.open(file, dataSpec.position)
                } finally {
                    interruptWatcher.cancel() // Thread Guard Clean (Ensure background coroutines are cancelled to prevent leaks)
                }
            }
        } catch (_: InterruptedException) {
            // Exception Normalization (Map thread cancellations during connection setup to standard IOExceptions for ExoPlayer compatibility)
            throw DataSourceException(
                java.io.InterruptedIOException("VFS open stream was interrupted"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            throw DataSourceException(
                java.io.InterruptedIOException("VFS open stream was physically canceled by thread interrupt"),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED
            )
        } catch (e: IOException) {
            // Error Mapping Strategy (Map VFS stream open failures to standard out-of-range position errors to shield inner exceptions)
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
            // Suppressed Exception Policy (Silences stream closing exceptions to protect previous runtime errors during teardown)
        }
    }

    class Factory(context: Context) : DataSource.Factory {
        private val appContext = context.applicationContext
        // Decoupled Container Access (Use getContainer to resolve dependencies from application contexts lazily)
        // Removes hardcoded application class casting to ensure thread-safe component resolutions during player recovery.
        private val container = com.viel.aplayer.APlayerApplication.getContainer(appContext)

        override fun createDataSource(): DataSource =
            VfsPlaybackDataSource(
                fileLookup = container.playbackFileLookup,
                fileReader = container.vfsFileInterface
            )
    }
}
