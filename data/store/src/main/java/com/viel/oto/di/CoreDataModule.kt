package com.viel.oto.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.viel.oto.data.AppSettingsRepository
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.store.SearchHistoryStore
import com.viel.oto.data.webdav.WebDavCredentialStore
import com.viel.oto.data.webdav.webDavCredentialDataStore
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Process-wide durable data stores: Room database, settings repository, WebDAV credential store,
 * and search history store.
 *
 * Each DataStore is registered with a named qualifier because they all share the
 * [DataStore]<[Preferences]> type but differ by preferencesDataStore name.
 *
 * Application-level settings contracts are bound in CoreSettingsModule so this data module
 * does not depend on application scene interfaces while data extraction proceeds.
 */
object CoreDataModule {

    private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
    private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history")

    val module: Module = module {
        single(named("appSettings")) { get<Context>().applicationContext.appSettingsDataStore }
        single(named("searchHistory")) { get<Context>().applicationContext.searchHistoryDataStore }
        single(named("webDavCredentials")) { get<Context>().applicationContext.webDavCredentialDataStore }

        single {
            AppDatabase.create(get()).also { db ->
                GraphClosePolicy.register(
                    stage = GraphClosePolicy.Stage.Data,
                    closeable = { db.close() }
                )
            }
        }
        single { AppSettingsRepository(get(named("appSettings"))) }
        single { SearchHistoryStore(get(named("searchHistory"))) }
        single { WebDavCredentialStore(get(named("webDavCredentials"))) }
    }
}
