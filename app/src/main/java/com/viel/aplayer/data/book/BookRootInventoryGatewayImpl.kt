package com.viel.aplayer.data.book

import com.viel.aplayer.data.dao.BookDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Implements BookRootInventoryGateway.
 *
 * Exposes only the root-scoped book ids that LibraryRootManagementUseCase needs before cascade deletion
 * removes BookFileEntity rows. It intentionally does not carry catalog or metadata surfaces.
 */
class BookRootInventoryGatewayImpl(
    private val bookDao: BookDao
) : BookRootInventoryGateway {

    override suspend fun getBookIdsByRootId(rootId: String): List<String> = withContext(Dispatchers.IO) {
        bookDao.getBookIdsByRootId(rootId)
    }
}
