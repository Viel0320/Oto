package com.viel.aplayer.media.parser

import com.viel.aplayer.data.entity.ChapterEntity
import com.viel.aplayer.media.AudiobookMetadata
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.roundToLong

// mp3 的所有格式逻辑都收口在本文件内：
// ID3v2、ID3v1、Xing/VBRI/CBR 时长估算都不再委托给外部格式专属 helper。
internal object Mp3MetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".mp3", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult {
        val id3v2 = readId3v2(input, options)
        val id3v1 = readId3v1(input)
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

    private suspend fun readId3v2(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions,
        maxTagBytes: Int = MAX_ID3_TAG_BYTES
    ): ParsedId3Tag? {
        val header = input.readRange(0L, ID3_HEADER_BYTES) ?: return null
        if (header.size < ID3_HEADER_BYTES || !header.copyOfRange(0, 3).contentEquals("ID3".toByteArray(StandardCharsets.ISO_8859_1))) {
            return null
        }

        val versionMajor = header[3].toInt() and 0xff
        val flags = header[5].toInt() and 0xff
        val tagBodySize = RangeAudioParserSupport.run { header.readSyncSafeInt(6) }
        val footerSize = if (versionMajor == 4 && (flags and 0x10) != 0) ID3_HEADER_BYTES else 0
        val totalTagBytes = (ID3_HEADER_BYTES + tagBodySize + footerSize).coerceAtMost(maxTagBytes)
        val tagBytes = input.readRange(0L, totalTagBytes) ?: return null
        if (tagBytes.size < ID3_HEADER_BYTES) return null

        val rawBodyEnd = (ID3_HEADER_BYTES + tagBodySize).coerceAtMost(tagBytes.size)
        var body = tagBytes.copyOfRange(ID3_HEADER_BYTES, rawBodyEnd)
        if ((flags and 0x80) != 0) {
            body = removeUnsynchronization(body)
        }
        if (versionMajor == 3 && body.size >= 4 && (flags and 0x40) != 0) {
            val extendedHeaderSize = RangeAudioParserSupport.run { body.readUInt32BE(0) }.toInt()
            if (extendedHeaderSize in 4..body.size) {
                body = body.copyOfRange(extendedHeaderSize, body.size)
            }
        } else if (versionMajor == 4 && body.size >= 4 && (flags and 0x40) != 0) {
            val extendedHeaderSize = RangeAudioParserSupport.run { body.readSyncSafeInt(0) }
            if (extendedHeaderSize in 4..body.size) {
                body = body.copyOfRange(extendedHeaderSize, body.size)
            }
        }

        return parseId3Frames(versionMajor, body, options, totalTagBytes.toLong())
    }

    private suspend fun readId3v1(input: RangeAudioParserInput): ParsedId3Tag? {
        if (input.fileSize < ID3V1_BYTES) return null
        val bytes = input.readRange(input.fileSize - ID3V1_BYTES, ID3V1_BYTES) ?: return null
        if (bytes.size < ID3V1_BYTES || !bytes.copyOfRange(0, 3).contentEquals("TAG".toByteArray(StandardCharsets.ISO_8859_1))) {
            return null
        }
        val title = RangeAudioParserSupport.cString(bytes, 3, 30)
        val author = RangeAudioParserSupport.cString(bytes, 33, 30)
        val album = RangeAudioParserSupport.cString(bytes, 63, 30)
        val year = RangeAudioParserSupport.cString(bytes, 93, 4)
        return ParsedId3Tag(
            bytesConsumed = 0L,
            title = title.ifBlank { null },
            author = author.ifBlank { null },
            album = album.ifBlank { null },
            year = year.ifBlank { null }
        )
    }

    private fun parseId3Frames(
        versionMajor: Int,
        body: ByteArray,
        options: RangeAudioParseOptions,
        bytesConsumed: Long
    ): ParsedId3Tag {
        var cursor = 0
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var album: String? = null
        val customDescriptionFields = linkedMapOf<String, String>()
        var description: String? = null
        var year: String? = null
        var trackIndex: Int? = null
        var durationMs: Long? = null
        var embeddedCover: EmbeddedCoverBytes? = null
        val chapters = mutableListOf<ChapterEntity>()

        while (cursor < body.size) {
            val frameHeaderSize = if (versionMajor == 2) 6 else 10
            if (cursor + frameHeaderSize > body.size) break
            val frameId = if (versionMajor == 2) {
                body.copyOfRange(cursor, cursor + 3).toString(StandardCharsets.ISO_8859_1)
            } else {
                body.copyOfRange(cursor, cursor + 4).toString(StandardCharsets.ISO_8859_1)
            }
            if (frameId.all { it == '\u0000' }) break

            val frameSize = when (versionMajor) {
                2 -> RangeAudioParserSupport.run { body.readUInt24BE(cursor + 3) }
                3 -> RangeAudioParserSupport.run { body.readUInt32BE(cursor + 4) }.toInt()
                4 -> RangeAudioParserSupport.run { body.readSyncSafeInt(cursor + 4) }
                else -> 0
            }
            if (frameSize <= 0 || cursor + frameHeaderSize + frameSize > body.size) break

            val payload = body.copyOfRange(cursor + frameHeaderSize, cursor + frameHeaderSize + frameSize)
            when (frameId) {
                "TT2", "TIT2" -> title = title ?: decodeId3TextFrame(payload)
                "TP1", "TPE1" -> author = author ?: decodeId3TextFrame(payload)
                "TAL", "TALB" -> album = album ?: decodeId3TextFrame(payload)
                "TCM", "TCOM", "TP2", "TPE2", "TPE3", "TPE4" -> narrator = narrator ?: decodeId3TextFrame(payload)
                "TYE", "TYER", "TDRC", "TDAT" -> year = year ?: RangeAudioParserSupport.normalizeYear(decodeId3TextFrame(payload)).ifBlank { null }
                "TRK", "TRCK" -> trackIndex = trackIndex ?: RangeAudioParserSupport.normalizeTrackIndex(decodeId3TextFrame(payload))
                "TLE", "TLEN" -> durationMs = durationMs ?: decodeId3TextFrame(payload)?.toLongOrNull()
                "TXX", "TXXX" -> {
                    // TXXX/TXX 是用户自定义字段集合，先记录字段名和值，最终交给统一 description 规则按优先级挑选。
                    decodeId3UserTextFrame(payload)?.let { (fieldName, value) ->
                        customDescriptionFields.putIfAbsent(fieldName, value)
                    }
                }
                "COM", "COMM" -> description = description ?: decodeId3CommentFrame(payload)
                "PIC", "APIC" -> if (options.includeEmbeddedCover && embeddedCover == null) {
                    embeddedCover = decodeId3ApicFrame(payload)
                }
                "CHAP" -> parseId3ChapterFrame(payload)?.let { chapter ->
                    chapters.add(chapter)
                }
            }

            cursor += frameHeaderSize + frameSize
        }

        return ParsedId3Tag(
            bytesConsumed = bytesConsumed,
            title = title,
            author = author,
            narrator = narrator,
            album = album,
            // ID3 自定义文本帧更可能承载用户维护的书籍简介，优先于泛用 COMM 备注帧。
            description = MetadataDescriptionRules.firstDescriptionFromFields(customDescriptionFields)
                .takeIf { it.isNotBlank() }
                ?: description,
            year = year,
            trackIndex = trackIndex,
            durationMs = durationMs,
            embeddedCover = embeddedCover,
            chapters = normalizeEmbeddedChapters(chapters)
        )
    }

    private fun decodeId3TextFrame(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val charset = id3Charset(payload[0].toInt() and 0xff)
        val textBytes = payload.copyOfRange(1, payload.size)
        return textBytes.toString(charset)
            .replace('\u0000', '\n')
            .lineSequence()
            .map { line -> line.trim() }
            .firstOrNull { line -> line.isNotBlank() }
    }

    private fun decodeId3UserTextFrame(payload: ByteArray): Pair<String, String>? {
        if (payload.isEmpty()) return null
        val charset = id3Charset(payload[0].toInt() and 0xff)
        val (fieldName, valueOffset) = RangeAudioParserSupport.readNullTerminatedText(payload, 1, charset)
        if (!MetadataDescriptionRules.isDescriptionFieldName(fieldName) || valueOffset >= payload.size) return null
        val value = payload.copyOfRange(valueOffset, payload.size)
            .toString(charset)
            .trim('\u0000', ' ', '\n', '\r', '\t')
            .takeIf { it.isNotBlank() }
            ?: return null
        return fieldName to value
    }

    private fun decodeId3CommentFrame(payload: ByteArray): String? {
        if (payload.size < 5) return null
        val charset = id3Charset(payload[0].toInt() and 0xff)
        val (_, nextOffset) = RangeAudioParserSupport.readNullTerminatedText(payload, 4, charset)
        if (nextOffset >= payload.size) return null
        return payload.copyOfRange(nextOffset, payload.size)
            .toString(charset)
            .let(MetadataDescriptionRules::normalizeDescriptionText)
            .trim('\u0000', ' ', '\n', '\r', '\t')
            .takeIf { it.isNotBlank() }
    }

    private fun decodeId3ApicFrame(payload: ByteArray): EmbeddedCoverBytes? {
        if (payload.size < 5) return null
        val charset = id3Charset(payload[0].toInt() and 0xff)
        var cursor = 1
        while (cursor < payload.size && payload[cursor] != 0.toByte()) {
            cursor++
        }
        val mimeType = payload.copyOfRange(1, cursor).toString(StandardCharsets.ISO_8859_1).ifBlank { null }
        cursor = (cursor + 1).coerceAtMost(payload.size)
        cursor = (cursor + 1).coerceAtMost(payload.size)
        val (_, nextOffset) = RangeAudioParserSupport.readNullTerminatedText(payload, cursor, charset)
        if (nextOffset >= payload.size) return null
        val imageBytes = payload.copyOfRange(nextOffset, payload.size)
        return imageBytes.takeIf { it.isNotEmpty() }?.let { EmbeddedCoverBytes(bytes = it, mimeType = mimeType) }
    }

    private fun id3Charset(encoding: Int): Charset =
        when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }

    private fun removeUnsynchronization(bytes: ByteArray): ByteArray {
        val output = ArrayList<Byte>(bytes.size)
        var index = 0
        while (index < bytes.size) {
            val current = bytes[index]
            output.add(current)
            if (current == 0xff.toByte() && index + 1 < bytes.size && bytes[index + 1] == 0.toByte()) {
                index += 2
            } else {
                index++
            }
        }
        return ByteArray(output.size) { idx -> output[idx] }
    }

    private fun parseId3ChapterFrame(payload: ByteArray): ChapterEntity? {
        var cursor = 0
        while (cursor < payload.size && payload[cursor] != 0.toByte()) {
            cursor++
        }
        if (cursor + 17 > payload.size) return null
        val chapterId = payload.copyOfRange(0, cursor).toString(StandardCharsets.ISO_8859_1)
        cursor++
        val startTimeMs = RangeAudioParserSupport.run { payload.readUInt32BE(cursor) }
        val endTimeMs = RangeAudioParserSupport.run { payload.readUInt32BE(cursor + 4) }
        cursor += 16
        val title = parseChapterTitleFromSubframes(payload, cursor) ?: chapterId.takeIf { it.isNotBlank() }
        return ChapterEntity(
            id = UUID.randomUUID().toString(),
            bookId = "TEMP",
            bookFileId = "",
            index = 0,
            title = title ?: "Chapter",
            startPositionMs = startTimeMs,
            durationMs = (endTimeMs - startTimeMs).coerceAtLeast(0L),
            fileOffsetMs = startTimeMs,
            source = "EMBEDDED"
        )
    }

    private fun parseChapterTitleFromSubframes(bytes: ByteArray, startOffset: Int): String? {
        var cursor = startOffset
        while (cursor + 10 <= bytes.size) {
            val frameId = bytes.copyOfRange(cursor, cursor + 4).toString(StandardCharsets.ISO_8859_1)
            if (frameId.all { it == '\u0000' }) break
            val frameSize = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor + 4) }.toInt()
            if (frameSize <= 0 || cursor + 10 + frameSize > bytes.size) break
            val payload = bytes.copyOfRange(cursor + 10, cursor + 10 + frameSize)
            if (frameId == "TIT2" || frameId == "TIT3" || frameId == "TIT1") {
                return decodeId3TextFrame(payload)
            }
            cursor += 10 + frameSize
        }
        return null
    }

    private fun normalizeEmbeddedChapters(chapters: List<ChapterEntity>): List<ChapterEntity> {
        val sorted = chapters
            .distinctBy { it.startPositionMs }
            .sortedBy { it.startPositionMs }
        return sorted.mapIndexed { index, chapter ->
            val nextStart = sorted.getOrNull(index + 1)?.startPositionMs
            val inferredDuration = when {
                nextStart != null -> nextStart - chapter.startPositionMs
                chapter.durationMs > 0L -> chapter.durationMs
                else -> 0L
            }
            chapter.copy(
                index = index,
                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                durationMs = inferredDuration.coerceAtLeast(0L)
            )
        }
    }

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

    private data class ParsedId3Tag(
        val bytesConsumed: Long,
        val title: String? = null,
        val author: String? = null,
        val narrator: String? = null,
        val album: String? = null,
        val description: String? = null,
        val year: String? = null,
        val trackIndex: Int? = null,
        val durationMs: Long? = null,
        val embeddedCover: EmbeddedCoverBytes? = null,
        val chapters: List<ChapterEntity> = emptyList()
    )

    private const val SEARCH_WINDOW_BYTES = 64 * 1024
    private const val ID3_HEADER_BYTES = 10
    private const val ID3V1_BYTES = 128
    private const val MAX_ID3_TAG_BYTES = 2 * 1024 * 1024
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
