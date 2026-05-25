package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import java.nio.charset.StandardCharsets
import java.util.Base64

// ogg/opus 的 packet、comment、granule 与图片块解析都内聚在本文件内。
internal object OggOpusMetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".ogg", ignoreCase = true) ||
            displayName.endsWith(".opus", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult? {
        val packets = readLeadingPackets(input) ?: return null
        val firstPacket = packets.firstOrNull() ?: return null
        val firstBytes = firstPacket.bytes
        return when {
            firstBytes.startsWithAscii("OpusHead") -> parseOpus(input, packets, options)
            firstBytes.size >= 7 && firstBytes[0] == 0x01.toByte() && firstBytes.copyOfRange(1, 7).startsWithAscii("vorbis") ->
                parseVorbis(input, packets, options)
            else -> null
        }
    }

    private suspend fun parseOpus(
        input: RangeAudioParserInput,
        packets: List<OggPacket>,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult? {
        val head = packets.firstOrNull()?.bytes ?: return null
        if (head.size < 12) return null
        val preSkip = RangeAudioParserSupport.run { head.readUInt16LE(10) }
        val tagsPacket = packets.drop(1).firstOrNull { it.bytes.startsWithAscii("OpusTags") }?.bytes
        val comments = tagsPacket?.let { decodeVorbisCommentBlock(it, 8) }.orEmpty()
        val lastGranule = findLastGranulePosition(input) ?: 0L
        val durationMs = ((lastGranule - preSkip).coerceAtLeast(0L) * 1000L) / 48_000L
        return RangeAudioParseResult(
            metadata = buildMetadataFromComments(comments, durationMs),
            embeddedCover = if (options.includeEmbeddedCover) comments["METADATA_BLOCK_PICTURE"]?.let(::decodeBase64Picture) else null
        )
    }

    private suspend fun parseVorbis(
        input: RangeAudioParserInput,
        packets: List<OggPacket>,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult? {
        val identification = packets.firstOrNull()?.bytes ?: return null
        if (identification.size < 16) return null
        val sampleRate = RangeAudioParserSupport.run { identification.readUInt32LE(12) }
        val commentPacket = packets.drop(1).firstOrNull {
            it.bytes.size >= 7 && it.bytes[0] == 0x03.toByte() && it.bytes.copyOfRange(1, 7).startsWithAscii("vorbis")
        }?.bytes
        val comments = commentPacket?.let { decodeVorbisCommentBlock(it, 7) }.orEmpty()
        val lastGranule = findLastGranulePosition(input) ?: 0L
        val durationMs = if (sampleRate > 0L && lastGranule > 0L) {
            (lastGranule * 1000L) / sampleRate
        } else {
            0L
        }
        return RangeAudioParseResult(
            metadata = buildMetadataFromComments(comments, durationMs),
            embeddedCover = if (options.includeEmbeddedCover) comments["METADATA_BLOCK_PICTURE"]?.let(::decodeBase64Picture) else null
        )
    }

    private fun buildMetadataFromComments(comments: Map<String, String>, durationMs: Long): AudiobookMetadata =
        AudiobookMetadata(
            title = RangeAudioParserSupport.mergeFirstNonBlank(comments["TITLE"]),
            author = RangeAudioParserSupport.mergeFirstNonBlank(comments["ARTIST"], comments["AUTHOR"]),
            narrator = RangeAudioParserSupport.mergeFirstNonBlank(comments["NARRATOR"], comments["READER"], comments["PERFORMER"], comments["COMPOSER"]),
            album = RangeAudioParserSupport.mergeFirstNonBlank(comments["ALBUM"]),
            trackIndex = RangeAudioParserSupport.normalizeTrackIndex(
                RangeAudioParserSupport.mergeFirstNonBlank(comments["TRACKNUMBER"], comments["TRACK"])
            ),
            description = RangeAudioParserSupport.mergeFirstNonBlank(comments["DESCRIPTION"], comments["COMMENT"], comments["SUMMARY"]),
            year = RangeAudioParserSupport.mergeFirstNonBlank(comments["DATE"], comments["YEAR"]),
            durationMs = durationMs
        )

    private fun decodeVorbisCommentBlock(bytes: ByteArray, startOffset: Int = 0): Map<String, String> {
        var cursor = startOffset
        if (cursor + 8 > bytes.size) return emptyMap()
        val vendorLength = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }.toInt()
        cursor += 4 + vendorLength
        if (cursor + 4 > bytes.size) return emptyMap()
        val commentCount = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }.toInt()
        cursor += 4
        val comments = linkedMapOf<String, String>()
        repeat(commentCount) {
            if (cursor + 4 > bytes.size) return comments
            val length = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }.toInt()
            cursor += 4
            if (length < 0 || cursor + length > bytes.size) return comments
            val entry = bytes.copyOfRange(cursor, cursor + length).toString(StandardCharsets.UTF_8)
            cursor += length
            val separatorIndex = entry.indexOf('=')
            if (separatorIndex <= 0) return@repeat
            val key = entry.substring(0, separatorIndex).trim().uppercase()
            val value = entry.substring(separatorIndex + 1).trim()
            if (key.isNotBlank() && value.isNotBlank() && key !in comments) {
                comments[key] = value
            }
        }
        return comments
    }

    private fun decodeBase64Picture(value: String): EmbeddedCoverBytes? =
        runCatching {
            parseFlacPictureBlock(Base64.getDecoder().decode(value))
        }.getOrNull()

    private fun parseFlacPictureBlock(bytes: ByteArray): EmbeddedCoverBytes? {
        if (bytes.size < 32) return null
        var cursor = 0
        RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }.toInt()
        cursor += 4
        val mimeLength = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }.toInt()
        cursor += 4
        if (cursor + mimeLength > bytes.size) return null
        val mimeType = bytes.copyOfRange(cursor, cursor + mimeLength).toString(StandardCharsets.UTF_8).ifBlank { null }
        cursor += mimeLength
        val descriptionLength = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }.toInt()
        cursor += 4 + descriptionLength
        if (cursor + 20 > bytes.size) return null
        cursor += 16
        val pictureLength = RangeAudioParserSupport.run { bytes.readUInt32BE(cursor) }.toInt()
        cursor += 4
        if (pictureLength <= 0 || cursor + pictureLength > bytes.size) return null
        val imageBytes = bytes.copyOfRange(cursor, cursor + pictureLength)
        return imageBytes.takeIf { it.isNotEmpty() }?.let { EmbeddedCoverBytes(bytes = it, mimeType = mimeType) }
    }

    private suspend fun readLeadingPackets(input: RangeAudioParserInput): List<OggPacket>? {
        val packets = mutableListOf<OggPacket>()
        var offset = 0L
        var packetBuffer = ByteArray(0)
        while (offset < input.fileSize && offset < MAX_HEAD_SCAN_BYTES && packets.size < 3) {
            val page = readPage(input, offset) ?: break
            offset += page.totalLength.toLong()
            var bodyOffset = 0
            page.lacingValues.forEach { segmentSize ->
                if (bodyOffset + segmentSize > page.body.size) return@forEach
                packetBuffer += page.body.copyOfRange(bodyOffset, bodyOffset + segmentSize)
                bodyOffset += segmentSize
                if (segmentSize < 255) {
                    packets.add(OggPacket(packetBuffer))
                    packetBuffer = ByteArray(0)
                }
            }
        }
        return packets.takeIf { it.isNotEmpty() }
    }

    private suspend fun findLastGranulePosition(input: RangeAudioParserInput): Long? {
        val windowSize = minOf(MAX_TAIL_SCAN_BYTES.toLong(), input.fileSize).toInt()
        val startOffset = (input.fileSize - windowSize).coerceAtLeast(0L)
        val tail = input.readRange(startOffset, windowSize) ?: return null
        for (index in tail.size - OGG_HEADER_BYTES downTo 0) {
            if (!tail.isOggCaptureAt(index)) continue
            if (index + OGG_HEADER_BYTES > tail.size) continue
            return RangeAudioParserSupport.run { tail.readUInt64LE(index + 6) }
        }
        return null
    }

    private suspend fun readPage(input: RangeAudioParserInput, offset: Long): OggPage? {
        val header = input.readRange(offset, OGG_HEADER_BYTES) ?: return null
        if (header.size < OGG_HEADER_BYTES || !header.startsWithAscii("OggS")) return null
        val segmentCount = header[26].toInt() and 0xff
        val fullHeader = input.readRange(offset, OGG_HEADER_BYTES + segmentCount) ?: return null
        if (fullHeader.size < OGG_HEADER_BYTES + segmentCount) return null
        val lacingValues = IntArray(segmentCount) { index -> fullHeader[OGG_HEADER_BYTES + index].toInt() and 0xff }
        val bodyLength = lacingValues.sum()
        val body = input.readRange(offset + OGG_HEADER_BYTES + segmentCount, bodyLength) ?: return null
        return OggPage(
            granulePosition = RangeAudioParserSupport.run { header.readUInt64LE(6) },
            lacingValues = lacingValues,
            body = body,
            totalLength = OGG_HEADER_BYTES + segmentCount + bodyLength
        )
    }

    private fun ByteArray.startsWithAscii(value: String): Boolean =
        size >= value.length && copyOfRange(0, value.length).toString(StandardCharsets.ISO_8859_1) == value

    private fun ByteArray.isOggCaptureAt(offset: Int): Boolean =
        offset + 4 <= size && copyOfRange(offset, offset + 4).toString(StandardCharsets.ISO_8859_1) == "OggS"

    private data class OggPage(
        val granulePosition: Long,
        val lacingValues: IntArray,
        val body: ByteArray,
        val totalLength: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as OggPage

            if (granulePosition != other.granulePosition) return false
            if (totalLength != other.totalLength) return false
            if (!lacingValues.contentEquals(other.lacingValues)) return false
            if (!body.contentEquals(other.body)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = granulePosition.hashCode()
            result = 31 * result + totalLength
            result = 31 * result + lacingValues.contentHashCode()
            result = 31 * result + body.contentHashCode()
            return result
        }
    }

    private data class OggPacket(
        val bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as OggPacket

            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            return bytes.contentHashCode()
        }
    }

    private const val OGG_HEADER_BYTES = 27
    private const val MAX_HEAD_SCAN_BYTES = 512 * 1024L
    private const val MAX_TAIL_SCAN_BYTES = 512 * 1024
}
