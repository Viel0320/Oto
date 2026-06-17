package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import java.nio.charset.StandardCharsets
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
                        // Read totalSamples (36 bits) by prepending the 4 bits of offset 13 to the 32-bit big-endian value at offset 14.
                        val totalSamples = ((block[13].toLong() and 0x0f) shl 32) or
                            RangeAudioParserSupport.run { block.readUInt32BE(14) }
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
                    // Vorbis comment 是开放字段集合，简介字段优先于泛用 COMMENT 备注。
                    description = description ?: MetadataDescriptionRules.firstDescriptionFromFields(comments)
                    year = year ?: RangeAudioParserSupport.mergeFirstNonBlank(comments["DATE"], comments["YEAR"])
                    trackIndex = trackIndex ?: RangeAudioParserSupport.normalizeTrackIndex(
                        RangeAudioParserSupport.mergeFirstNonBlank(comments["TRACKNUMBER"], comments["TRACK"])
                    )
                    if (options.includeEmbeddedCover && embeddedCover == null) {
                        embeddedCover = comments["METADATA_BLOCK_PICTURE"]?.let(FlacPictureCodec::decodeBase64)
                    }
                }
                6 -> if (options.includeEmbeddedCover && embeddedCover == null) {
                    embeddedCover = FlacPictureCodec.parseBlock(block)
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

        // 详尽的中文注释：对 vendorLength 长度字段进行无符号和防整数溢出的物理范围拦截，
        // 确保读取的长度值绝对不超过当前 bytes 字节数组所能容纳的最大剩余边界
        val vendorLengthLong = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }
        val maxRemainingVendor = bytes.size - cursor - 8 // 后续还有 commentCount 需要 4 字节
        if (vendorLengthLong !in 0L..maxRemainingVendor) {
            return emptyMap()
        }
        val vendorLength = vendorLengthLong.toInt()
        cursor += 4 + vendorLength

        if (cursor + 4 > bytes.size) return emptyMap()
        val commentCountLong = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }
        if (commentCountLong < 0L || commentCountLong > Int.MAX_VALUE.toLong()) {
            return emptyMap()
        }
        val commentCount = commentCountLong.toInt()
        cursor += 4

        val comments = linkedMapOf<String, String>()
        repeat(commentCount) {
            if (cursor + 4 > bytes.size) return comments
            val lengthLong = RangeAudioParserSupport.run { bytes.readUInt32LE(cursor) }
            cursor += 4
            // 详尽的中文注释：对每一个 comment entry 的长度进行无符号安全边界验证，
            // length 必须完全位于 [0, bytes.size - cursor] 物理区间内，完美规避正数相加可能引起的溢出漏洞
            if (lengthLong !in 0L..(bytes.size - cursor).toLong()) {
                return comments
            }
            val length = lengthLong.toInt()
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

    private const val MAX_METADATA_SCAN_BYTES = 2 * 1024 * 1024
}
