package com.viel.aplayer.data.book

/**
 * Root-scoped book identity lookup.
 * Destructive root-management workflows need stable book ids before cascade deletion removes file rows used by
 * manual-download cleanup, but they should not receive broad catalog search or metadata mutation capabilities.
 */
interface BookRootInventoryGateway {
    /**
     * Capture deletion targets before root-owned rows disappear.
     * Returns only book identifiers so root cleanup can remove download tasks without loading full audiobook entities.
     */
    suspend fun getBookIdsByRootId(rootId: String): List<String>
}
