package com.viel.aplayer.abs.playback

import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.dao.LibraryRootDao
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.logger.AbsPlaybackLogger

class AbsPlaybackCredentialResolver(
    private val libraryRootDao: LibraryRootDao,
    private val credentialStore: AbsCredentialStore
) {
    suspend fun resolve(book: BookEntity): AbsPlaybackSessionSyncer.CredentialSnapshot? {
        // Credential Resolution Logging (Trace session credentials verification)
        // Credential resolution is the most common point where a playback session silently returns null.
        // Therefore, we explicitly log whether the library root and the corresponding credentials are found in the store.
        AbsPlaybackLogger.logResolveCredentialStart(bookId = book.id, rootId = book.rootId)
        val root = libraryRootDao.getRootById(book.rootId)
        val credential = root?.let { credentialStore.get(it.credentialId) }
        AbsPlaybackLogger.logResolveCredentialResult(
            bookId = book.id,
            rootId = book.rootId,
            foundRoot = root != null,
            foundCredential = credential != null
        )
        if (root == null || credential == null) return null
        return AbsPlaybackSessionSyncer.CredentialSnapshot(
            baseUrl = credential.baseUrl,
            token = credential.token
        )
    }
}
