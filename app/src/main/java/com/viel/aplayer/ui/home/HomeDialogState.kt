package com.viel.aplayer.ui.home

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.data.entity.ScanSessionEntity

/**
 * Home Dialog State (Page-owned dialog event model)
 *
 * Describes the active dialog requested by Home click events without embedding dialog rendering in the bookshelf content component.
 * The state stays inside the Home page container so the content layer can remain a pure renderer that reports user intent.
 */
sealed interface HomeDialogState {
    /**
     * No Dialog (Home dialog host is idle)
     *
     * Represents the default state where no Home-owned dialog should be rendered.
     */
    data object None : HomeDialogState

    /**
     * Audiobook Actions (Long-press operation menu for one audiobook)
     *
     * Carries the selected audiobook for read-status updates, metadata regeneration, and soft-delete confirmation flows.
     */
    data class AudiobookActions(val bookWithProgress: BookWithProgress) : HomeDialogState

    /**
     * Scan Result (Completed library scan summary)
     *
     * Carries the completed scan session so HomeDialogHost can render import feedback through the shared dialog template.
     */
    data class ScanResult(val session: ScanSessionEntity) : HomeDialogState
}
