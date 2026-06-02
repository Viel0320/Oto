package com.viel.aplayer.media

/**
 * 播放链统一的 `mediaId` 编解码器。
 *
 * 设计约束：
 * 1. `bookId` 和 `fileId` 都允许包含 `:`，因此绝不能再依赖任意分隔符切分。
 * 2. 新格式必须可逆、稳定、纯字符串，并能在 Media3 / Session / 日志里安全传递。
 * 3. 为了兼容旧会话，解析器保留一条旧格式兜底，但新生成的 `mediaId` 一律使用新格式。
 */
object PlaybackMediaId {
    private const val PREFIX = "aplayer-mid:v1:"

    data class Parts(
        val bookId: String,
        val fileId: String
    )

    /**
     * 新格式：
     * `aplayer-mid:v1:<bookIdLength>:<bookId><fileId>`
     *
     * 这样解析时只需要先读出 `bookIdLength`，再按固定长度截取 `bookId`，
     * 剩余部分就是完整的 `fileId`，不受其中冒号数量影响。
     */
    fun compose(bookId: String, fileId: String): String =
        "$PREFIX${bookId.length}:$bookId$fileId"

    fun parse(mediaId: String?): Parts? {
        if (mediaId.isNullOrBlank()) return null
        return parseV1(mediaId)
    }

    private fun parseV1(mediaId: String): Parts? {
        if (!mediaId.startsWith(PREFIX)) return null
        val lengthStart = PREFIX.length
        val lengthSeparatorIndex = mediaId.indexOf(':', startIndex = lengthStart)
        if (lengthSeparatorIndex == -1) return null

        val bookIdLength = mediaId.substring(lengthStart, lengthSeparatorIndex).toIntOrNull() ?: return null
        val bookIdStart = lengthSeparatorIndex + 1
        val bookIdEnd = bookIdStart + bookIdLength
        if (bookIdLength <= 0 || bookIdEnd > mediaId.length) return null

        val bookId = mediaId.substring(bookIdStart, bookIdEnd)
        val fileId = mediaId.substring(bookIdEnd)
        if (bookId.isBlank() || fileId.isBlank()) return null
        return Parts(bookId = bookId, fileId = fileId)
    }

}
