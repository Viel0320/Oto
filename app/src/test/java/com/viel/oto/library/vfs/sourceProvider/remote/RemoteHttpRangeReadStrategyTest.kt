package com.viel.oto.library.vfs.sourceProvider.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class RemoteHttpRangeReadStrategyTest {
    @Test
    fun `plan should build range header without known file size`() {
        val plan = RemoteHttpRangeReadStrategy.plan(offset = 5L, length = 5)

        assertEquals(RemoteRangePlan(start = 5L, end = 9L, expectedBodyLength = 5), plan)
        assertEquals("bytes=5-9", plan?.headerValue)
    }

    @Test
    fun `plan should clamp to known file size`() {
        val plan = RemoteHttpRangeReadStrategy.plan(offset = 95L, length = 1000, knownFileSize = 100L)

        assertEquals(RemoteRangePlan(start = 95L, end = 99L, expectedBodyLength = 5), plan)
        assertEquals("bytes=95-99", plan?.headerValue)
    }

    @Test
    fun `plan should return null when offset is outside known file size`() {
        val plan = RemoteHttpRangeReadStrategy.plan(offset = 100L, length = 5, knownFileSize = 100L)

        assertNull(plan)
    }

    @Test
    fun `plan should saturate overflowing range end`() {
        val plan = RemoteHttpRangeReadStrategy.plan(offset = Long.MAX_VALUE - 1L, length = 8)

        assertEquals(RemoteRangePlan(start = Long.MAX_VALUE - 1L, end = Long.MAX_VALUE, expectedBodyLength = 2), plan)
        assertEquals("bytes=${Long.MAX_VALUE - 1L}-${Long.MAX_VALUE}", plan?.headerValue)
    }

    @Test
    fun `parseContentRange should parse valid byte windows`() {
        val contentRange = RemoteHttpRangeReadStrategy.parseContentRange("bytes 5-9/100")

        assertEquals(RemoteContentRange(start = 5L, end = 9L), contentRange)
    }

    @Test
    fun `parseContentRange should reject invalid byte windows`() {
        assertNull(RemoteHttpRangeReadStrategy.parseContentRange(null))
        assertNull(RemoteHttpRangeReadStrategy.parseContentRange("bytes 9-5/100"))
        assertNull(RemoteHttpRangeReadStrategy.parseContentRange("items 5-9/100"))
    }

    @Test
    fun `validateContentRange should support exact and within-end policies`() {
        val plan = RemoteRangePlan(start = 5L, end = 9L, expectedBodyLength = 5)

        assertTrue(
            RemoteHttpRangeReadStrategy.validateContentRange(
                contentRange = RemoteContentRange(start = 5L, end = 9L),
                plan = plan,
                endPolicy = RemoteRangeEndPolicy.ExactEnd
            )
        )
        assertFalse(
            RemoteHttpRangeReadStrategy.validateContentRange(
                contentRange = RemoteContentRange(start = 5L, end = 8L),
                plan = plan,
                endPolicy = RemoteRangeEndPolicy.ExactEnd
            )
        )
        assertTrue(
            RemoteHttpRangeReadStrategy.validateContentRange(
                contentRange = RemoteContentRange(start = 5L, end = 8L),
                plan = plan,
                endPolicy = RemoteRangeEndPolicy.WithinRequestedEnd
            )
        )
        assertFalse(
            RemoteHttpRangeReadStrategy.validateContentRange(
                contentRange = RemoteContentRange(start = 4L, end = 8L),
                plan = plan,
                endPolicy = RemoteRangeEndPolicy.WithinRequestedEnd
            )
        )
    }

    @Test
    fun `validateContentRange should emit mismatch diagnostics through strategy sink`() {
        val plan = RemoteRangePlan(start = 5L, end = 9L, expectedBodyLength = 5)
        val sink = RecordingRangeStrategyLogSink()

        val isValid = RemoteHttpRangeReadStrategy.validateContentRange(
            contentRange = RemoteContentRange(start = 4L, end = 9L),
            plan = plan,
            endPolicy = RemoteRangeEndPolicy.ExactEnd,
            logSink = sink
        )

        assertFalse(isValid)
        assertEquals(plan, sink.mismatchPlan)
        assertEquals(RemoteContentRange(start = 4L, end = 9L), sink.mismatchContentRange)
        assertEquals(RemoteRangeEndPolicy.ExactEnd, sink.mismatchEndPolicy)
    }

    @Test
    fun `validateContentRangeStart should support open-ended range streams`() {
        assertTrue(
            RemoteHttpRangeReadStrategy.validateContentRangeStart(
                contentRange = RemoteContentRange(start = 5L, end = 99L),
                start = 5L
            )
        )
        assertFalse(
            RemoteHttpRangeReadStrategy.validateContentRangeStart(
                contentRange = RemoteContentRange(start = 4L, end = 99L),
                start = 5L
            )
        )
    }

    @Test
    fun `readBodyWithLimit should return exact and shorter bodies`() {
        val exact = RemoteHttpRangeReadStrategy.readBodyWithLimit(
            stream = ByteArrayInputStream("56789".toByteArray()),
            expectedLength = 5
        )
        val shorter = RemoteHttpRangeReadStrategy.readBodyWithLimit(
            stream = ByteArrayInputStream("789".toByteArray()),
            expectedLength = 5
        )

        assertArrayEquals("56789".toByteArray(), (exact as RemoteRangeBodyReadResult.Success).bytes)
        assertArrayEquals("789".toByteArray(), (shorter as RemoteRangeBodyReadResult.Success).bytes)
    }

    @Test
    fun `readBodyWithLimit should report oversized bodies after one extra byte`() {
        val result = RemoteHttpRangeReadStrategy.readBodyWithLimit(
            stream = ByteArrayInputStream("567890".toByteArray()),
            expectedLength = 5
        )

        val tooLarge = result as RemoteRangeBodyReadResult.TooLarge
        assertEquals(5, tooLarge.requestedLength)
        assertEquals(6L, tooLarge.observedBytes)
    }

    @Test
    fun `readBodyWithLimit should emit oversized body diagnostics through strategy sink`() {
        val sink = RecordingRangeStrategyLogSink()

        val result = RemoteHttpRangeReadStrategy.readBodyWithLimit(
            stream = ByteArrayInputStream("567890".toByteArray()),
            expectedLength = 5,
            logSink = sink
        )

        val tooLarge = result as RemoteRangeBodyReadResult.TooLarge
        assertEquals(tooLarge, sink.tooLargeResult)
    }

    private class RecordingRangeStrategyLogSink : RemoteRangeStrategyLogSink {
        var mismatchPlan: RemoteRangePlan? = null
            private set
        var mismatchContentRange: RemoteContentRange? = null
            private set
        var mismatchEndPolicy: RemoteRangeEndPolicy? = null
            private set
        var tooLargeResult: RemoteRangeBodyReadResult.TooLarge? = null
            private set

        override fun onContentRangeMismatch(
            plan: RemoteRangePlan,
            contentRange: RemoteContentRange?,
            endPolicy: RemoteRangeEndPolicy
        ) {
            mismatchPlan = plan
            mismatchContentRange = contentRange
            mismatchEndPolicy = endPolicy
        }

        override fun onBodyTooLarge(result: RemoteRangeBodyReadResult.TooLarge) {
            tooLargeResult = result
        }
    }
}
