package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import kotlin.math.roundToLong

// All MP3 format parsing details are consolidated in this file:
// Parses ID3v2, ID3v1 tags and calculates Xing/VBRI/CBR durations without delegating to external helper classes.
internal object Mp3MetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".mp3", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult {
        // Delegate ID3 v1/v2 parsing to the shared Id3TagReader helper to deduplicate tag processing logic.
        val id3v2 = Id3TagReader.readId3v2(input, options)
        val id3v1 = Id3TagReader.readId3v1(input)
        val audioStartOffset = id3v2?.bytesConsumed ?: 0L
        val durationMs = id3v2?.durationMs ?: estimateDuration(input, audioStartOffset, id3v1 != null)

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

    private suspend fun estimateDuration(
        input: RangeAudioParserInput,
        audioStartOffset: Long,
        hasId3v1: Boolean
    ): Long {
        val headerWindow = readHeadWindow(input, audioStartOffset) ?: return 0L
        val firstFrame = findFirstFrame(headerWindow, audioStartOffset) ?: return 0L
        firstFrame.xingFrameCount(headerWindow)?.let { frameCount ->
            return ((frameCount * firstFrame.samplesPerFrame.toDouble() * 1000.0) / firstFrame.sampleRate.toDouble()).roundToLong()
        }
        firstFrame.vbriFrameCount(headerWindow)?.let { frameCount ->
            return ((frameCount * firstFrame.samplesPerFrame.toDouble() * 1000.0) / firstFrame.sampleRate.toDouble()).roundToLong()
        }

        val audioBytes = (input.fileSize - audioStartOffset - if (hasId3v1) 128L else 0L).coerceAtLeast(0L)
        val bitrate = firstFrame.bitrateBps.takeIf { it > 0 } ?: return 0L
        return ((audioBytes * 8.0 * 1000.0) / bitrate.toDouble()).roundToLong()
    }

    private suspend fun readHeadWindow(input: RangeAudioParserInput, offset: Long): ByteArray? {
        if (offset >= input.fileSize) return null
        val length = minOf(SEARCH_WINDOW_BYTES.toLong(), input.fileSize - offset).toInt()
        return input.readRange(offset, length)
    }

    private fun findFirstFrame(bytes: ByteArray, absoluteOffset: Long): Mp3FrameHeader? {
        for (index in 0 until (bytes.size - 4).coerceAtLeast(0)) {
            parseFrameHeader(bytes, index, absoluteOffset)?.let { return it }
        }
        return null
    }

    private fun parseFrameHeader(bytes: ByteArray, offset: Int, absoluteOffset: Long): Mp3FrameHeader? {
        if ((bytes[offset].toInt() and 0xff) != 0xff || (bytes[offset + 1].toInt() and 0xe0) != 0xe0) return null
        val versionBits = (bytes[offset + 1].toInt() shr 3) and 0x03
        val layerBits = (bytes[offset + 1].toInt() shr 1) and 0x03
        val protectionAbsent = bytes[offset + 1].toInt() and 0x01
        val bitrateIndex = (bytes[offset + 2].toInt() shr 4) and 0x0f
        val sampleRateIndex = (bytes[offset + 2].toInt() shr 2) and 0x03
        val padding = (bytes[offset + 2].toInt() shr 1) and 0x01
        val channelMode = (bytes[offset + 3].toInt() shr 6) and 0x03

        val version = when (versionBits) {
            0 -> MPEG_VERSION_25
            2 -> MPEG_VERSION_2
            3 -> MPEG_VERSION_1
            else -> return null
        }
        val layer = when (layerBits) {
            1 -> MPEG_LAYER_III
            2 -> MPEG_LAYER_II
            3 -> MPEG_LAYER_I
            else -> return null
        }
        if (bitrateIndex == 0 || bitrateIndex == 0x0f || sampleRateIndex == 0x03) return null

        val bitrateKbps = lookupBitrateKbps(version, layer, bitrateIndex) ?: return null
        val sampleRate = lookupSampleRate(version, sampleRateIndex) ?: return null
        val samplesPerFrame = lookupSamplesPerFrame(version, layer)
        val frameSize = lookupFrameSize(version, layer, bitrateKbps, sampleRate, padding)
        if (frameSize <= 0) return null

        return Mp3FrameHeader(
            absoluteOffset = absoluteOffset + offset,
            localOffset = offset,
            bitrateBps = bitrateKbps * 1000,
            sampleRate = sampleRate,
            samplesPerFrame = samplesPerFrame,
            frameSize = frameSize,
            channelMode = channelMode,
            protectionAbsent = protectionAbsent != 0,
            version = version,
            layer = layer
        )
    }

    private fun lookupBitrateKbps(version: Int, layer: Int, bitrateIndex: Int): Int? {
        val table = when (layer) {
            MPEG_LAYER_I -> if (version == MPEG_VERSION_1) BITRATE_V1_L1 else BITRATE_V2_L1
            MPEG_LAYER_II -> if (version == MPEG_VERSION_1) BITRATE_V1_L2 else BITRATE_V2_L2L3
            MPEG_LAYER_III -> if (version == MPEG_VERSION_1) BITRATE_V1_L3 else BITRATE_V2_L2L3
            else -> return null
        }
        return table.getOrNull(bitrateIndex)
    }

    private fun lookupSampleRate(version: Int, sampleRateIndex: Int): Int? =
        when (version) {
            MPEG_VERSION_1 -> SAMPLE_RATE_V1.getOrNull(sampleRateIndex)
            MPEG_VERSION_2 -> SAMPLE_RATE_V2.getOrNull(sampleRateIndex)
            MPEG_VERSION_25 -> SAMPLE_RATE_V25.getOrNull(sampleRateIndex)
            else -> null
        }

    private fun lookupSamplesPerFrame(version: Int, layer: Int): Int =
        when (layer) {
            MPEG_LAYER_I -> 384
            MPEG_LAYER_II -> 1152
            MPEG_LAYER_III -> if (version == MPEG_VERSION_1) 1152 else 576
            else -> 0
        }

    private fun lookupFrameSize(version: Int, layer: Int, bitrateKbps: Int, sampleRate: Int, padding: Int): Int =
        when (layer) {
            MPEG_LAYER_I -> (((12L * bitrateKbps * 1000L) / sampleRate.toLong()) + padding) * 4L
            MPEG_LAYER_III -> {
                val coefficient = if (version == MPEG_VERSION_1) 144L else 72L
                ((coefficient * bitrateKbps * 1000L) / sampleRate.toLong()) + padding.toLong()
            }
            else -> ((144L * bitrateKbps * 1000L) / sampleRate.toLong()) + padding.toLong()
        }.toInt()



    private data class Mp3FrameHeader(
        val absoluteOffset: Long,
        val localOffset: Int,
        val bitrateBps: Int,
        val sampleRate: Int,
        val samplesPerFrame: Int,
        val frameSize: Int,
        val channelMode: Int,
        val protectionAbsent: Boolean,
        val version: Int,
        val layer: Int
    ) {
        private val headerSize: Int get() = if (protectionAbsent) 4 else 6

        fun xingFrameCount(window: ByteArray): Long? {
            if (layer != MPEG_LAYER_III) return null
            val sideInfoSize = when {
                version == MPEG_VERSION_1 && channelMode == 3 -> 17
                version == MPEG_VERSION_1 -> 32
                channelMode == 3 -> 9
                else -> 17
            }
            val xingOffset = localOffset + headerSize + sideInfoSize
            if (xingOffset + 12 > window.size) return null
            val marker = window.copyOfRange(xingOffset, xingOffset + 4).toString(Charsets.ISO_8859_1)
            if (marker != "Xing" && marker != "Info") return null
            val flags = RangeAudioParserSupport.run { window.readUInt32BE(xingOffset + 4) }.toInt()
            return if ((flags and 0x1) != 0) {
                RangeAudioParserSupport.run { window.readUInt32BE(xingOffset + 8) }
            } else {
                null
            }
        }

        fun vbriFrameCount(window: ByteArray): Long? {
            val vbriOffset = localOffset + 4 + 32
            if (vbriOffset + 18 > window.size) return null
            val marker = window.copyOfRange(vbriOffset, vbriOffset + 4).toString(Charsets.ISO_8859_1)
            return if (marker == "VBRI") {
                RangeAudioParserSupport.run { window.readUInt32BE(vbriOffset + 14) }
            } else {
                null
            }
        }
    }

    private const val SEARCH_WINDOW_BYTES = 64 * 1024
    private const val MPEG_VERSION_1 = 3
    private const val MPEG_VERSION_2 = 2
    private const val MPEG_VERSION_25 = 0
    private const val MPEG_LAYER_I = 3
    private const val MPEG_LAYER_II = 2
    private const val MPEG_LAYER_III = 1
    private val BITRATE_V1_L1 = listOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448)
    private val BITRATE_V1_L2 = listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384)
    private val BITRATE_V1_L3 = listOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATE_V2_L1 = listOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256)
    private val BITRATE_V2_L2L3 = listOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val SAMPLE_RATE_V1 = listOf(44100, 48000, 32000)
    private val SAMPLE_RATE_V2 = listOf(22050, 24000, 16000)
    private val SAMPLE_RATE_V25 = listOf(11025, 12000, 8000)
}
