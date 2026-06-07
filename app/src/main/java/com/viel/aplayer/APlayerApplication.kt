package com.viel.aplayer

import android.app.Application
import android.content.Context
import android.os.StrictMode
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
 * Application class responsible for initializing the global dependency container.
 */
class APlayerApplication : Application(), ImageLoaderFactory {

    // Application Coroutine Scope (Provides a centralized coroutine lifecycle scope managed by the application process)
    // App Scope Visibility: Expose appScope internally to allow background tasks (like widget cleanups during service destruction) to launch on a persistent scope.
    internal val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /** 
     * Dependency Container Interface (Provide backward compatibility and safe container retrieval)
     * Keeps the container property interface to support seamless compatibility with legacy code.
     * By using a read-only property backed by DCL (Double-Check Locking) lazy initialization in the companion object,
     * this ensures safe multi-threaded retrieval even if invoked before Application.onCreate(),
     * preventing UninitializedPropertyAccessException.
     */
    val container: AppContainer
        get() = getContainer(this)

    override fun onCreate() {
        super.onCreate()
        // StrictMode Setup: Enable VM Policy checking to detect closeable leaks on debug builds without depending on BuildConfig class.
        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        // Early Container Initialization (Trigger AppContainer instantiation on the main thread during onCreate)
        // This ensures all dependency components are fully initialized before any activities or background services connect.
        val appContainer = getContainer(this)
        
        // Async Warmup (Warm up database/settings components on background thread during application startup)
        // Dispatches the pre-caching of preferences and progress recovery self-healing to a dedicated background thread.
        appScope.launch {
            appContainer.settingsRepository
            appContainer.autoRewindManager.performColdStartSelfHealing()
        }
    }

    override fun newImageLoader(): ImageLoader {
        // Shared ImageLoader Strategy (Provide a single ImageLoader instance to unify Coil cache keys)
        // Consolidating memory and disk caches under one loader avoids redundant fetching and key mismatches across screens.
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
                // Unified Cache Logging Event Bridge (Attach CoverImageCoilEventListener to the global ImageLoader)
                // Unifies analytics and tracking for image loadings (success, failure, cache hits) regardless of request sources.
                CoverImageCoilEventListener.Factory()
            )
            .build()
    }

    companion object {
        @Volatile
        private var instance: AppContainer? = null

        /**
         * Thread-Safe Container Factory (Thread-safely resolve or instantiate the global AppContainer singleton)
         * Uses Double-Check Locking (DCL) to protect multi-threaded concurrent access.
         * If the application context cannot be cast to APlayerApplication (e.g. under test runners or background processes),
         * it falls back to instantiating DefaultAppContainer using the raw context, preventing ClassCastException.
         * 
         * @param context Component Context
         * @return The global singleton AppContainer
         */
        @OptIn(UnstableApi::class)
        fun getContainer(context: Context): AppContainer {
            return instance ?: synchronized(this) {
                instance ?: DefaultAppContainer(context.applicationContext).also { instance = it }
            }
        }
    }
}
