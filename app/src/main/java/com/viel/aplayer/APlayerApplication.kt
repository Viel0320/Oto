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

        // 详尽的中文注释：在应用启动并完成依赖容器装挂后，在后台 IO 协程作用域内安全通过容器的 autoRewindManager 属性调度执行进度冷启动自愈逻辑；
        // 这规避了以前在全局生命周期中越过 DI 容器强行直调外部静态单例的混乱设计，保证整个系统的一致性与可测试性
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            container.autoRewindManager.performColdStartSelfHealing()
        }
    }
}