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
import com.viel.aplayer.abs.sync.AbsSyncWorkScheduler
import com.viel.aplayer.application.download.ManualDownloadOrphanCleanupScheduler
import com.viel.aplayer.application.startup.APlayerStartupWarmup
import com.viel.aplayer.application.startup.DefaultStartupWarmupDependencies
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.di.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.di.dependencies.AppShellDependencies
import com.viel.aplayer.di.dependencies.DetailScreenDependencies
import com.viel.aplayer.di.dependencies.DownloadRuntimeDependencies
import com.viel.aplayer.di.dependencies.EditScreenDependencies
import com.viel.aplayer.di.dependencies.HomeScreenDependencies
import com.viel.aplayer.di.dependencies.LibrarySyncWorkerDependencies
import com.viel.aplayer.di.dependencies.ManualDownloadNotificationActionDependencies
import com.viel.aplayer.di.dependencies.PlaybackRecoveryDependencies
import com.viel.aplayer.di.dependencies.PlaybackRuntimeDependencies
import com.viel.aplayer.di.dependencies.PlayerScreenDependencies
import com.viel.aplayer.di.dependencies.RemoteConnectionDependencies
import com.viel.aplayer.di.dependencies.SearchScreenDependencies
import com.viel.aplayer.di.dependencies.SettingsScreenDependencies
import com.viel.aplayer.di.dependencies.VfsPlaybackDependencies
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.logger.AbsSyncLogger
import com.viel.aplayer.logger.CoverImageCoilEventListener
import com.viel.aplayer.logger.DownloadSyncLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

/**
 * Application class responsible for initializing the global dependency container.
 */
class APlayerApplication : Application(), ImageLoaderFactory {

    internal val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Provide backward compatibility and safe container retrieval.
     * Keeps the container property interface to support seamless compatibility with legacy code.
     * Double-Check Locking. lazy initialization in the companion object,
     * this ensures safe multi-threaded retrieval even if invoked before Application.onCreate(),
     * preventing UninitializedPropertyAccessException.
     */
    val container: AppContainer
        get() = getContainer(this)

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

        val processContainer = getProcessContainer(this)

        appScope.launch {
            supervisorScope {
                val settingsWarmup = async {
                    processContainer.settingsReadModel
                }
                val downloadRecovery = async {
                    runCatching {
                        processContainer.downloadRecoveryService.recoverIfNeeded()
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
                    createStartupWarmup(processContainer).run()
                }
                settingsWarmup.await()
                downloadRecovery.await()
                orphanCleanup.await()
                startupWarmup.await()
            }
        }
    }

    /**
     * Build the application-level startup coordinator from the process container.
     * Keeping construction here leaves the coordinator testable with small function seams while the Application owns Android-specific scheduler creation.
     */
    internal fun createStartupWarmup(processContainer: ProcessContainer): APlayerStartupWarmup {
        val startupDatabase = lazy { AppDatabase.getInstance(this) }
        val startupWarmupDependencies = DefaultStartupWarmupDependencies(
            libraryRootDaoProvider = { startupDatabase.value.libraryRootDao() },
            absCatalogStoreProvider = { startupDatabase.value.absCatalogDao() },
            coldStartSelfHealing = {
                processContainer.autoRewindManager.performColdStartSelfHealing()
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

    companion object {
        @Volatile
        private var instance: ProcessContainer? = null

        /**
         * Thread-safely resolve or instantiate the global AppContainer singleton.
         * Uses Double-Check Locking (DCL) to protect multi-threaded concurrent access.
         * If the application context cannot be cast to APlayerApplication (e.g. under test runners or background processes),
         * it falls back to instantiating DefaultAppContainer using the raw context, preventing ClassCastException.
         *
         * @param context Component Context
         * @return The global singleton AppContainer
         */
        @OptIn(UnstableApi::class)
        fun getContainer(context: Context): AppContainer {
            return getProcessContainer(context)
        }

        /**
         * Return the internal composition-root view.
         * Gives Application startup wiring access to di-owned implementations without expanding AppContainer's public contract.
         *
         * @param context Component Context
         * @return The global singleton ProcessContainer
         */
        @OptIn(UnstableApi::class)
        internal fun getProcessContainer(context: Context): ProcessContainer {
            return instance ?: synchronized(this) {
                instance ?: DefaultAppContainer(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Return the narrow media-core dependency view.
         * Prevents playback services from learning the wider application container surface.
         *
         * @param context Component Context
         * @return The media-core dependency view backed by the global container
         */
        fun getPlaybackRuntimeDependencies(context: Context): PlaybackRuntimeDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow cold-start recovery dependency view.
         * Lets AutoRewindManager repair progress records without receiving playback session or UI-facing dependencies.
         *
         * @param context Component Context
         * @return The progress recovery dependency view backed by the global container
         */
        fun getPlaybackRecoveryDependencies(context: Context): PlaybackRecoveryDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow data-source dependency view.
         * Keeps playback data-source factories limited to VFS reads and media-file lookup.
         *
         * @param context Component Context
         * @return The VFS playback dependency view backed by the global container
         */
        fun getVfsPlaybackDependencies(context: Context): VfsPlaybackDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow download-cache and queue dependency view.
         * Playback code can resolve cache access through this view without widening to the full application container.
         *
         * @param context Component Context
         * @return The download dependency view backed by the global container
         */
        fun getDownloadRuntimeDependencies(context: Context): DownloadRuntimeDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow notification command view.
         * Notification receivers need only the book-level download controller, not the full application container or settings surface.
         *
         * @param context Component Context
         * @return The notification-action dependency view backed by the global container
         */
        fun getManualDownloadNotificationActionDependencies(context: Context): ManualDownloadNotificationActionDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow local-library worker dependency view.
         * Lets WorkManager refresh jobs schedule scans without receiving screen, playback, or ABS surfaces.
         *
         * @param context Component Context
         * @return The local-library worker dependency view backed by the global container
         */
        fun getLibrarySyncWorkerDependencies(context: Context): LibrarySyncWorkerDependencies {
            return getContainer(context)
        }

        /**
         * Return the narrow AudiobookShelf worker dependency view.
         * Lets ABS background jobs mirror one root and publish feedback without receiving unrelated container entries.
         *
         * @param context Component Context
         * @return The ABS worker dependency view backed by the global container
         */
        fun getAbsSyncWorkerDependencies(context: Context): AbsSyncWorkerDependencies {
            return getContainer(context)
        }

        /**
         * Return the search-scene dependency view.
         * Gives SearchViewModel only search-specific read and command interfaces during the scene-module migration.
         *
         * @param context Component Context
         * @return The search-screen dependency view backed by the global container
         */
        fun getSearchScreenDependencies(context: Context): SearchScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the detail-scene dependency view.
         * Gives DetailViewModel only detail-specific read and command interfaces during the scene-module migration.
         *
         * @param context Component Context
         * @return The detail-screen dependency view backed by the global container
         */
        fun getDetailScreenDependencies(context: Context): DetailScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the home-screen dependency view.
         * Gives LibraryViewModel only home scene interfaces, settings, deletion use cases, and feedback sink it consumes.
         *
         * @param context Component Context
         * @return The home-screen dependency view backed by the global container
         */
        fun getHomeScreenDependencies(context: Context): HomeScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the settings-screen dependency view.
         * Gives SettingsViewModel settings-specific operations without exposing playback runtime or VFS entries.
         *
         * @param context Component Context
         * @return The settings-screen dependency view backed by the global container
         */
        fun getSettingsScreenDependencies(context: Context): SettingsScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the remote-connection scene dependency view.
         * Gives the app-level RemoteConnectionViewModel only the connection-test, ABS login, and root
         * registration seams it needs, without exposing the broader settings surface.
         *
         * @param context Component Context
         * @return The remote-connection dependency view backed by the global container
         */
        fun getRemoteConnectionDependencies(context: Context): RemoteConnectionDependencies {
            return getContainer(context)
        }

        /**
         * Return the player-screen dependency view.
         * Gives PlayerViewModel UI-facing playback startup and feedback dependencies while media core keeps its own runtime view.
         *
         * @param context Component Context
         * @return The player-screen dependency view backed by the global container
         */
        fun getPlayerScreenDependencies(context: Context): PlayerScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the edit-scene dependency view.
         * Gives EditBookViewModel only editable metadata reads and save commands during the scene-module migration.
         *
         * @param context Component Context
         * @return The edit-screen dependency view backed by the global container
         */
        fun getEditScreenDependencies(context: Context): EditScreenDependencies {
            return getContainer(context)
        }

        /**
         * Return the top-level Compose shell dependency view.
         * Lets APlayerApp collect settings and app-shell events without touching screen or playback entries.
         *
         * @param context Component Context
         * @return The app-shell dependency view backed by the global container
         */
        fun getAppShellDependencies(context: Context): AppShellDependencies {
            return getContainer(context)
        }

    }
}
