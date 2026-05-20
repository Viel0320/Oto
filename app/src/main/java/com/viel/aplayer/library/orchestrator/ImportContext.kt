package com.viel.aplayer.library.orchestrator

import com.viel.aplayer.library.ExistingClaimIndex
import com.viel.aplayer.library.FileIdentity
import com.viel.aplayer.library.FileInventory
import com.viel.aplayer.library.RunClaimLedger

/**
 * 导入同步上下文
 * 
 * 为每一次改动添加详尽的中文注释：
 * 本类声明为 internal 限制其在本模块内可见，妥善解决类型曝光错误。
 * 本类承载扫描生命周期内的全局只读变量和内存缓存，避免多级步骤间的繁琐参数传递。
 */
internal data class ImportContext(
    // 唯一的扫描任务会话 ID，每次扫描由协调器分配，用于日志追踪与冲突动作归档
    val scanId: String,
    
    // 内存中的“首次认领者胜出”冲突内存账本，防范同批次扫描内多个书籍草稿争夺同一个物理音轨
    val runClaimLedger: RunClaimLedger = RunClaimLedger(),
    
    // 数据库中目前已经存在的书籍和音轨物理归属认领索引
    val existingClaimIndex: ExistingClaimIndex,
    
    // 物理文件存量盘点结果的缓存（共享给后续工位，防重复扫描）
    var sharedInventory: FileInventory? = null,
    
    // 已被清单文件（CUE/M3U8）预占用的音频物理标识，防止它们参与启发式智能聚类
    val reservedAudioIdentities: MutableSet<FileIdentity> = mutableSetOf()
)