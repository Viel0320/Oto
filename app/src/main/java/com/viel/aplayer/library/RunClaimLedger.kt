package com.viel.aplayer.library

import com.viel.aplayer.data.entity.BookFileEntity

// In-memory first-claim-wins ledger for one import run.
class RunClaimLedger {
    private val ownerByKey = mutableMapOf<String, ImportSourceRef>()

    /**
     * 为每一次改动添加详尽的中文注释：
     * 为某个导入源分配并预留物理文件。
     * 增加 currentParentUri 参数，在向 existingClaimIndex 校验物理文件已被数据库中已有书籍抢占时，
     * 能够将检测局域化在该同级物理目录内。
     * 
     * @param source 本次认领的来源
     * @param files 待认领的物理文件标识列表
     * @param existingClaimIndex 全局/已入库所有权索引
     * @param currentParentUri 当前正在被认领的文件所属的物理父目录 Uri
     * @return 认领预留结果
     */
    fun reserve(
        source: ImportSourceRef,
        files: List<FileIdentity>,
        existingClaimIndex: ExistingClaimIndex,
        currentParentUri: String? = null
    ): ReservationResult {
        val existingHits = mutableListOf<BookFileEntity>()
        val runHits = mutableListOf<ImportSourceRef>()

        files.forEach { identity ->
            // 详尽的中文注释：将 currentParentUri 局域化物理父目录 Uri 参数透传给 existingClaimIndex.find
            existingClaimIndex.find(identity, currentParentUri)?.let { existingHits.add(it) }
            identity.keys().forEach { key ->
                ownerByKey[key]?.let { runHits.add(it) }
            }
        }

        val canReserve = existingHits.isEmpty() && runHits.isEmpty()
        if (canReserve) {
            files.forEach { identity ->
                identity.keys().forEach { key -> ownerByKey.putIfAbsent(key, source) }
            }
        }

        return ReservationResult(
            reserved = canReserve,
            existingConflicts = existingHits.distinctBy { it.id },
            runConflicts = runHits.distinct()
        )
    }
}

data class ImportSourceRef(
    val sourceType: String,
    val sourceUri: String,
    val displayName: String
)

data class ReservationResult(
    val reserved: Boolean,
    val existingConflicts: List<BookFileEntity>,
    val runConflicts: List<ImportSourceRef>
)