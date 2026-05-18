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
        val partialBookIds: List<String>,
        val updatedClaims: List<Pair<ClaimSource, String>>, // Claim 与 对应的原有 BookID
        val recoveredBookIds: List<String>,
        val pendingActions: List<PendingScanActionEntity>,
        val discoveredCount: Int
    )

    /**
     * 计算差异：对比快照和数据库。
     */
    suspend fun reconcile(snapshot: LibrarySnapshot): ReconciliationResult {
        val allBooks = bookDao.getAllBooksOnce()
        val existingSourceUrisMap = allBooks.associateBy { it.sourceUri }

        // 获取所有书籍的文件数量索引，用于快速对比
        val bookFileCountMap = bookDao.getBookFileCounts().associate { it.bookId to it.count }
        
        // 建立 物理文件 URI -> 书籍 ID 的反向索引，用于检测竞争冲突
        val fileToBookMap = bookDao.getFileUriToBookIdMap().associate { it.uri to it.bookId }
        
        val pendingActions = mutableListOf<PendingScanActionEntity>()
        val newClaims = mutableListOf<ClaimSource>()
        val partialBookIds = mutableListOf<String>()
        val updatedClaims = mutableListOf<Pair<ClaimSource, String>>()
        val recoveredBookIds = mutableListOf<String>()

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
                    updatedClaims.add(claim to existingBook.id)
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

                // 3. 深度检查：对比数据库记录的文件数量与物理发现的数量
                val dbFileCount = bookFileCountMap[existingBook.id] ?: 0
                val scannedFileCount = claim.referencedFileUris.size
                
                // 综合判定逻辑
                // 只有当物理找回的文件数 < 数据库记录的文件数（且非单文件）时，才判定为丢失。
                // 此时即便 claim.missingFileCount > 0（清单里有冗余错误行），只要已导入的文件全找回来了，就不报丢失。
                val isActuallyPartlyLost = (dbFileCount > scannedFileCount && dbFileCount > 1) || 
                                          (claim.missingFileCount > 0 && scannedFileCount < dbFileCount)

                // 统一状态恢复/更新逻辑
                when {
                    isActuallyPartlyLost -> {
                        android.util.Log.w("LibraryReconciler", "⚠️ Partly lost detected for: ${existingBook.title} (DB: $dbFileCount, Scanned: $scannedFileCount, Missing: ${claim.missingFileCount})")
                        partialBookIds.add(existingBook.id)
                    }
                    existingBook.status != "READY" -> {
                        // 如果之前是 UNAVAILABLE 或 PARTIAL，但现在文件全找回来了，恢复为 READY
                        // 将其加入 updatedClaims 以便重新导入补全文件列表
                        android.util.Log.i("LibraryReconciler", "✅ Book fully recovered: ${existingBook.title}")
                        recoveredBookIds.add(existingBook.id)
                        updatedClaims.add(claim to existingBook.id)
                        bookDao.updateBookStatus(existingBook.id, "READY")
                    }
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
            partialBookIds = partialBookIds,
            updatedClaims = updatedClaims,
            recoveredBookIds = recoveredBookIds,
            pendingActions = pendingActions,
            discoveredCount = newClaims.size
        )
    }
}
