package com.viel.aplayer.di.koin

import androidx.media3.common.util.UnstableApi
import com.viel.aplayer.ui.detail.DetailViewModel
import com.viel.aplayer.ui.edit.EditBookViewModel
import com.viel.aplayer.ui.home.LibraryViewModel
import com.viel.aplayer.ui.player.BookmarkViewModel
import com.viel.aplayer.ui.player.PlaybackViewModel
import com.viel.aplayer.ui.player.PlayerSettingsViewModel
import com.viel.aplayer.ui.search.SearchViewModel
import com.viel.aplayer.ui.settings.SettingsViewModel
import com.viel.aplayer.ui.settings.recovery.DeletedBookRecoveryViewModel
import com.viel.aplayer.ui.settings.remote.RemoteConnectionViewModel
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * ViewModel definitions for every screen.
 * Each ViewModel receives its dependency-view interface through constructor injection.
 */
@UnstableApi
internal object ViewModelModule {

    val module: Module = module {
        viewModel { LibraryViewModel(get()) }
        viewModel { PlaybackViewModel(get()) }
        viewModel { BookmarkViewModel(get(), get()) }
        viewModel { PlayerSettingsViewModel(get(), get()) }
        viewModel { DetailViewModel(get()) }
        viewModel { EditBookViewModel(get()) }
        viewModel { SearchViewModel(get()) }
        viewModel { SettingsViewModel(get(), get()) }
        viewModel { RemoteConnectionViewModel(get(), get()) }
        viewModel { DeletedBookRecoveryViewModel(get()) }
    }
}
