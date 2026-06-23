package com.viel.aplayer.di

import android.content.Context
import androidx.media3.common.util.UnstableApi
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.core.logger.PrintLogger

/**
 * Entry point for the Koin GlobalContext.
 *
 * Starts Koin with androidContext and every APlayer module, and exposes an explicit shutdown
 * hook that preserves the previous graph close order before stopping Koin.
 */
@UnstableApi
internal object APlayerKoinApplication {

    /**
     * Initialize the global Koin context with all APlayer modules.
     * Idempotent: a no-op when the GlobalContext is already running.
     */
    fun start(context: Context) {
        if (GlobalContext.getOrNull() != null) {
            return
        }
        startKoin {
            androidContext(context.applicationContext)
            logger(PrintLogger(Level.ERROR))
            modules(
                CoreDataModule.module,
                CoreSettingsModule.module,
                UiEventModule.module,
                MediaModule.module,
                MediaPlaybackControllerModule.module,
                DownloadModule.module,
                DownloadReadModelModule.module,
                LibraryBookGatewayModule.module,
                LibraryCoverModule.module,
                LibraryScanModule.module,
                LibraryUseCaseModule.module,
                LibrarySceneModule.module,
                AbsModule.module,
                AbsSyncModule.module,
                SettingsUseCaseModule.module,
                ViewModelModule.module
            )
        }
    }

    /**
     * Close di-owned resources in lifecycle order, then stop the Koin GlobalContext.
     */
    fun stop() {
        GraphClosePolicy.closeInLifecycleOrder()
        stopKoin()
    }
}
