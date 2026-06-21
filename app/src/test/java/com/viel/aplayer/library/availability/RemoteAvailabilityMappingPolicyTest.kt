package com.viel.aplayer.library.availability

import com.viel.aplayer.data.db.AudiobookSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RemoteAvailabilityMappingPolicyTest {
    @Test
    fun `common http statuses map consistently across remote protocols`() {
        listOf(RemoteAvailabilityProtocol.ABS, RemoteAvailabilityProtocol.WEBDAV).forEach { protocol ->
            assertEquals(
                AudiobookSchema.AvailabilityStatus.AUTH_FAILED,
                RemoteAvailabilityMappingPolicy.fromHttpStatus(401, protocol)
            )
            assertEquals(
                AudiobookSchema.AvailabilityStatus.PERMISSION_DENIED,
                RemoteAvailabilityMappingPolicy.fromHttpStatus(403, protocol)
            )
            assertEquals(
                AudiobookSchema.AvailabilityStatus.NOT_FOUND,
                RemoteAvailabilityMappingPolicy.fromHttpStatus(404, protocol)
            )
            assertEquals(
                AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
                RemoteAvailabilityMappingPolicy.fromHttpStatus(500, protocol)
            )
        }
    }

    @Test
    fun `abs keeps unknown http fallback semantics`() {
        assertEquals(
            AudiobookSchema.AvailabilityStatus.UNKNOWN,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(408, RemoteAvailabilityProtocol.ABS)
        )
        assertEquals(
            AudiobookSchema.AvailabilityStatus.SERVER_ERROR,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(504, RemoteAvailabilityProtocol.ABS)
        )
        assertEquals(
            AudiobookSchema.AvailabilityStatus.UNKNOWN,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(418, RemoteAvailabilityProtocol.ABS)
        )
    }

    @Test
    fun `webdav keeps timeout and network fallback semantics`() {
        assertEquals(
            AudiobookSchema.AvailabilityStatus.TIMEOUT,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(408, RemoteAvailabilityProtocol.WEBDAV)
        )
        assertEquals(
            AudiobookSchema.AvailabilityStatus.TIMEOUT,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(504, RemoteAvailabilityProtocol.WEBDAV)
        )
        assertEquals(
            AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE,
            RemoteAvailabilityMappingPolicy.fromHttpStatus(418, RemoteAvailabilityProtocol.WEBDAV)
        )
    }

    @Test
    fun `transport exceptions distinguish timeout from general network failure`() {
        val timeout = RemoteAvailabilityMappingPolicy.fromTransportException(SocketTimeoutException("slow"))
        val network = RemoteAvailabilityMappingPolicy.fromTransportException(IOException("offline"))

        assertEquals("TIMEOUT", timeout.errorCode)
        assertEquals(AudiobookSchema.AvailabilityStatus.TIMEOUT, timeout.availabilityStatus)
        assertTrue(timeout.isTimeout)

        assertEquals("NETWORK_UNAVAILABLE", network.errorCode)
        assertEquals(AudiobookSchema.AvailabilityStatus.NETWORK_UNAVAILABLE, network.availabilityStatus)
        assertFalse(network.isTimeout)
    }
}
