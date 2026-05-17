package com.viel.aplayer.library

import com.viel.aplayer.data.AppDatabase
import com.viel.aplayer.data.PendingScanActionEntity
import java.util.UUID

/**
 * 协调器：对比扫描快照与数据库，生成同步任务。
 */
class LibraryReconciler(private val database: AppDatabase) {
    private val bookDao = database.bookDao()

    data class ReconciliationResult(
        val scanId: String,
        val newClaims: List<ClaimSource>,
        val unavailableBookIds: List<String>,
        val pendingActions: List<PendingScanActionEntity>,
        val discoveredCount: Int
    )

    /**
     * 计算差异：对比快照和数据库。
     */
    suspend fun reconcile(snapshot: LibrarySnapshot): ReconciliationResult {
        val allBooks = bookDao.getAllBooksOnce()
        val existingSourceUrisMap = allBooks.associateBy { it.sourceUri }
        
        // 建立 物理文件 URI -> 书籍 ID 的反向索引，用于检测竞争冲突
        val fileToBookMap = bookDao.getFileUriToBookIdMap().associate { it.uri to it.bookId }
        
        val pendingActions = mutableListOf<PendingScanActionEntity>()
        val newClaims = mutableListOf<ClaimSource>()

        snapshot.claims.forEach { claim ->
            val existingBook = existingSourceUrisMap[claim.sourceUri]
            if (existingBook == null) {
                // 1. 检查是否有文件竞争
                val conflictingBookId = claim.referencedFileUris.firstNotNullOfOrNull { fileToBookMap[it] }
                
                if (conflictingBookId != null) {
                    pendingActions.add(PendingScanActionEntity(
                        id = UUID.randomUUID().toString(),
                        scanSessionId = snapshot.scanId,
                        actionKey = "CONFLICT:${claim.sourceUri}",
                        type = "CONFLICT",
                        status = "PENDING",
                        bookId = conflictingBookId,
                        payloadJson = "{}", 
                        message = "Source \"${claim.displayName}\" conflicts with an existing book.",
                        lastSeenScanId = snapshot.scanId
                    ))
                } else {
                    newClaims.add(claim)
                }
            } else {
                // 2. 来源已存在，检查是否发生了变化（结构更新）
                val hasChanged = existingBook.sourceLastModified != claim.sourceLastModified || 
                                 existingBook.sourceFileSize != claim.sourceFileSize
                
                if (hasChanged) {
                    pendingActions.add(PendingScanActionEntity(
                        id = UUID.randomUUID().toString(),
                        scanSessionId = snapshot.scanId,
                        actionKey = "UPDATE:${existingBook.id}",
                        type = "UPDATE_EXISTING",
                        status = "PENDING",
                        bookId = existingBook.id,
                        payloadJson = "{}", // TODO: 存储更新内容
                        message = "Manifest for \"${existingBook.title}\" has changed.",
                        lastSeenScanId = snapshot.scanId
                    ))
                }

                // 自动恢复
                if (existingBook.status == "UNAVAILABLE") {
                    bookDao.updateBookStatus(existingBook.id, "READY")
                }
            }
        }
        
        // 3. 识别缺失书籍
        val currentScanUris = snapshot.claims.map { it.sourceUri }.toSet()
        val unavailableIds = allBooks.filter { 
            it.status != "UNAVAILABLE" && !currentScanUris.contains(it.sourceUri) 
        }.map { it.id }

        return ReconciliationResult(
            scanId = snapshot.scanId,
            newClaims = newClaims,
            unavailableBookIds = unavailableIds,
            pendingActions = pendingActions,
            discoveredCount = newClaims.size
        )
    }
}
