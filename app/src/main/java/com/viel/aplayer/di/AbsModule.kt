package com.viel.aplayer.di

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.net.AbsApiClient
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.abs.sync.AbsCoverCache
import com.viel.aplayer.abs.sync.AbsCoverStore
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * AudiobookShelf credentials, API client, connection tester, cover cache, and credential resolver.
 * Replaces the credential/client section of AbsGraph. Each public ABS contract is registered
 * directly so optimized builds do not need redirect-only Koin definitions.
 */
@UnstableApi
internal object AbsModule {

    val module: Module = module {
        single<AbsApiClient> {
            RealAbsApiClient(
                appSettingsRepository = get<AppSettingsRepository>(),
                credentialStore = get()
            )
        }

        single { AbsConnectionTester(get()) }

        single<AbsCoverStore> {
            AbsCoverCache(
                context = get(),
                credentialStore = get(),
                settingsProvider = { get<AppSettingsRepository>().cachedSettings }
            )
        }

        single {
            AbsPlaybackCredentialResolver(
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                credentialStore = get()
            )
        }
    }
}
