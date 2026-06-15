package com.viel.aplayer.ui.detail

import com.viel.aplayer.application.download.BookCacheStatus
import com.viel.aplayer.application.library.detail.DetailSnapshot

/**
 * Detail Entry Source (Detail overlay motion origin)
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
 * DetailUiState Model (Detail Screen UI State)
 *
 * UI State for the book details page.
 */
data class DetailUiState(
    /**
     * Selected Detail Snapshot (Transition boundary for the detail scene)
     * Stores the module-produced snapshot and Room-free detail item so source location, availability, and metadata stay behind the detail scene boundary.
     */
    val book: DetailSnapshot? = null,
    /*
     * Detail Entry Source (Shared-element source selector)
     *
     * Keeps the entry origin with the selected book so Home recent and Home list covers can
     * use independent motion channels without both reacting to the same visible detail book.
     */
    val entrySource: DetailEntrySource = DetailEntrySource.None,
    /** Whether to show the details page */
    val isVisible: Boolean = false,
    /** Whether the book files are available locally */
    // Detail page adopts an optimistic availability status, only degrading to unplayable after background VFS check confirms missing, avoiding false alarms of unavailability when opening details.
    val isAvailable: Boolean = true,
    /** Playback progress percentage (0-100), the real progress from the database */
    val progressPercent: Int = 0,
    // Fix M-19 (Display Progress Under Protection)
    // Display progress filtered through the 3-second protection period.
    // Within 3 seconds after the user clicks play from the "unstarted state", this value is forced to 0,
    // preventing the button icon/text from high-frequency flashing between "Start Listening" and "Continue at X%".
    // Controlled by DetailViewModel.onPlayPressed, and preserved through configuration changes.
    val displayProgressPercent: Int = 0,
    // Deprecated: backgroundColorArgb is removed
    // Detail Source Indicator Text (Stores the user-facing storage breadcrumb for the selected book)
    // The ViewModel formats this from library root labels and VFS-relative file metadata so the UI never displays raw SAF tree URIs, WebDAV URLs, or remote playback endpoints.
    val fullSourcePath: String = "",
    // Detail Manual Cache Status (Expose the selected book's offline-cache state to the top bar)
    // Missing download metadata is represented as NONE here so DetailContent never reads Room rows or Media3 DownloadIndex state.
    val bookCacheStatus: BookCacheStatus = BookCacheStatus.none()
)
