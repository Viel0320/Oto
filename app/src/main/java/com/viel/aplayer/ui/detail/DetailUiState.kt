package com.viel.aplayer.ui.detail

import com.viel.aplayer.data.entity.BookWithProgress
import com.viel.aplayer.media.parser.ImageProcessor

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
    /** Currently selected book entity */
    val book: BookWithProgress? = null,
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
    // Added fullSourcePath field to store the complete physical source file path processed at the ViewModel level (after SAF decoding, 'primary:' filtering, and concatenation with the filename), ensuring purity and high performance in the UI rendering layer.
    val fullSourcePath: String = ""
)
