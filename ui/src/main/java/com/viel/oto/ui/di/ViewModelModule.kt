package com.viel.oto.ui.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.viel.oto.ui.detail.DetailViewModel
import com.viel.oto.ui.edit.EditBookViewModel
import com.viel.oto.ui.home.LibraryViewModel
import com.viel.oto.ui.libraryManagement.RemoteConnectionViewModel
import com.viel.oto.ui.player.BookmarkViewModel
import com.viel.oto.ui.player.PlaybackViewModel
import com.viel.oto.ui.player.PlayerSettingsViewModel
import com.viel.oto.ui.search.SearchViewModel
import com.viel.oto.ui.settings.SettingsViewModel
import com.viel.oto.ui.settings.recovery.DeletedBookRecoveryViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * UI-owned Koin definitions for every screen ViewModel.
 *
 * The app shell imports this module as a composition-root contribution while the
 * ViewModel classes and their constructor wiring remain owned by the UI module.
 */
@OptIn(UnstableApi::class)
object ViewModelModule {

    val module: Module = module {
        viewModel { LibraryViewModel(get(), get(), get(), get(), get(), get()) }
        viewModel { PlaybackViewModel(get(), get(), get(), get(), get(), get(), get()) }
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
