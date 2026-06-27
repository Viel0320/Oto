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
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * UI-owned Koin definitions for every screen ViewModel.
 *
 * The app shell imports this module as a composition-root contribution while the
 * ViewModel classes and their constructor wiring remain owned by the UI module.
 * Constructor references keep regular registrations readable, while ViewModels with non-DI
 * lifecycle override hooks keep explicit constructors so Koin never resolves those hooks.
 */
@OptIn(UnstableApi::class)
object ViewModelModule {

    val module: Module = module {
        viewModelOf(::LibraryViewModel)
        viewModel {
            PlaybackViewModel(
                playbackController = get(),
                playerLibraryReadModel = get(),
                prepareBookPlaybackUseCase = get(),
                resolveProgressConflictUseCase = get(),
                appEventSink = get(),
                settingsReadModel = get(),
                settingsCommands = get(),
                rawExternalScope = null
            )
        }
        viewModel {
            BookmarkViewModel(
                application = get(),
                bookmarkCommands = get(),
                appEventSink = get(),
                rawExternalScope = null
            )
        }
        viewModel {
            PlayerSettingsViewModel(
                application = get(),
                settingsReadModel = get(),
                settingsCommands = get(),
                appEventSink = get(),
                playerPlaybackController = get(),
                rawExternalScope = null
            )
        }
        viewModelOf(::DetailViewModel)
        viewModelOf(::EditBookViewModel)
        viewModelOf(::SearchViewModel)
        viewModelOf(::SettingsViewModel)
        viewModelOf(::RemoteConnectionViewModel)
        viewModelOf(::DeletedBookRecoveryViewModel)
    }
}
