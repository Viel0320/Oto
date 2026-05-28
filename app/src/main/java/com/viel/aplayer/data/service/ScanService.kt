package com.viel.aplayer.data.service

import com.viel.aplayer.data.BookLibraryRepository
import com.viel.aplayer.data.gateway.ScanScheduler

/**
 * 媒体库扫描调度应用服务（实现了 ScanScheduler 接口）。
 *
 * 核心设计目标：
 * 1. 扫描逻辑防腐：隔离后台 WorkManager 或前台主动调用的重扫请求，底层暂时委托给 [BookLibraryRepository]。
 * 2. 避免大上帝类：将频繁的后台定时扫描调度与普通的只读查询逻辑隔离。
 */
class ScanService(
    private val bookLibraryRepository: BookLibraryRepository
) : ScanScheduler {

    override suspend fun syncLibrary(trigger: String) {
        bookLibraryRepository.syncLibrary(trigger)
    }

    override fun scheduleLibrarySync(trigger: String) {
        bookLibraryRepository.scheduleLibrarySync(trigger)
    }
}
