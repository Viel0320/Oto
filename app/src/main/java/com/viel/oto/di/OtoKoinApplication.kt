package com.viel.oto.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.ui.di.SettingsUiModule
import com.viel.oto.ui.di.ViewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.core.logger.PrintLogger

/**
 * Entry point for the Koin GlobalContext.
 *
 * Starts Koin with androidContext and every Oto module, and exposes an explicit shutdown
 * hook that preserves the previous graph close order before stopping Koin.
 */
@OptIn(UnstableApi::class)
internal object OtoKoinApplication {

    /**
     * Initialize the global Koin context with all Oto modules.
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
                AppDownloadResourceModule.module,
                LibraryVfsModule.module,
                MediaMetadataModule.module,
                MediaSubtitleModule.module,
                MediaServiceModule.module,
                MediaDownloadModule.module,
                MediaPlaybackRuntimeModule.module,
                MediaPlaybackControllerModule.module,
                ApplicationDownloadModule.module,
                DownloadReadModelModule.module,
                LibraryBookGatewayModule.module,
                LibraryCoverModule.module,
                LibraryAvailabilityModule.module,
                LibraryScanModule.module,
                LibraryUseCaseModule.module,
                LibrarySceneModule.module,
                AbsModule.module,
                AbsSyncModule.module,
                SettingsUseCaseModule.module,
                SettingsUiModule.module,
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
