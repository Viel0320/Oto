package com.viel.aplayer.media

import androidx.media3.common.PlaybackException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import com.viel.aplayer.abs.net.AbsApiError
import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

@OptIn(UnstableApi::class)
class PlaybackErrorPolicyTest {
    @Test
    fun `open classification preserves provider cancellation`() {
        val error = PlaybackErrorPolicy.toOpenException(
            IOException("socket canceled", InterruptedIOException("call interrupted")),
            position = 0L
        )

        assertTrue(error is InterruptedIOException)
    }

    @Test
    fun `availability status maps to media3 open error codes`() {
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

        assertEquals(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, error.reason)
    }
}
