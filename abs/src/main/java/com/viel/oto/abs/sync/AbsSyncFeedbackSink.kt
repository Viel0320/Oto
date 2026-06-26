package com.viel.oto.abs.sync

import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate

/**
 * Reports ABS sync lifecycle facts without depending on app-shell feedback rendering.
 *
 * The ABS module owns the protocol and scheduling result, while the app module decides how those facts
 * become resource-backed feedback. Keeping this seam small prevents ABS sync from importing UI/event
 * types just to show a toast.
 */
interface AbsSyncFeedbackSink {
    fun syncRootMissing()

    fun syncBlocked(rootId: String, availability: LibraryRootAvailabilityUpdate)

    fun syncCompleted(rootId: String, summary: AbsSyncSummary)

    fun syncFailed(rootId: String, redactedMessage: String)
}
