package com.viel.aplayer.abs.mapping

import java.security.MessageDigest

class AbsRemoteIdMapper {
    fun serverKey(normalizedBaseUrl: String, userId: String?): String =
        sha256("$normalizedBaseUrl|${userId.orEmpty()}").take(16)

    fun rootId(serverKey: String, libraryId: String): String =
        "abs:$serverKey:library:$libraryId"

    fun bookId(serverKey: String, libraryItemId: String): String =
        "abs:$serverKey:item:$libraryItemId"

    fun bookFileId(serverKey: String, libraryItemId: String, trackIndex: Int): String =
        "abs:$serverKey:item:$libraryItemId:track:$trackIndex"

    fun sessionLocalId(serverKey: String, sessionId: String): String =
        "abs:$serverKey:session:$sessionId"

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
