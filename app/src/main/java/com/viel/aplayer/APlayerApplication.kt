package com.viel.aplayer

import android.app.Application
import com.viel.aplayer.media.AutoRewindManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自定义 Application 类，负责全局依赖容器初始化。
 */
class APlayerApplication : Application() {
    
    /** 全局依赖容器实例 */
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        // 在应用启动时初始化容器
        container = DefaultAppContainer(this)

        // 在主进程冷启动时，在后台 IO 协程中执行自动回退进度自愈，避免阻塞启动主线程
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            AutoRewindManager.getInstance(this@APlayerApplication).performColdStartSelfHealing()
        }
    }
}