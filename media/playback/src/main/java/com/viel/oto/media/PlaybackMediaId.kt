package com.viel.oto.media

import java.security.MessageDigest

/**
 * Unified media identifier compiler.
 *
 * Design constraints:
 * 1. Both `bookId` and `fileId` may contain colons (`:`), so simple delimiter splits are prohibited.
 * 2. The compiled format must be reversible, stable, plain text, and safe for Media3/Session pipelines.
 * 3. The compiled identifier carries an integrity suffix so truncated or retargeted payloads fail closed.
 */
object PlaybackMediaId {
    private const val PREFIX = "oto-mid:v1:"
    private const val CHECKSUM_HEX_LENGTH = 16

    data class Parts(
        val bookId: String,
        val fileId: String
    )

    /**
     * Structure layout for colon-resilient identifiers.
     * Format: `oto-mid:v1:<bookIdLength>:<bookId><fileId>:<checksum>`
     *
     * The length prefix preserves colon-bearing IDs, while the checksum binds the parsed book/file
     * split to the original compose call so malformed-but-length-valid strings cannot retarget playback.
     */
    fun compose(bookId: String, fileId: String): String =
        "$PREFIX${bookId.length}:$bookId$fileId:${checksum(bookId, fileId)}"

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
        val checksumSeparatorIndex = mediaId.lastIndexOf(':')
        if (checksumSeparatorIndex <= bookIdStart) return null
        val providedChecksum = mediaId.substring(checksumSeparatorIndex + 1)
        if (!providedChecksum.matches(Regex("[0-9a-f]{$CHECKSUM_HEX_LENGTH}"))) return null

        val bookIdEnd = bookIdStart + bookIdLength
        if (bookIdLength <= 0 || bookIdEnd > checksumSeparatorIndex) return null

        val bookId = mediaId.substring(bookIdStart, bookIdEnd)
        val fileId = mediaId.substring(bookIdEnd, checksumSeparatorIndex)
        if (bookId.isBlank() || fileId.isBlank()) return null
        if (!checksumMatches(bookId, fileId, providedChecksum)) return null
        return Parts(bookId = bookId, fileId = fileId)
    }

    /**
     * Computes a short checksum over the parsed fields, not just the concatenated payload, so moving
     * the length boundary between `bookId` and `fileId` invalidates the identifier.
     */
    private fun checksum(bookId: String, fileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$bookId\u0000$fileId".toByteArray(Charsets.UTF_8))
        return digest.take(CHECKSUM_HEX_LENGTH / 2).joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun checksumMatches(bookId: String, fileId: String, providedChecksum: String): Boolean {
        val expected = checksum(bookId, fileId)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            providedChecksum.toByteArray(Charsets.UTF_8)
        )
    }

}
