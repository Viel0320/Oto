package com.viel.oto.event.feedback

import com.viel.oto.event.AppEventSink
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
import com.viel.oto.library.scan.ScanNotice
import com.viel.oto.library.scan.ScanNoticeAccessForm
import com.viel.oto.library.scan.ScanNoticeContext
import com.viel.oto.library.scan.ScanNoticeMessage
import com.viel.oto.library.scan.ScanNoticeSeverity
import com.viel.oto.library.scan.ScanNoticeSink

/**
 * App-shell adapter from library scan notices to feedback facts.
 *
 * The library import module owns scan outcome semantics, while this adapter owns Android resource-backed
 * rendering and delivery through AppEventSink.
 */
class AppEventScanNoticeSink(
    private val appEventSink: AppEventSink
) : ScanNoticeSink {
    override fun emitNotice(notice: ScanNotice) {
        appEventSink.emitFeedback(notice.toFeedbackFact())
    }
}

/**
 * Converts a library scan notice into the existing app feedback fact model.
 */
fun ScanNotice.toFeedbackFact(): FeedbackFact =
    when (severity) {
        ScanNoticeSeverity.COMPLETED -> LibraryAccessFeedbackFacts.rescanCompleted(
            message = message.toFeedbackMessage(),
            context = context.toFeedbackContext()
        )
        ScanNoticeSeverity.BLOCKED -> LibraryAccessFeedbackFacts.rescanBlocked(
            message = message.toFeedbackMessage(),
            context = context.toFeedbackContext()
        )
        ScanNoticeSeverity.FAILED -> LibraryAccessFeedbackFacts.rescanFailed(message.toFeedbackMessage())
    }

/**
 * Builds the resource-backed availability message used by ABS sync and settings preflight.
 */
fun LibraryRootAvailabilityUpdate.toRootUnavailableFeedbackMessage(): FeedbackMessage {
    val rootName = root.displayName.ifBlank { root.sourceUri }
    return FeedbackMessages.libraryRootUnavailableSync(
        rootName = rootName,
        availabilityStatus = availability.status,
        fallbackCode = availability.errorCode ?: availability.status.name
    )
}

/**
 * Maps library-owned scan message keys to Android resource-backed feedback messages.
 */
private fun ScanNoticeMessage.toFeedbackMessage(): FeedbackMessage =
    when (this) {
        ScanNoticeMessage.Separator -> FeedbackMessages.messageSeparator()
        ScanNoticeMessage.UnavailableRootsNone -> FeedbackMessages.libraryRootsUnavailableNone()
        is ScanNoticeMessage.UnavailableRoot -> FeedbackMessages.libraryRootUnavailableSync(
            rootName = rootName,
            availabilityStatus = availabilityStatus,
            fallbackCode = fallbackCode
        )
        is ScanNoticeMessage.UnavailableRootCount -> FeedbackMessages.libraryRootsUnavailableSync(rootCount)
        is ScanNoticeMessage.Composite -> FeedbackMessage.Composite(parts.map { part -> part.toFeedbackMessage() })
        is ScanNoticeMessage.CompletedWithDiscoveredBooks -> FeedbackMessages.scanCompletedWithDiscoveredBooks(discoveredCount)
        ScanNoticeMessage.Completed -> FeedbackMessages.scanCompleted()
        ScanNoticeMessage.LibraryEmpty -> FeedbackMessages.scanLibraryEmpty()
        ScanNoticeMessage.AlreadyUpToDate -> FeedbackMessages.scanAlreadyUpToDate()
        is ScanNoticeMessage.CompletedSuffixUpdated -> FeedbackMessages.scanCompletedSuffixUpdated(updatedCount)
        is ScanNoticeMessage.CompletedSuffixPartial -> FeedbackMessages.scanCompletedSuffixPartial(partialCount)
        ScanNoticeMessage.BlockedNoAvailableLibraries -> FeedbackMessages.scanBlockedNoAvailableLibraries()
        ScanNoticeMessage.RetryLater -> FeedbackMessages.scanRetryLater()
        is ScanNoticeMessage.Failed -> FeedbackMessages.scanFailed(errorMessage)
    }

private fun ScanNoticeContext.toFeedbackContext(): FeedbackContext =
    when (this) {
        ScanNoticeContext.Global -> FeedbackContext.Global
        is ScanNoticeContext.LibraryRoot -> FeedbackContext.LibraryRoot(
            rootId = rootId,
            accessForm = accessForm.toFeedbackAccessForm()
        )
    }

private fun ScanNoticeAccessForm.toFeedbackAccessForm(): LibraryAccessForm =
    when (this) {
        ScanNoticeAccessForm.LOCAL_FOLDER -> LibraryAccessForm.LOCAL_FOLDER
        ScanNoticeAccessForm.WEBDAV -> LibraryAccessForm.WEBDAV
        ScanNoticeAccessForm.AUDIOBOOKSHELF -> LibraryAccessForm.AudiobookShelf
    }
