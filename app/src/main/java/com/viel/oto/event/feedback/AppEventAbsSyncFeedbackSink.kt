package com.viel.oto.event.feedback

import com.viel.oto.abs.sync.AbsSyncFeedbackSink
import com.viel.oto.abs.sync.AbsSyncSummary
import com.viel.oto.event.AppEventSink
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate

/**
 * App-shell adapter from ABS sync facts to feedback facts.
 *
 * ABS stays responsible for protocol and sync outcomes; this adapter owns resource-backed rendering and
 * delivery through the process-wide AppEventSink.
 */
class AppEventAbsSyncFeedbackSink(
    private val appEventSink: AppEventSink
) : AbsSyncFeedbackSink {
    override fun syncRootMissing() {
        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncRootMissing())
    }

    override fun syncBlocked(rootId: String, availability: LibraryRootAvailabilityUpdate) {
        appEventSink.emitFeedback(
            LibraryAccessFeedbackFacts.syncBlocked(
                rootId = rootId,
                detailMessage = availability.toRootUnavailableFeedbackMessage()
            )
        )
    }

    override fun syncCompleted(rootId: String, summary: AbsSyncSummary) {
        appEventSink.emitFeedback(
            LibraryAccessFeedbackFacts.syncCompleted(
                rootId = rootId,
                addedBooks = summary.addedBooks,
                failedItems = summary.failedItems
            )
        )
    }

    override fun syncFailed(rootId: String, redactedMessage: String) {
        appEventSink.emitFeedback(LibraryAccessFeedbackFacts.syncFailed(rootId, redactedMessage))
    }
}
