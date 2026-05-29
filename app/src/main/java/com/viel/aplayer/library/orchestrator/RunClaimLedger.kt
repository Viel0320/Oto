package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.data.entity.BookFileEntity
import com.viel.aplayer.library.FileIdentity

// In-memory first-claim-wins ledger for one import run.
class RunClaimLedger(
    // 允许 RescanCoordinator 传入同一轮扫描共享的 owner map，从而跨 scope 保留 claim 预留状态。
    private val ownerByKey: MutableMap<String, ImportSourceRef> = mutableMapOf()
) {

    /**
         * 为某个导入源分配并预留物理文件。
     * 增加 currentParentSourcePath 参数，在向 existingClaimIndex 校验物理文件已被数据库中已有书籍抢占时，
     * 能够将检测局域化在该同级 VFS 目录内。
     * 
     * @param source 本次认领的来源
     * @param files 待认领的物理文件标识列表
     * @param existingClaimIndex 全局/已入库所有权索引
     * @param currentParentSourcePath 当前正在被认领的文件所属的 VFS 父目录路径
     * @return 认领预留结果
     */
    fun reserve(
        source: ImportSourceRef,
        files: List<FileIdentity>,
        existingClaimIndex: ExistingClaimIndex,
        currentParentSourcePath: String? = null
    ): ReservationResult {
        val existingHits = mutableListOf<BookFileEntity>()
        val runHits = mutableListOf<ImportSourceRef>()

        files.forEach { identity ->
            // 将 VFS 父目录路径透传给 existingClaimIndex.find，去掉旧父目录 URI 兼容分支。
            existingClaimIndex.find(identity, currentParentSourcePath)?.let { existingHits.add(it) }
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

    // 为单个 scope 创建隔离副本，scope 内的 claim 只有在入库成功后才通过 commitFrom 合并回全局扫描账本。
    fun fork(): RunClaimLedger = RunClaimLedger(ownerByKey.toMutableMap())

    // scope 入库成功后提交其新增 claim 预留，避免解析失败或入库失败的 scope 污染后续 claim 判断。
    fun commitFrom(scopeLedger: RunClaimLedger) {
        scopeLedger.ownerByKey.forEach { (key, owner) ->
            ownerByKey.putIfAbsent(key, owner)
        }
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
