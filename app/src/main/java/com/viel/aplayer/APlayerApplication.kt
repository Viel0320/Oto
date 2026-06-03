package com.viel.aplayer

import android.app.Application
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.viel.aplayer.logger.CoverImageCoilEventListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自定义 Application 类，负责全局依赖容器初始化。
 */
class APlayerApplication : Application(), ImageLoaderFactory {
    
    /** 
     * 全局依赖容器实例计算属性。
     * 详尽的中文注释：保留 container 属性接口以支持应用内大量旧代码的无缝向前兼容。
     * 通过将 lateinit var 更改为只读计算属性并在底层代理至伴生对象中的 DCL (Double-Check Locking) 惰性初始化方法，
     * 能够在所有组件强转访问 container 时，即使发生早于 onCreate 的极早期调用或高并发获取，
     * 也能确保返回线程安全的 AppContainer 实例，从根源上扑灭 UninitializedPropertyAccessException 启动期崩溃。
     */
    val container: AppContainer
        get() = getContainer(this)

    override fun onCreate() {
        super.onCreate()
        // 详尽的中文注释：在 Application.onCreate 周期中预先触发容器的主线程初始化，
        // 保证在后续界面或其它组件开始连接前，容器及其各个依赖项都已处于就绪状态
        getContainer(this)

        // 详尽的中文注释：在应用启动并完成依赖容器装挂后，在后台 IO 协程作用域内安全通过容器的 autoRewindManager 属性调度执行进度 cold start 自愈逻辑；
        // 这规避了以前在全局生命周期中越过 DI 容器强行直调外部静态单例的混乱设计，保证整个系统的一致性与可测试性
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            container.autoRewindManager.performColdStartSelfHealing()
        }
    }

    override fun newImageLoader(): ImageLoader {
        // 详尽注释：全应用只提供一个 Coil ImageLoader，让封面请求工厂生成的统一 key 能进入同一个
        // memory/disk cache 池；如果各页面隐式创建不同 ImageLoader，即使 key 一致也无法稳定复用缓存。
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .eventListenerFactory(
                // 详尽注释：把封面专用的 Coil 事件桥接统一挂到全局 ImageLoader 上，
                // 这样无论封面请求来自首页、详情页、播放器还是迷你播放器，只要共用这一个 loader，
                // 就能在同一缓存池口径下持续记录成功、失败、取消以及 dataSource 命中事实。
                CoverImageCoilEventListener.Factory()
            )
            .build()
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /**
         * 详尽的中文注释：线程安全地惰性获取全局依赖容器 AppContainer 单例。
         * 使用双重锁校验 (Double-Check Locking) 保证多线程并发安全。
         * 如果当前 ApplicationContext 并非 APlayerApplication（例如某些测试沙箱、辅助进程等），
         * 则直接依托其 applicationContext 上下文初始化并缓存 DefaultAppContainer 单例，
         * 从而有效避免 ClassCastException 和 lateinit 未初始化等启动级隐患。
         * 
         * @param context 组件的 Context
         * @return 全局唯一的 AppContainer 实例
         */
        @OptIn(UnstableApi::class)
        fun getContainer(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: DefaultAppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}
