package com.viel.aplayer.media.parser

import com.viel.aplayer.media.AudiobookMetadata

// 统一的内嵌封面载体从 MP4 专属类型抽离出来，供 mp3/flac/ogg-opus 等所有范围读取 parser 复用。
internal data class EmbeddedCoverBytes(
    val bytes: ByteArray,
    val mimeType: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as EmbeddedCoverBytes

        if (!bytes.contentEquals(other.bytes)) return false
        if (mimeType != other.mimeType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }
}

// parser 的最小输入只保留 sourceId、fileSize 与 readRange 回调；
// 这样 parser 自己决定读哪些 offset/length，VfsFileReader 只负责提供底层 byte range 能力。
internal data class RangeAudioParserInput(
    val sourceId: String,
    val fileSize: Long,
    val readRange: suspend (offset: Long, length: Int) -> ByteArray?
)

// 是否提取内嵌封面由调用方显式声明，避免普通 metadata 路径误读大尺寸图片块。
internal data class RangeAudioParseOptions(
    val includeEmbeddedCover: Boolean = false
)

// 所有格式 parser 统一返回同一份结构化结果，MetadataResolver 再做标题兜底与乱码修正。
internal data class RangeAudioParseResult(
    val metadata: AudiobookMetadata,
    val embeddedCover: EmbeddedCoverBytes? = null
)

// 每个格式 parser 只负责自己支持的扩展名与容器结构，不共享“读取策略”的主导权。
internal interface RangeAudioFormatParser {
    fun supports(displayName: String): Boolean

    suspend fun parse(
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions = RangeAudioParseOptions()
    ): RangeAudioParseResult?
}

// 统一的路由层只负责按扩展名分发到具体 parser，不介入任何格式内部的范围读取细节。
internal object RangeAudioParserRouter {
    private val parsers: List<RangeAudioFormatParser> = listOf(
        Mp3MetadataRangeParser,
        FlacMetadataRangeParser,
        OggOpusMetadataRangeParser,
        WavMetadataRangeParser,
        AacMetadataRangeParser
    )

    suspend fun parse(
        displayName: String,
        input: RangeAudioParserInput,
        options: RangeAudioParseOptions = RangeAudioParseOptions()
    ): RangeAudioParseResult? =
        parsers.firstOrNull { parser -> parser.supports(displayName) }?.parse(input, options)
}
