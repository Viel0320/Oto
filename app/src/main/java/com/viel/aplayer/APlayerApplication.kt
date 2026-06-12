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
import com.viel.aplayer.application.startup.APlayerStartupWarmup
import com.viel.aplayer.application.startup.DefaultStartupWarmupDependencies
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.dependencies.AbsSyncWorkerDependencies
import com.viel.aplayer.dependencies.AppShellDependencies
import com.viel.aplayer.dependencies.DetailScreenDependencies
import com.viel.aplayer.dependencies.EditScreenDependencies
import com.viel.aplayer.dependencies.HomeScreenDependencies
import com.viel.aplayer.dependencies.LibrarySyncWorkerDependencies
import com.viel.aplayer.dependencies.PlaybackRecoveryDependencies
import com.viel.aplayer.dependencies.PlaybackRuntimeDependencies
import com.viel.aplayer.dependencies.PlayerScreenDependencies
import com.viel.aplayer.dependencies.SearchScreenDependencies
import com.viel.aplayer.dependencies.SettingsScreenDependencies
import com.viel.aplayer.dependencies.VfsPlaybackDependencies
import com.viel.aplayer.i18n.AppLocaleController
import com.viel.aplayer.logger.AbsSyncLogger
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
        // Runtime Locale Config Sync (Keep Android Settings aware of APlayer's supported app languages)
        // This complements manifest localeConfig and covers Android 16/OEM package-state caches that may not refresh after incremental installs.
        AppLocaleController.ensurePlatformLocaleConfig(this)

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

        // Early Process Container Initialization (Trigger composition-root instantiation on the main thread during onCreate)
        // The process-only view preserves startup access to graph-owned wiring while keeping the public AppContainer surface narrow.
        val processContainer = getProcessContainer(this)
        
        // Async Warmup (Warm up database/settings components on background thread during application startup)
        // Dispatches the pre-caching of preferences and progress recovery self-healing to a dedicated background thread.
        appScope.launch {
            processContainer.settingsRepository
            // Startup Warmup Coordinator (Gate remote ABS work before local progress recovery)
            // Cold start now enqueues stale ABS roots through root-scoped WorkManager instead of performing authorize progress refreshes inline on every process creation.
            createStartupWarmup(processContainer).run()
        }
    }

    /**
     * Startup Warmup Wiring (Build the application-level startup coordinator from the process container)
     * Keeping construction here leaves the coordinator testable with small function seams while the Application owns Android-specific scheduler creation.
     */
    internal fun createStartupWarmup(processContainer: ProcessContainer): APlayerStartupWarmup {
        // Startup Warmup Database Provider (Resolve only Room when the freshness gate actually reads persisted root state)
        // This keeps warmup construction away from LibraryGraph and AbsGraph properties that would otherwise allocate scanner, VFS, cover, and remote sync adapters.
        val startupDatabase = lazy { AppDatabase.getInstance(this) }
        val startupWarmupDependencies = DefaultStartupWarmupDependencies(
            libraryRootDaoProvider = { startupDatabase.value.libraryRootDao() },
            absCatalogStoreProvider = { startupDatabase.value.absCatalogDao() },
            coldStartSelfHealing = {
                processContainer.autoRewindManager.performColdStartSelfHealing()
            }
        )

        // Lazy ABS Work Scheduler (Avoid initializing WorkManager when every ABS root is still fresh)
        // The startup coordinator resolves this scheduler only after the freshness gate selects at least one stale remote root.
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
        private var instance: ProcessContainer? = null

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
            // Public Container Provider (Return only the dependency-view union)
            // Hiding the process-only subtype prevents public callers from resolving graph-owned implementation properties.
            return getProcessContainer(context)
        }

        /**
         * Process Container Provider (Return the internal composition-root view)
         * Gives Application startup wiring access to graph-owned implementations without expanding AppContainer's public contract.
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
         * Playback Runtime Dependencies Provider (Return the narrow media-core dependency view)
         * Prevents playback services from learning the wider application container surface.
         *
         * @param context Component Context
         * @return The media-core dependency view backed by the global container
         */
        fun getPlaybackRuntimeDependencies(context: Context): PlaybackRuntimeDependencies {
            return getContainer(context)
        }

        /**
         * Playback Recovery Dependencies Provider (Return the narrow cold-start recovery dependency view)
         * Lets AutoRewindManager repair progress records without receiving playback session or UI-facing dependencies.
         *
         * @param context Component Context
         * @return The progress recovery dependency view backed by the global container
         */
        fun getPlaybackRecoveryDependencies(context: Context): PlaybackRecoveryDependencies {
            return getContainer(context)
        }

        /**
         * VFS Playback Dependencies Provider (Return the narrow data-source dependency view)
         * Keeps playback data-source factories limited to VFS reads and media-file lookup.
         *
         * @param context Component Context
         * @return The VFS playback dependency view backed by the global container
         */
        fun getVfsPlaybackDependencies(context: Context): VfsPlaybackDependencies {
            return getContainer(context)
        }

        /**
         * Library Sync Worker Dependencies Provider (Return the narrow local-library worker dependency view)
         * Lets WorkManager refresh jobs schedule scans without receiving screen, playback, or ABS surfaces.
         *
         * @param context Component Context
         * @return The local-library worker dependency view backed by the global container
         */
        fun getLibrarySyncWorkerDependencies(context: Context): LibrarySyncWorkerDependencies {
            return getContainer(context)
        }

        /**
         * ABS Sync Worker Dependencies Provider (Return the narrow Audiobookshelf worker dependency view)
         * Lets ABS background jobs mirror one root and publish feedback without receiving unrelated container entries.
         *
         * @param context Component Context
         * @return The ABS worker dependency view backed by the global container
         */
        fun getAbsSyncWorkerDependencies(context: Context): AbsSyncWorkerDependencies {
            return getContainer(context)
        }

        /**
         * Search Screen Dependencies Provider (Return the search-scene dependency view)
         * Gives SearchViewModel only search-specific read and command interfaces during the scene-module migration.
         *
         * @param context Component Context
         * @return The search-screen dependency view backed by the global container
         */
        fun getSearchScreenDependencies(context: Context): SearchScreenDependencies {
            return getContainer(context)
        }

        /**
         * Detail Screen Dependencies Provider (Return the detail-scene dependency view)
         * Gives DetailViewModel only detail-specific read and command interfaces during the scene-module migration.
         *
         * @param context Component Context
         * @return The detail-screen dependency view backed by the global container
         */
        fun getDetailScreenDependencies(context: Context): DetailScreenDependencies {
            return getContainer(context)
        }

        /**
         * Home Screen Dependencies Provider (Return the home-screen dependency view)
         * Gives LibraryViewModel only home scene interfaces, settings, deletion use cases, and feedback sink it consumes.
         *
         * @param context Component Context
         * @return The home-screen dependency view backed by the global container
         */
        fun getHomeScreenDependencies(context: Context): HomeScreenDependencies {
            return getContainer(context)
        }

        /**
         * Settings Screen Dependencies Provider (Return the settings-screen dependency view)
         * Gives SettingsViewModel settings-specific operations without exposing playback runtime or VFS entries.
         *
         * @param context Component Context
         * @return The settings-screen dependency view backed by the global container
         */
        fun getSettingsScreenDependencies(context: Context): SettingsScreenDependencies {
            return getContainer(context)
        }

        /**
         * Player Screen Dependencies Provider (Return the player-screen dependency view)
         * Gives PlayerViewModel UI-facing playback startup and feedback dependencies while media core keeps its own runtime view.
         *
         * @param context Component Context
         * @return The player-screen dependency view backed by the global container
         */
        fun getPlayerScreenDependencies(context: Context): PlayerScreenDependencies {
            return getContainer(context)
        }

        /**
         * Edit Screen Dependencies Provider (Return the edit-scene dependency view)
         * Gives EditBookViewModel only editable metadata reads and save commands during the scene-module migration.
         *
         * @param context Component Context
         * @return The edit-screen dependency view backed by the global container
         */
        fun getEditScreenDependencies(context: Context): EditScreenDependencies {
            return getContainer(context)
        }

        /**
         * App Shell Dependencies Provider (Return the top-level Compose shell dependency view)
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
