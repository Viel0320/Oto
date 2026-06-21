package com.viel.aplayer.media

/**
 * Unified media identifier compiler.
 *
 * Design constraints:
 * 1. Both `bookId` and `fileId` may contain colons (`:`), so simple delimiter splits are prohibited.
 * 2. The compiled format must be reversible, stable, plain text, and safe for Media3/Session pipelines.
 * 3. Implements backward-compatible decoding fallback steps, while compiling using the new schema.
 */
object PlaybackMediaId {
    private const val PREFIX = "aplayer-mid:v1:"

    data class Parts(
        val bookId: String,
        val fileId: String
    )

    /**
     * Structure layout for colon-resilient identifiers.
     * Format: `aplayer-mid:v1:<bookIdLength>:<bookId><fileId>`
     *
     * Guarantees safe parsing by recording length parameters beforehand.
     * Decoders split segments by exact character length boundaries.
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
