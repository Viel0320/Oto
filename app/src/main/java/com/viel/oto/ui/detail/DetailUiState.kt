package com.viel.oto.ui.detail

import com.viel.oto.application.download.BookCacheStatus
import com.viel.oto.application.library.detail.DetailSnapshot

/**
 * Detail overlay motion origin.
 *
 * Records the UI surface that opened the current detail overlay so shared-element artwork
 * transitions can bind only to the matching source and avoid cross-activation between Home sections.
 */
enum class DetailEntrySource {
    /** No shared-element source is active for the current detail entry. */
    None,
    /** The current detail entry was opened from the horizontal Home recent section. */
    HomeRecent,
    /** The current detail entry was opened from the vertical/adaptive Home list section. */
    HomeList,
    /** The current detail entry was opened from Search or another non-Home surface. */
    Search
}

/**
 * Detail Screen UI State.
 *
 * UI State for the book details page.
 */
data class DetailUiState(
    /**
     * Transition boundary for the detail scene.
     * Stores the module-produced snapshot and Room-free detail item so source location, availability, and metadata stay behind the detail scene boundary.
     */
    val book: DetailSnapshot? = null,
    val entrySource: DetailEntrySource = DetailEntrySource.None,
    /** Whether to show the details page */
    val isVisible: Boolean = false,
    /** Whether the book files are available locally */
    val isAvailable: Boolean = true,
    /** Playback progress percentage (0-100), the real progress from the database */
    val progressPercent: Int = 0,
    val displayProgressPercent: Int = 0,
    val fullSourcePath: String = "",
    val bookCacheStatus: BookCacheStatus = BookCacheStatus.none(),
    /**
     * Read-model projected visibility for the manual cache shortcut.
     * Compose reads this flag directly so source-type eligibility stays outside the UI layer.
     */
    val shouldShowDownloadAction: Boolean = false
)
