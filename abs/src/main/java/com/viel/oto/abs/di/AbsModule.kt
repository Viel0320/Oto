package com.viel.oto.di

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import com.viel.oto.abs.availability.AbsLibraryAvailabilityGateway
import com.viel.oto.abs.auth.AbsCredentialStore
import com.viel.oto.abs.net.AbsApiClient
import com.viel.oto.abs.net.RealAbsApiClient
import com.viel.oto.abs.playback.AbsPlaybackCredentialResolver
import com.viel.oto.abs.root.AbsRootCredentialGatewayAdapter
import com.viel.oto.abs.sync.AbsConnectionTester
import com.viel.oto.abs.sync.AbsCoverCache
import com.viel.oto.abs.sync.AbsCoverStore
import com.viel.oto.abs.vfs.AbsSourceProvider
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.cover.RemoteCoverStore
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.library.availability.AbsAvailabilityGateway
import com.viel.oto.library.root.AbsRootCredentialGateway
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module

/**
 * AudiobookShelf credentials, API client, connection tester, cover cache, and credential resolver.
 * Replaces the credential/client section of AbsGraph. Each public ABS contract is registered
 * directly so optimized builds do not need redirect-only Koin definitions.
 */
@OptIn(UnstableApi::class)
object AbsModule {
    private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

    val module: Module = module {
        single { AbsCredentialStore(get<Context>().applicationContext.absCredentialDataStore) }
        single<AbsRootCredentialGateway> { AbsRootCredentialGatewayAdapter(get()) }

        single<AbsApiClient> {
            RealAbsApiClient(
                appSettingsRepository = get<AppSettingsRepository>(),
                credentialStore = get()
            )
        }

        single { AbsConnectionTester(get()) }
        single {
            AbsSourceProvider(
                context = get<Context>().applicationContext,
                credentialStore = get<AbsCredentialStore>(),
                settingsProvider = { get<AppSettingsRepository>().cachedSettings }
            )
        }
        single<AbsAvailabilityGateway> {
            AbsLibraryAvailabilityGateway(
                credentialStore = get(),
                connectionTester = get(),
                sourceProvider = get(),
                settingsProvider = { get<AppSettingsRepository>().cachedSettings }
            )
        }

        single {
            AbsCoverCache(
                context = get(),
                credentialStore = get(),
                settingsProvider = { get<AppSettingsRepository>().cachedSettings }
            )
        } binds arrayOf(AbsCoverStore::class, RemoteCoverStore::class)

        single {
            AbsPlaybackCredentialResolver(
                libraryRootDao = get<AppDatabase>().libraryRootDao(),
                credentialStore = get()
            )
        }
    }
}
