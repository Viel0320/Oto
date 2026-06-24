package com.viel.aplayer.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.libraryManagement.RemoteConnectionViewModel
import com.viel.aplayer.ui.player.BookmarkViewModel
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import com.viel.aplayer.ui.search.SearchViewModel
import com.viel.aplayer.ui.settings.SettingsViewModel
import com.viel.aplayer.ui.settings.recovery.DeletedBookRecoveryViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * ViewModel definitions for every screen.
 * Each ViewModel receives its dependency-view interface through constructor injection.
 */
@OptIn(UnstableApi::class)
internal object ViewModelModule {

    val module: Module = module {
        viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { PlaybackViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { BookmarkViewModel(get(), get(), get()) }
        viewModel { PlayerSettingsViewModel(get(), get(), get(), get(), get()) }
        viewModel { DetailViewModel(get(), get(), get(), get(), get()) }
        viewModel { EditBookViewModel(get(), get()) }
        viewModel { SearchViewModel(get(), get()) }
        viewModel { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { RemoteConnectionViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        viewModel { DeletedBookRecoveryViewModel(get(), get(), get()) }
    }
}
