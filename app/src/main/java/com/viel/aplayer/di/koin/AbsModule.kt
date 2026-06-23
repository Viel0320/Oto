package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.net.RealAbsApiClient
import com.viel.aplayer.abs.playback.AbsPlaybackCredentialResolver
import com.viel.aplayer.abs.sync.AbsConnectionTester
import com.viel.aplayer.abs.sync.AbsCoverCache
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * AudiobookShelf credentials, API client, connection tester, cover cache, and credential resolver.
 * Replaces the credential/client section of AbsGraph.
 */
@UnstableApi
internal object AbsModule {

    val module: Module = module {
        single {
            RealAbsApiClient(
                appSettingsRepository = get<AppSettingsRepository>(),
                credentialStore = get()
            )
        }

        single<com.viel.aplayer.abs.net.AbsApiClient> { get<RealAbsApiClient>() }

        single { AbsConnectionTester(get()) }

        single {
            AbsCoverCache(
                context = get(),
                credentialStore = get(),
                settingsProvider = { get<AppSettingsRepository>().cachedSettings }
            )
        }

        single<com.viel.aplayer.abs.sync.AbsCoverStore> { get<AbsCoverCache>() as com.viel.aplayer.abs.sync.AbsCoverStore }

        single {
            AbsPlaybackCredentialResolver(
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                credentialStore = get()
            )
        }
    }
}
