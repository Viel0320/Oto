package com.viel.aplayer.di.koin

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.store.SearchHistoryStore
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Process-wide durable data stores: Room database, settings repository, ABS credential store,
 * and search history store.
 *
 * Each DataStore is registered with a named qualifier because they all share the
 * [DataStore]<[Preferences]> type but differ by preferencesDataStore name.
 */
@UnstableApi
internal object CoreDataModule {

    private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
    private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")
    private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

    val module: Module = module {
        single(named("appSettings")) { get<android.content.Context>().applicationContext.appSettingsDataStore }
        single(named("searchHistory")) { get<android.content.Context>().applicationContext.searchHistoryDataStore }
        single(named("absCredentials")) { get<android.content.Context>().applicationContext.absCredentialDataStore }

        single {
            AppDatabase.create(get()).also { db ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Data,
                    closeable = java.io.Closeable { db.close() }
                )
            }
        }
        single { AppSettingsRepository(get(named("appSettings"))) }
        single { SearchHistoryStore(get(named("searchHistory"))) }
        single { AbsCredentialStore(get(named("absCredentials"))) }
    }
}
