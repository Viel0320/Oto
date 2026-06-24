package com.viel.aplayer.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.abs.auth.AbsCredentialStore
import com.viel.aplayer.application.library.settings.AppSettingsReadModel
import com.viel.aplayer.data.AppSettingsRepository
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.store.SearchHistoryStore
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Process-wide durable data stores: Room database, settings repository, ABS credential store,
 * and search history store.
 *
 * Each DataStore is registered with a named qualifier because they all share the
 * [DataStore]<[Preferences]> type but differ by preferencesDataStore name.
 *
 * AppSettingsRepository is registered once and bound to its read contract from the same
 * definition so release shrinking keeps a single dependency-resolution path.
 */
@UnstableApi
internal object CoreDataModule {

    private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
    private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")
    private val Context.absCredentialDataStore: DataStore<Preferences> by preferencesDataStore(name = "abs_credentials")

    val module: Module = module {
        single(named("appSettings")) { get<Context>().applicationContext.appSettingsDataStore }
        single(named("searchHistory")) { get<Context>().applicationContext.searchHistoryDataStore }
        single(named("absCredentials")) { get<Context>().applicationContext.absCredentialDataStore }

        single {
            AppDatabase.create(get()).also { db ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Data,
                    closeable = { db.close() }
                )
            }
        }
        single { AppSettingsRepository(get(named("appSettings"))) } bind AppSettingsReadModel::class
        single { SearchHistoryStore(get(named("searchHistory"))) }
        single { AbsCredentialStore(get(named("absCredentials"))) }
    }
}
