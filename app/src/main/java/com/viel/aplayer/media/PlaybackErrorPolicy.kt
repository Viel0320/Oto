package com.viel.aplayer.media

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.library.vfs.sourceProvider.webdav.WebDavException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Playback Error Policy (Maps VFS and remote-source failures into Media3 playback error categories)
 * Keeps DataSource I/O mechanics separate from provider availability, cancellation, and seek-range classification rules.
 */
// Media3 Error API Boundary (Document the narrow unstable API boundary for playback exception mapping)
// DataSourceException is the Media3-facing error type that preserves playback error codes, so this mapper accepts the unstable contract in one focused place.
@UnstableApi
object PlaybackErrorPolicy {
    /**
     * Playback Open Error Classification (Keeps cancellation, network failure, and range overflow on separate Media3 paths)
     * Active cancellation remains InterruptedIOException, while provider availability failures map to the closest playback I/O code.
     */
    fun toOpenException(error: IOException, position: Long): IOException {
        if (error.isPlaybackCancellation()) {
            return InterruptedIOException(error.message ?: "VFS playback open was canceled").also { interrupted ->
                interrupted.initCause(error)
            }
        }
        return DataSourceException(error, error.toOpenErrorCode(position))
    }

    /**
     * Playback Read Error Classification (Preserves caller cancellation during stream consumption)
     * Close-triggered interrupts should not be reported as ordinary unavailable media or corrupt playback streams.
     */
    fun toReadException(error: IOException): IOException =
        if (error.isPlaybackCancellation()) {
            InterruptedIOException(error.message ?: "VFS playback read was canceled").also { interrupted ->
                interrupted.initCause(error)
            }
        } else {
            DataSourceException(error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }

    /**
     * Availability Status Mapping (Translates provider health states into Media3 I/O categories)
     * A missing ranged stream is treated as seek overflow, while missing position zero remains a file-not-found condition.
     */
    fun availabilityStatusToOpenErrorCode(availabilityStatus: String, position: Long): Int =
        when (availabilityStatus) {
            AudiobookSchema.AvailabilityStatus.TIMEOUT -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
            AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED -> PlaybackException.ERROR_CODE_IO_NO_PERMISSION
            AudiobookSchema.AvailabilityStatus.NOT_FOUND ->
                if (position > 0L) PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE else PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            else -> PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        }

    private fun IOException.toOpenErrorCode(position: Long): Int =
        when {
            this is AbsApiError -> availabilityStatusToOpenErrorCode(availabilityStatus, position)
            this is WebDavException -> availabilityStatusToOpenErrorCode(availabilityStatus, position)
            hasCause<SocketTimeoutException>() -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            hasCause<UnknownHostException>() || hasCause<ConnectException>() -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            this is FileNotFoundException -> PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
            else -> PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        }

    private fun IOException.isPlaybackCancellation(): Boolean =
        // Cancellation Cause Detection (Recognizes direct interrupts and provider-wrapped cancellation causes)
        // Remote providers may wrap socket cancellation inside their own IOException type, so the whole cause chain is inspected.
        this is InterruptedIOException ||
            hasCause<InterruptedIOException>() ||
            hasCause<CancellationException>() ||
            Thread.currentThread().isInterrupted

    private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean {
        // Cause Chain Search (Finds wrapped provider errors without depending on a single exception wrapper)
        // ABS, WebDAV, OkHttp, and DataSource layers can each add one wrapper around the original cancellation or network exception.
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }
}
