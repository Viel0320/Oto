package com.viel.oto.media.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEnhancementAudioProcessorTest {
    @Test
    fun `disabled processor preserves pcm samples exactly`() {
        val processor = configuredProcessor(enabled = false)
        val samples = shortArrayOf(1200, -900, 600, -300, 0, 2400)

        processor.queueInput(samples.toInputBuffer())

        assertArrayEquals(samples, processor.getOutput().toShortArray())
    }

    @Test
    fun `enabled processor changes speech band samples without clipping`() {
        val processor = configuredProcessor(enabled = true)
        val samples = shortArrayOf(1200, -900, 600, -300, 0, 2400, -1800, 900)

        processor.queueInput(samples.toInputBuffer())

        val enhanced = processor.getOutput().toShortArray()
        assertEquals(samples.size, enhanced.size)
        assertTrue("Voice enhancement should alter non-silent PCM samples.", enhanced.contentEquals(samples).not())
        assertTrue(
            "Voice enhancement must stay under the processor's limiter ceiling.",
            enhanced.all { abs(it.toInt()) <= OUTPUT_LIMITED_SAMPLE }
        )
    }

    /**
     * Configures the processor exactly as DefaultAudioSink does before audio is queued.
     *
     * Tests call flush after configure so BaseAudioProcessor publishes the pending Media3 format to
     * the active input format, matching the runtime sink lifecycle used by PlaybackService.
     */
    private fun configuredProcessor(enabled: Boolean): VoiceEnhancementAudioProcessor {
        val processor = VoiceEnhancementAudioProcessor()
        processor.setVoiceEnhancementEnabled(enabled)
        processor.configure(AudioFormat(SAMPLE_RATE, CHANNEL_COUNT, C.ENCODING_PCM_16BIT))
        processor.flush()
        return processor
    }

    private fun ShortArray.toInputBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size * BYTES_PER_SAMPLE).order(ByteOrder.nativeOrder())
        forEach(buffer::putShort)
        buffer.flip()
        return buffer
    }

    private fun ByteBuffer.toShortArray(): ShortArray {
        val output = ShortArray(remaining() / BYTES_PER_SAMPLE)
        var index = 0
        while (remaining() >= BYTES_PER_SAMPLE) {
            output[index] = short
            index += 1
        }
        return output
    }

    private companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val OUTPUT_LIMITED_SAMPLE = 32_112
    }
}
