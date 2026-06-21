package com.viel.aplayer.application.library.settings

import com.viel.aplayer.application.usecase.LibraryRootSettingsSnapshot
import kotlinx.coroutines.flow.Flow

/**
 * Scene-level root display stream.
 * Provides the settings page with root snapshots without exposing the broad library transition entry point.
 */
interface SettingsRootReadModel {
    /**
     * Return root rows plus settings-only display facts.
     * Keeps root list queries behind the settings-root module so SettingsViewModel can render rows without selecting storage gateways directly.
     */
    fun observeRootSnapshots(): Flow<List<LibraryRootSettingsSnapshot>>
}
