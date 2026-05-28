package com.viel.aplayer.media.parser

import java.util.Locale

// description 的字段选择和换行修复属于导入语义规则，不属于底层范围读取工具。
// 单独维护在这里，避免 MP3/MP4/FLAC/Opus 等 parser 各自复制规则，也避免把业务判断塞进 RangeAudioParserSupport。
internal object MetadataDescriptionRules {
    // 这些字段名按“用户维护的简介字段优先、通用备注字段兜底”的顺序排列。
    // 不同写入工具会使用 Description、Long Description、Summary、Comment 等变体，比较前会统一规范化。
    private val preferredDescriptionKeys = listOf(
        "description",
        "desc",
        "longdescription",
        "synopsis",
        "summary",
        "comment",
        "comments"
    )

    fun normalizeDescriptionText(value: String): String =
        // 导入层统一处理两类换行：真实 CRLF/CR，以及部分标签工具写入的字面量 "\n" / "\r\n"。
        // UI 只负责展示和 HTML 解析，不再猜测各音频格式的换行保存方式。
        value
            .replace("\\r\\n", "\n")
            .replace("\\n", "\n")
            .replace("\\r", "\n")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

    fun normalizedFieldKey(value: String): String =
        // 自定义元数据字段名常见写法包括 Long Description、LONG_DESCRIPTION、desc 等。
        // 统一压成小写字母数字键后再比较，避免大小写和分隔符差异影响优先级。
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")

    fun isDescriptionFieldName(value: String?): Boolean =
        // ID3 TXXX 等自定义字段需要先验证字段名，再把字段值当作 description 使用。
        normalizedFieldKey(value.orEmpty()) in preferredDescriptionKeys

    fun firstDescriptionFromCustomAndFallback(
        values: Map<String, String>,
        customPrefix: String,
        fallbackKeys: List<String>
    ): String =
        // MP4 freeform 字段会先被保存成 "$prefix:字段名" 形式；
        // 这里统一先扫自定义字段，再扫格式标准字段，避免调用方各自拼接优先级。
        firstDescriptionFromOrderedKeys(
            values = values,
            keys = preferredDescriptionKeys.map { key -> "$customPrefix:$key" } + fallbackKeys
        )

    fun firstDescriptionFromFields(values: Map<String, String>): String {
        // Vorbis/FLAC/Opus 的 comment map 本身就是开放字段集合；
        // ID3 TXXX 自定义文本帧也可以复用同一套字段名优先级，最后才会落到 COMMENT/COMMENTS 这类泛用备注。
        return preferredDescriptionKeys.firstNotNullOfOrNull { preferredKey ->
            values.entries.firstOrNull { (key, value) ->
                normalizedFieldKey(key) == preferredKey && value.isNotBlank()
            }?.value?.let(::normalizeDescriptionText)?.takeIf { it.isNotBlank() }
        }.orEmpty()
    }

    private fun firstDescriptionFromOrderedKeys(values: Map<String, String>, keys: List<String>): String =
        // MP4 标准 atom 名称需要精确匹配，例如 ©des / ©cmt 不能经过字母数字压缩后再查表。
        keys.firstNotNullOfOrNull { key ->
            values[key]
                ?.let(::normalizeDescriptionText)
                ?.takeIf { it.isNotBlank() }
        }.orEmpty()
}
