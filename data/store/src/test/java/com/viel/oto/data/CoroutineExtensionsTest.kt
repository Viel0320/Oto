package com.viel.oto.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Verifies the structured-concurrency contract of runCatchingCancellable.
 * CancellationException must keep propagating while all other throwables collapse into Result.failure.
 */
class CoroutineExtensionsTest {

    @Test
    fun `normal return is wrapped in success`() {
        val result = runCatchingCancellable { 42 }

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `null return is wrapped in success`() {
        val result = runCatchingCancellable<String?> { null }

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrNull())
    }

    @Test
    fun `generic throwable is captured as failure`() {
        val boom = IllegalStateException("boom")

        val result = runCatchingCancellable { throw boom }

        assertTrue(result.isFailure)
        assertSame(boom, result.exceptionOrNull())
    }

    @Test
    fun `cancellation exception is rethrown not captured`() {
        val cancellation = CancellationException("cancelled")

        try {
            runCatchingCancellable { throw cancellation }
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
        }
    }

    @Test
    fun `error subclass of throwable is captured as failure`() {
        val error = OutOfMemoryError("simulated")

        val result = runCatchingCancellable { throw error }

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
        assertSame(error, result.exceptionOrNull())
    }
}
