package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.min

// flac 相关的 metadata block、Vorbis comment、PICTURE 解析都留在本文件内，
// 不再通过共享的格式 helper 间接完成。
internal object FlacMetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".flac", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult? {
        val headBytes = input.readRange(0L, min(input.fileSize, MAX_METADATA_SCAN_BYTES.toLong()).toInt()) ?: return null
        if (headBytes.size < 4 || !headBytes.copyOfRange(0, 4).contentEquals(byteArrayOf('f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte()))) {
            return null
        }

        var cursor = 4
        var title: String? = null
        var author: String? = null
        var narrator: String? = null
        var album: String? = null
        var description: String? = null
        var year: String? = null
        var trackIndex: Int? = null
        var durationMs = 0L
        var embeddedCover: EmbeddedCoverBytes? = null

        while (cursor + 4 <= headBytes.size) {
            val header = headBytes[cursor].toInt() and 0xff
            val isLast = (header and 0x80) != 0
            val blockType = header and 0x7f
            val blockLength = RangeAudioParserSupport.run { headBytes.readUInt24BE(cursor + 1) }
            cursor += 4
            if (blockLength < 0 || cursor + blockLength > headBytes.size) break
            val block = headBytes.copyOfRange(cursor, cursor + blockLength)
            when (blockType) {
                0 -> {
                    if (block.size >= 18) {
                        val sampleRate = ((block[10].toInt() and 0xff) shl 12) or
                            ((block[11].toInt() and 0xff) shl 4) or
                            ((block[12].toInt() and 0xf0) shr 4)
                        val totalSamples = ((block[13].toLong() and 0x0f) shl 32) or
                            ((block[14].toLong() and 0xff) shl 24) or
                            ((block[15].toLong() and 0xff) shl 16) or
                            ((block[16].toLong() and 0xff) shl 8) or
                            (block[17].toLong() and 0xff)
                        if (sampleRate > 0 && totalSamples > 0) {
                            durationMs = (totalSamples * 1000L) / sampleRate.toLong()
                        }
                    }
                }
                4 -> {
                    val comments = decodeVorbisCommentBlock(block)
                    title = title ?: comments["TITLE"]
                    author = author ?: RangeAudioParserSupport.mergeFirstNonBlank(comments["ARTIST"], comments["AUTHOR"])
                    narrator = narrator ?: RangeAudioParserSupport.mergeFirstNonBlank(comments["NARRATOR"], comments["READER"], comments["PERFORMER"], comments["COMPOSER"])
                    album = album ?: comments["ALBUM"]
                    description = description ?: RangeAudioParserSupport.mergeFirstNonBlank(comments["DESCRIPTION"], comments["COMMENT"], comments["SUMMARY"])
                    year = year ?: RangeAudioParserSupport.mergeFirstNonBlank(comments["DATE"], comments["YEAR"])
                    trackIndex = trackIndex ?: RangeAudioParserSupport.normalizeTrackIndex(
                        RangeAudioParserSupport.mergeFirstNonBlank(comments["TRACKNUMBER"], comments["TRACK"])
                    )
                    if (options.includeEmbeddedCover && embeddedCover == null) {
                        embeddedCover = comments["METADATA_BLOCK_PICTURE"]?.let(::decodeBase64Picture)
                    }
                }
                6 -> if (options.includeEmbeddedCover && embeddedCover == null) {
                    embeddedCover = parseFlacPictureBlock(block)
                }
            }
            cursor += blockLength
            if (isLast) break
        }

        return RangeAudioParseResult(
            metadata = AudiobookMetadata(
                title = title.orEmpty(),
                author = author.orEmpty(),
                narrator = narrator.orEmpty(),
                album = album.orEmpty(),
                trackIndex = trackIndex,
                description = description.orEmpty(),
                year = year.orEmpty(),
                durationMs = durationMs
            ),
            embeddedCover = embeddedCover
        )
    }

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

    private const val MAX_METADATA_SCAN_BYTES = 2 * 1024 * 1024
}
