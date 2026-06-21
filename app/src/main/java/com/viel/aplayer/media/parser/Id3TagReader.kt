package com.viel.aplayer.media.parser

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.ChapterEntity
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Shared utility for reading and decoding ID3v1 and ID3v2 metadata tags from audio byte streams.
 * Consolidates duplicate parser implementations used by format-specific media parsers.
 */
internal object Id3TagReader {

    internal const val ID3_HEADER_BYTES = 10
    internal const val ID3V1_BYTES = 128
    internal const val MAX_ID3_TAG_BYTES = 2 * 1024 * 1024

    /**
     * Attempts to read and parse an ID3v2 tag block from the start of the audio stream.
     * Restricts range-reading up to the computed size of the ID3v2 tag header.
     */
    suspend fun readId3v2(
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

    /**
     * Reads a trailing 128-byte ID3v1 metadata block from the end of the audio stream.
     */
    suspend fun readId3v1(input: RangeAudioParserInput): ParsedId3Tag? {
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

    /**
     * Loops through individual ID3 frame headers in the tag body payload and delegates parsing by frame type.
     */
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
            description = description?.takeIf { it.isNotBlank() }
                ?: MetadataDescriptionRules.firstDescriptionFromFields(customDescriptionFields),
            year = year,
            trackIndex = trackIndex,
            durationMs = durationMs,
            embeddedCover = embeddedCover,
            chapters = normalizeEmbeddedChapters(chapters)
        )
    }

    /**
     * Decodes and trims standard text string frames, replacing null bytes with line breaks.
     */
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

    /**
     * Decodes a user-defined text frame (TXXX/TXX), returning its descriptor/value pair if it maps to a description/synopsis field.
     */
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

    /**
     * Decodes a standard ID3 comment frame (COMM/COM), extracting and normalising the trailing text segment.
     */
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

    /**
     * Extracts picture bytes and mime type from ID3 APIC/PIC frame payloads.
     */
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
        return RangeAudioParserSupport.embeddedCover(imageBytes, mimeType)
    }

    /**
     * Maps the encoding byte value in ID3 frames to their standard Charset definitions.
     */
    private fun id3Charset(encoding: Int): Charset =
        when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }

    /**
     * Reconstructs byte payload by removing ID3 unsynchronization marker patterns (0xFF 0x00 -> 0xFF).
     */
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

    /**
     * chapter. frames into a local ChapterEntity structure.
     */
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
            source = AudiobookSchema.ChapterSource.EMBEDDED
        )
    }

    /**
     * e.g. TIT2. inside chapter frames to retrieve titles.
     */
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

    /**
     * Dedupes, sorts, and aligns start times and durations across extracted embedded chapters.
     */
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
                title = chapter.title.ifBlank { RangeAudioParserSupport.chapterTitle(index) },
                durationMs = inferredDuration.coerceAtLeast(0L)
            )
        }
    }
}

/**
 * Shared container representing metadata parsed from ID3v1 or ID3v2 stream structures.
 */
internal data class ParsedId3Tag(
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
