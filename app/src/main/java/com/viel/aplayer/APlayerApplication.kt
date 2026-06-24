package com.viel.aplayer

import android.app.Application
import android.os.StrictMode
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.viel.aplayer.abs.sync.AbsSyncWorkScheduler
import com.viel.aplayer.application.download.DownloadRecoveryService
import com.viel.aplayer.application.download.ManualDownloadOrphanCleanupScheduler
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.application.startup.APlayerStartupWarmup
import com.viel.aplayer.application.startup.DefaultStartupWarmupDependencies
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.di.APlayerKoinApplication
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.logger.AbsSyncLogger
import com.viel.aplayer.logger.CoverImageCoilEventListener
import com.viel.aplayer.logger.DownloadSyncLogger
import com.viel.aplayer.logger.StartupLogger
import com.viel.aplayer.media.AutoRewindManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * Application class responsible for initializing the Koin dependency container.
 */
@OptIn(UnstableApi::class)
class APlayerApplication : Application(), ImageLoaderFactory, KoinComponent {

    internal val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        AppLocaleController.ensurePlatformLocaleConfig(this)

        val isDebuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .penaltyLog()
                    .build()
            )
        }

        APlayerKoinApplication.start(this)

        appScope.launch {
            supervisorScope {
                val settingsWarmup = async {
                    runCatching {
                        get<AppSettingsReadModel>()
                    }.onFailure { error ->
                        StartupLogger.logWarmupFailure("settings", error::class.java.simpleName, error.message)
                    }
                }
                val downloadRecovery = async {
                    runCatching {
                        get<DownloadRecoveryService>().recoverIfNeeded()
                    }.onFailure { error ->
                        DownloadSyncLogger.logRecoveryFailure(error::class.java.simpleName, error.message)
                    }
                }
                val orphanCleanup = async {
                    runCatching {
                        ManualDownloadOrphanCleanupScheduler(this@APlayerApplication).enqueue()
                    }.onFailure { error ->
                        DownloadSyncLogger.logOrphanCleanupFailure(error::class.java.simpleName, error.message)
                    }
                }
                val startupWarmup = async {
                    runCatching { createStartupWarmup().run() }
                        .onFailure { error ->
                            StartupLogger.logWarmupFailure("startup", error::class.java.simpleName, error.message)
                        }
                }
                settingsWarmup.await()
                downloadRecovery.await()
                orphanCleanup.await()
                startupWarmup.await()
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        APlayerKoinApplication.stop()
    }

    /**
     * Build the application-level startup coordinator from the Koin container.
     * Keeping construction here leaves the coordinator testable with small function seams while the Application owns Android-specific scheduler creation.
     */
    internal fun createStartupWarmup(): APlayerStartupWarmup {
        val startupDatabase = lazy { get<AppDatabase>() }
        val startupWarmupDependencies = DefaultStartupWarmupDependencies(
            libraryRootDaoProvider = { startupDatabase.value.libraryRootDao() },
            absCatalogStoreProvider = { startupDatabase.value.absCatalogDao() },
            coldStartSelfHealing = {
                get<AutoRewindManager>().performColdStartSelfHealing()
            }
        )

        val absSyncWorkScheduler = lazy { AbsSyncWorkScheduler(this) }
        return APlayerStartupWarmup(
            dependencies = startupWarmupDependencies,
            enqueueAbsRootSync = { rootId -> absSyncWorkScheduler.value.enqueue(rootId) },
            onAbsProgressWarmupFailure = { error ->
                AbsSyncLogger.logAuthorizedProgressSyncFailure(error::class.java.simpleName, error.message)
            }
        )
    }

    override fun newImageLoader(): ImageLoader {
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
                CoverImageCoilEventListener.Factory()
            )
            .build()
    }
}
