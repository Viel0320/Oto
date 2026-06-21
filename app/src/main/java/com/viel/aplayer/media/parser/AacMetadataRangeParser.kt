package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import kotlin.math.roundToLong

internal object AacMetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".aac", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult {
        val id3v2 = Id3TagReader.readId3v2(input, options)
        val id3v1 = Id3TagReader.readId3v1(input)
        val audioStartOffset = id3v2?.bytesConsumed ?: 0L
        val durationMs = id3v2?.durationMs ?: estimateAdtsDuration(input, audioStartOffset, id3v1 != null)

        return RangeAudioParseResult(
            metadata = AudiobookMetadata(
                title = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.title, id3v1?.title),
                author = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.author, id3v1?.author),
                narrator = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.narrator),
                album = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.album, id3v1?.album),
                trackIndex = id3v2?.trackIndex,
                description = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.description),
                year = RangeAudioParserSupport.mergeFirstNonBlank(id3v2?.year, id3v1?.year),
                durationMs = durationMs,
                chapters = id3v2?.chapters.orEmpty()
            ),
            embeddedCover = id3v2?.embeddedCover
        )
    }

    private suspend fun estimateAdtsDuration(
        input: RangeAudioParserInput,
        audioStartOffset: Long,
        hasId3v1: Boolean
    ): Long {
        if (audioStartOffset >= input.fileSize) return 0L
        val windowLength = minOf(SEARCH_WINDOW_BYTES.toLong(), input.fileSize - audioStartOffset).toInt()
        val bytes = input.readRange(audioStartOffset, windowLength) ?: return 0L
        val header = findAdtsHeader(bytes) ?: return 0L
        val audioBytes = (input.fileSize - audioStartOffset - if (hasId3v1) 128L else 0L).coerceAtLeast(0L)
        val bitrate = (header.frameLength.toDouble() * 8.0 * header.sampleRate.toDouble()) / header.samplesPerFrame.toDouble()
        return if (bitrate > 0.0) {
            ((audioBytes * 8.0 * 1000.0) / bitrate).roundToLong()
        } else {
            0L
        }
    }

    private fun findAdtsHeader(bytes: ByteArray): AdtsHeader? {
        for (offset in 0 until (bytes.size - 7).coerceAtLeast(0)) {
            if ((bytes[offset].toInt() and 0xff) != 0xff || (bytes[offset + 1].toInt() and 0xf0) != 0xf0) continue
            val sampleRateIndex = (bytes[offset + 2].toInt() shr 2) and 0x0f
            val sampleRate = SAMPLE_RATES.getOrNull(sampleRateIndex) ?: continue
            val frameLength = ((bytes[offset + 3].toInt() and 0x03) shl 11) or
                ((bytes[offset + 4].toInt() and 0xff) shl 3) or
                ((bytes[offset + 5].toInt() and 0xe0) shr 5)
            if (frameLength <= 7) continue
            return AdtsHeader(sampleRate = sampleRate, frameLength = frameLength, samplesPerFrame = 1024)
        }
        return null
    }



    private data class AdtsHeader(
        val sampleRate: Int,
        val frameLength: Int,
        val samplesPerFrame: Int
    )

    private const val SEARCH_WINDOW_BYTES = 16 * 1024
    private val SAMPLE_RATES = listOf(
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000, 7350
    )
}
