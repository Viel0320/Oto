package com.viel.oto.media.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Applies a lightweight narrator-voice enhancement pass to decoded PCM audio.
 *
 * The processor stays inside the Media3 service boundary because it works on rendered PCM frames
 * after source decoding. That keeps SAF, WebDAV, ABS, direct, and cached playback behavior unified
 * without leaking audio-rendering details into settings UI, playback plans, or VFS source access.
 */
@OptIn(UnstableApi::class)
class VoiceEnhancementAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var enhancementEnabled: Boolean = false

    @Volatile
    private var resetRequested: Boolean = false

    private var previousInputByChannel = FloatArray(0)
    private var previousHighPassByChannel = FloatArray(0)
    private var highPassAlpha: Float = 0f

    /**
     * Updates the runtime switch consumed by the audio rendering thread.
     *
     * The processor remains configured while disabled and performs byte-for-byte passthrough, so a
     * settings change does not require rebuilding ExoPlayer or renegotiating Media3 audio sinks.
     */
    fun setVoiceEnhancementEnabled(enabled: Boolean) {
        if (enhancementEnabled != enabled) {
            enhancementEnabled = enabled
            resetRequested = true
        }
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioFormat.NOT_SET
        }
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        prepareFilterState()
    }

    override fun onReset() {
        previousInputByChannel = FloatArray(0)
        previousHighPassByChannel = FloatArray(0)
        highPassAlpha = 0f
        resetRequested = false
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            // Media3 drains the pipeline by queuing AudioProcessor.EMPTY_BUFFER, including before the
            // first PCM frame arrives. At that point replaceOutputBuffer() still hands back that same
            // shared empty buffer, and ByteBuffer.put rejects copying a buffer onto itself even for
            // zero bytes, so there is nothing to process here.
            return
        }
        val bytesToProcess = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(bytesToProcess)
        if (resetRequested) {
            prepareFilterState()
            resetRequested = false
        }
        if (!enhancementEnabled || inputAudioFormat.channelCount <= 0) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        var channelIndex = 0
        while (inputBuffer.remaining() >= BYTES_PER_PCM_16_SAMPLE) {
            val sample = inputBuffer.short.toFloat() / PCM_16_FLOAT_SCALE
            val highPassed = highPassAlpha *
                (previousHighPassByChannel[channelIndex] + sample - previousInputByChannel[channelIndex])
            previousInputByChannel[channelIndex] = sample
            previousHighPassByChannel[channelIndex] = highPassed

            val enhanced = (sample * DRY_GAIN + highPassed * PRESENCE_GAIN)
                .coerceIn(-OUTPUT_LIMIT, OUTPUT_LIMIT)
            outputBuffer.putShort(
                (enhanced * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            )
            channelIndex = (channelIndex + 1) % inputAudioFormat.channelCount
        }
        if (inputBuffer.hasRemaining()) {
            outputBuffer.put(inputBuffer)
        }
        outputBuffer.flip()
    }

    /**
     * Recreates per-channel filter memory for the current Media3 format.
     *
     * Resetting this state on flush or enable changes avoids carrying filter history across tracks,
     * seeks, or an off-to-on toggle where the prior samples were intentionally passed through.
     */
    private fun prepareFilterState() {
        val channelCount = inputAudioFormat.channelCount.coerceAtLeast(0)
        previousInputByChannel = FloatArray(channelCount)
        previousHighPassByChannel = FloatArray(channelCount)
        highPassAlpha = highPassAlphaFor(inputAudioFormat.sampleRate)
    }

    private fun highPassAlphaFor(sampleRate: Int): Float {
        val safeSampleRate = sampleRate.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE
        val rc = 1.0 / (2.0 * PI * HIGH_PASS_CUTOFF_HZ)
        val dt = 1.0 / safeSampleRate.toDouble()
        return (rc / (rc + dt)).toFloat()
    }

    private companion object {
        private const val BYTES_PER_PCM_16_SAMPLE = 2
        private const val PCM_16_FLOAT_SCALE = 32768f
        private const val DEFAULT_SAMPLE_RATE = 44_100
        private const val HIGH_PASS_CUTOFF_HZ = 160.0
        private const val DRY_GAIN = 0.92f
        private const val PRESENCE_GAIN = 0.32f
        private const val OUTPUT_LIMIT = 0.98f
    }
}
