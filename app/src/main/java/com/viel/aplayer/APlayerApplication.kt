package com.viel.aplayer

import android.app.Application

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
    }
}