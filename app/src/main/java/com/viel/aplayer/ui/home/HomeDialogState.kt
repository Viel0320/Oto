package com.viel.aplayer.ui.home

import com.viel.aplayer.application.library.home.HomeBookItem

/**
 * Page-owned dialog event model.
 *
 * Describes the active dialog requested by Home click events without embedding dialog rendering in the bookshelf content component.
 * The state stays inside the Home page container so the content layer can remain a pure renderer that reports user intent.
 */
sealed interface HomeDialogState {
    /**
     * Home dialog host is idle.
     *
     * Represents the default state where no Home-owned dialog should be rendered.
     */
    data object None : HomeDialogState

    /**
     * Long-press operation menu for one audiobook.
     *
     * Carries the selected audiobook for read-status updates, metadata regeneration, and soft-delete confirmation flows.
     */
    data class AudiobookActions(val book: HomeBookItem) : HomeDialogState
}
