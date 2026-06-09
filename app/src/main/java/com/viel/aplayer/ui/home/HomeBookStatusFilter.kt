package com.viel.aplayer.ui.home

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Home Book Status Filter (User-facing availability filter for the Home catalog)
 *
 * Keeps the Home view dialog's status choices separate from the existing read-progress chips.
 * The enum stores stable preference names while matching against the Room-backed BookStatus constants.
 */
enum class HomeBookStatusFilter(private val schemaStatus: String?) {
    // All Statuses Filter (Default option that preserves the full visible Home catalog)
    // A null schema status intentionally means no additional BookStatus constraint is applied.
    All(schemaStatus = null),

    // Ready Status Filter (Shows fully available books)
    // Maps to BookStatus.READY so users can isolate books whose media files are currently playable.
    Ready(schemaStatus = AudiobookSchema.BookStatus.READY),

    // Partial Status Filter (Shows books with at least one unavailable file)
    // Maps to BookStatus.PARTIAL so incomplete multi-file imports remain discoverable from Home.
    Partial(schemaStatus = AudiobookSchema.BookStatus.PARTIAL),

    // Unavailable Status Filter (Shows books whose playable files are currently unavailable)
    // Maps to BookStatus.UNAVAILABLE for local permission loss or remote reachability failures.
    Unavailable(schemaStatus = AudiobookSchema.BookStatus.UNAVAILABLE);

    // Book Status Match Rule (Apply All as a pass-through and concrete filters as exact BookStatus matches)
    // Keeping this rule on the enum prevents LibraryViewModel from duplicating schema-string comparisons.
    fun matches(bookStatus: String): Boolean {
        return schemaStatus == null || bookStatus == schemaStatus
    }

    companion object {
        // Stored Preference Parsing (Resolve persisted enum names with a safe All fallback)
        // Invalid, stale, or previously persisted Conflict values should not hide the user's library after app upgrades.
        fun fromStoredName(value: String): HomeBookStatusFilter =
            runCatching { valueOf(value) }.getOrDefault(All)
    }
}
