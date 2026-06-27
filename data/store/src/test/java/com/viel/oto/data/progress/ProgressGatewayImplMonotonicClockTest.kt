package com.viel.oto.data.progress

import com.viel.oto.data.dao.BookDao
import com.viel.oto.logger.NoOpWorkflowLogSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Guards the strictly increasing progress-write clock.
 * nextProgressWriteTime() derives the persisted timestamp from AtomicLong.updateAndGet { maxOf(wallClock, prev + 1) },
 * so back-to-back writes must never collide or regress even when the wall clock does not advance or rolls back.
 */
class ProgressGatewayImplMonotonicClockTest {

    /**
     * Captures the currentTime argument every fire-and-forget write hands to BookDao.
     * A latch lets the test await asynchronous coroutine completion before asserting ordering.
     */
    private class RecordingBookDao(expectedCalls: Int) {
        val capturedTimestamps: MutableList<Long> = Collections.synchronizedList(mutableListOf())
        val latch = CountDownLatch(expectedCalls)

        val dao: BookDao = Proxy.newProxyInstance(
            BookDao::class.java.classLoader,
            arrayOf(BookDao::class.java)
        ) { _, method, args ->
            when (method.name) {
                "updateProgressWithReadStatus" -> {
                    // Suspend reflective signature: (bookId, position, currentTime, Continuation).
                    capturedTimestamps.add(args[2] as Long)
                    latch.countDown()
                    true
                }
                else -> throw UnsupportedOperationException("Unexpected DAO method: ${method.name}")
            }
        } as BookDao
    }

    @Test
    fun `serialized writes produce strictly increasing timestamps`() {
        val iterations = 50
        val recorder = RecordingBookDao(expectedCalls = iterations)
        val gateway = ProgressGatewayImpl(recorder.dao, NoOpWorkflowLogSink)

        // Serialize each write so captured order matches generation order on the calling thread.
        repeat(iterations) { index ->
            val singleCallLatch = CountDownLatch(1)
            val sizeBefore = recorder.capturedTimestamps.size
            gateway.updateProgress("book-1", index * 1000L)
            // Spin until the async coroutine recorded this write before issuing the next one.
            val deadline = System.currentTimeMillis() + 5_000L
            while (recorder.capturedTimestamps.size == sizeBefore && System.currentTimeMillis() < deadline) {
                Thread.sleep(1L)
            }
            singleCallLatch.countDown()
        }

        assertTrue("All writes should have completed", recorder.latch.await(5, TimeUnit.SECONDS))
        gateway.close()

        val timestamps = recorder.capturedTimestamps.toList()
        assertEquals(iterations, timestamps.size)
        for (i in 1 until timestamps.size) {
            assertTrue(
                "Timestamp at $i (${timestamps[i]}) must exceed previous (${timestamps[i - 1]})",
                timestamps[i] > timestamps[i - 1]
            )
        }
    }

    @Test
    fun `rapid writes within the same millisecond stay distinct and increasing`() {
        val iterations = 200
        val recorder = RecordingBookDao(expectedCalls = iterations)
        val gateway = ProgressGatewayImpl(recorder.dao, NoOpWorkflowLogSink)

        // Tight loop keeps many calls inside a single wall-clock millisecond, forcing the prev + 1 branch.
        repeat(iterations) { index ->
            gateway.updateProgress("book-1", index.toLong())
        }

        assertTrue("All writes should have completed", recorder.latch.await(10, TimeUnit.SECONDS))
        gateway.close()

        val timestamps = recorder.capturedTimestamps.toList()
        assertEquals(iterations, timestamps.size)
        // Generation is monotonic even if asynchronous arrival order scrambles the list, so distinctness proves no collisions.
        assertEquals("No two writes may share a timestamp", iterations, timestamps.toSet().size)
        val sorted = timestamps.sorted()
        for (i in 1 until sorted.size) {
            assertTrue("Sorted timestamps must strictly increase", sorted[i] > sorted[i - 1])
        }
    }
}
