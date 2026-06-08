package com.viel.aplayer.media

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

@UnstableApi
class PlaybackErrorPolicyTest {
    @Test
    fun `open classification preserves provider cancellation`() {
        val error = PlaybackErrorPolicy.toOpenException(
            IOException("socket canceled", InterruptedIOException("call interrupted")),
            position = 0L
        )

        // Provider Cancellation Policy (Keeps close-triggered or socket-interrupted opens out of Media3 unavailable-media paths)
        // Wrapped InterruptedIOException values remain cancellation signals so playback recovery does not mark tracks missing.
        assertTrue(error is InterruptedIOException)
    }

    @Test
    fun `availability status maps to media3 open error codes`() {
        // Playback Availability Mapping (Pins provider health states to stable Media3 I/O categories)
        // This policy is shared by ABS and WebDAV errors before they reach ExoPlayer.
        assertEquals(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackErrorPolicy.availabilityStatusToOpenErrorCode(AudiobookSchema.AvailabilityStatus.TIMEOUT, position = 0L)
        )
        assertEquals(
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackErrorPolicy.availabilityStatusToOpenErrorCode(AudiobookSchema.AvailabilityStatus.AUTH_FAILED, position = 0L)
        )
        assertEquals(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackErrorPolicy.availabilityStatusToOpenErrorCode(AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE, position = 0L)
        )
    }

    @Test
    fun `not found distinguishes initial missing file from ranged seek overflow`() {
        // Ranged Missing Mapping (Keeps offset failures separate from absent first-byte opens)
        // A remote 404 at position zero is file-not-found, while a 404 after seeking indicates the requested byte range is unavailable.
        assertEquals(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackErrorPolicy.availabilityStatusToOpenErrorCode(AudiobookSchema.AvailabilityStatus.NOT_FOUND, position = 0L)
        )
        assertEquals(
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackErrorPolicy.availabilityStatusToOpenErrorCode(AudiobookSchema.AvailabilityStatus.NOT_FOUND, position = 128L)
        )
    }

    @Test
    fun `abs open errors use availability status`() {
        val error = PlaybackErrorPolicy.toOpenException(
            AbsApiError(
                code = "TIMEOUT",
                availabilityStatus = AudiobookSchema.AvailabilityStatus.TIMEOUT,
                message = "ABS timeout"
            ),
            position = 0L
        ) as DataSourceException

        // ABS Playback Error Bridge (Uses the provider availability status instead of raw exception text)
        // The resulting Media3 reason remains stable even if ABS error messages change.
        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, error.reason)
    }
}
