package com.viel.oto.data.cover

import com.viel.oto.data.entity.LibraryRootEntity

/**
 * Remote artwork cache contract used by data-owned cover recovery.
 *
 * Implementations live with source-specific remote adapters; data code only needs the persisted image paths that
 * can be written back to Room for an existing local book.
 */
interface RemoteCoverStore {
    /**
     * Downloads and persists remote artwork for one mirrored library item.
     */
    suspend fun downloadCover(root: LibraryRootEntity, remoteItemId: String): CoverImageResult
}
