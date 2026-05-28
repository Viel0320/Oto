package com.viel.aplayer.data.gateway

/**
 * 领域解耦的网关接口：专门用于触发或安排本地/WebDAV 库的扫描同步工作。
 *
 * 核心设计目标：
 * 1. 拆分扫描任务触发：将后台定时重扫与前台即时扫描触发进行统一归口。
 * 2. 解耦 WorkManager 与前台逻辑：隔离底层并发和锁管理。
 */
interface ScanScheduler {

    /**
     * 在前台立即执行库同步和文件扫描重扫操作。
     * @param trigger 触发源，如 "USER", "SYSTEM", "BACKGROUND" 等
     */
    suspend fun syncLibrary(trigger: String = "USER")

    /**
     * 向后台协程异步并发管道中派发库重扫任务。
     * @param trigger 触发源
     */
    fun scheduleLibrarySync(trigger: String = "USER")
}
