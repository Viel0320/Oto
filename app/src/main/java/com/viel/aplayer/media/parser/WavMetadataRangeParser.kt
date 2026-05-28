package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata
import com.viel.aplayer.media.parser.RangeAudioParserSupport.cString
import java.nio.charset.StandardCharsets

// wav parser 只扫描 RIFF 头部附近的 chunk；
// 从 fmt/data 估算时长，从 LIST-INFO 读取有限元数据，不再让系统 retriever 去整文件探测。
internal object WavMetadataRangeParser : RangeAudioFormatParser {
    override fun supports(displayName: String): Boolean =
        displayName.endsWith(".wav", ignoreCase = true)

    override suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions
    ): RangeAudioParseResult? {
        val headBytes = input.readRange(0L, minOf(input.fileSize, MAX_HEAD_SCAN_BYTES.toLong()).toInt()) ?: return null
        if (headBytes.size < 12) return null
        val riff = headBytes.copyOfRange(0, 4).toString(StandardCharsets.ISO_8859_1)
        val wave = headBytes.copyOfRange(8, 12).toString(StandardCharsets.ISO_8859_1)
        if (riff != "RIFF" || wave != "WAVE") return null

        var cursor = 12
        var title = ""
        var author = ""
        var narrator = ""
        var album = ""
        var description = ""
        var year = ""
        var byteRate = 0L
        var dataSize = 0L

        while (cursor + 8 <= headBytes.size) {
            val chunkId = headBytes.copyOfRange(cursor, cursor + 4).toString(StandardCharsets.ISO_8859_1)
            val chunkSize = RangeAudioParserSupport.run { headBytes.readUInt32LE(cursor + 4) }.toInt()
            val chunkDataStart = cursor + 8
            when (chunkId) {
                "data" -> {
                    // WAV 的时长只依赖 data chunk 头里的 chunkSize，
                    // 即使整个 data 块远超头部扫描窗口，也应该立刻记录长度，不能因为 chunkData 没读全就返回 0 时长。
                    if (chunkSize >= 0) {
                        dataSize = chunkSize.toLong()
                    }
                }
            }
            if (chunkSize < 0 || chunkDataStart + chunkSize > headBytes.size) break
            val chunkData = headBytes.copyOfRange(chunkDataStart, chunkDataStart + chunkSize)
            when (chunkId) {
                "fmt " -> if (chunkData.size >= 12) {
                    // WAV fmt chunk 里的 byteRate 位于 audioFormat(2) + channels(2) + sampleRate(4) 之后，也就是偏移 8。
                    byteRate = RangeAudioParserSupport.run { chunkData.readUInt32LE(8) }
                }
                "LIST" -> if (chunkData.size >= 4 && chunkData.copyOfRange(0, 4).toString(StandardCharsets.ISO_8859_1) == "INFO") {
                    var infoCursor = 4
                    while (infoCursor + 8 <= chunkData.size) {
                        val infoId = chunkData.copyOfRange(infoCursor, infoCursor + 4).toString(StandardCharsets.ISO_8859_1)
                        val infoSize = RangeAudioParserSupport.run { chunkData.readUInt32LE(infoCursor + 4) }.toInt()
                        val infoDataStart = infoCursor + 8
                        if (infoSize < 0 || infoDataStart + infoSize > chunkData.size) break
                        val value = cString(chunkData, infoDataStart, infoSize)
                        when (infoId) {
                            "INAM" -> if (title.isBlank()) title = value
                            "IART" -> if (author.isBlank()) author = value
                            "IPRD" -> if (album.isBlank()) album = value
                            // WAV INFO 没有统一的自定义简介字段，至少在导入层处理 ICMT 里的字面量换行。
                            "ICMT" -> if (description.isBlank()) description = MetadataDescriptionRules.normalizeDescriptionText(value)
                            "ICRD" -> if (year.isBlank()) year = value
                            "IENG", "ITCH" -> if (narrator.isBlank()) narrator = value
                        }
                        infoCursor = infoDataStart + infoSize + (infoSize and 1)
                    }
                }
            }
            cursor = chunkDataStart + chunkSize + (chunkSize and 1)
            if (dataSize > 0L && byteRate > 0L && cursor >= headBytes.size) break
        }

        val durationMs = if (dataSize > 0L && byteRate > 0L) {
            (dataSize * 1000L) / byteRate
        } else {
            0L
        }

        return RangeAudioParseResult(
            metadata = AudiobookMetadata(
                title = title,
                author = author,
                narrator = narrator,
                album = album,
                description = description,
                year = year,
                durationMs = durationMs
            )
        )
    }

    private const val MAX_HEAD_SCAN_BYTES = 512 * 1024
}
