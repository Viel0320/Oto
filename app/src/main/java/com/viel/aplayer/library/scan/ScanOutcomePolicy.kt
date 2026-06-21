package com.viel.aplayer.library.scan

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.event.feedback.FeedbackContext
import com.viel.aplayer.event.feedback.FeedbackFact
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.event.feedback.LibraryAccessFeedbackFacts
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.availability.buildUnavailableRootsSyncMessage
import java.io.IOException

/**
 * Stable command result categories for scan callers.
 * Groups scanner results into success, partial, blocked, failed, and retryable meanings shared by UI and WorkManager callers.
 */
enum class ScanOutcomeKind {
    SUCCESS,
    PARTIAL,
    BLOCKED,
    FAILED,
    RETRY
}

/**
 * Command-level scan result contract.
 * Carries the persisted session when one exists plus the user-facing feedback fact and WorkManager result mapping for background callers.
 */
data class ScanOutcome(
    val kind: ScanOutcomeKind,
    val feedback: FeedbackFact?,
    val session: ScanSessionEntity? = null,
    val cause: Throwable? = null
)

/**
 * Maps scan sessions, preflight blocks, and exceptions into one command result language.
 * Keeps user feedback text, retry classification, and empty-library success handling out of runners, services, and workers.
 */
object ScanOutcomePolicy {

    /**
     * Turns a persisted scan session into success or partial semantics.
     * Partial imports remain successful commands but keep a distinct outcome kind so callers can surface softer warnings consistently.
     */
    fun fromCompletedSession(
        session: ScanSessionEntity,
        isLibraryEmpty: Boolean,
        skippedRoots: List<LibraryRootAvailabilityUpdate> = emptyList()
    ): ScanOutcome {
        if (session.status != AudiobookSchema.ScanStatus.COMPLETED) {
            return fromFailure(
                IllegalStateException("Scan session ended as ${session.status}")
            )
        }
        val changedCount = session.discoveredBookCount + session.updatedBookCount + session.partialBookCount
        val baseMessage = when {
            session.discoveredBookCount > 0 -> FeedbackMessages.scanCompletedWithDiscoveredBooks(session.discoveredBookCount)
            changedCount > 0 -> FeedbackMessages.scanCompleted()
            isLibraryEmpty -> FeedbackMessages.scanLibraryEmpty()
            else -> FeedbackMessages.scanAlreadyUpToDate()
        }
        val message = baseMessage
            .appendIf(session.updatedBookCount > 0) {
                FeedbackMessages.scanCompletedSuffixUpdated(session.updatedBookCount)
            }
            .appendIf(session.partialBookCount > 0) {
                FeedbackMessages.scanCompletedSuffixPartial(session.partialBookCount)
            }
            .appendIf(skippedRoots.isNotEmpty()) {
                FeedbackMessage.Composite(
                    listOf(
                        FeedbackMessages.messageSeparator(),
                        buildUnavailableRootsSyncMessage(skippedRoots)
                    )
                )
            }
        val kind = if (session.partialBookCount > 0 || session.unavailableBookCount > 0 || skippedRoots.isNotEmpty()) {
            ScanOutcomeKind.PARTIAL
        } else {
            ScanOutcomeKind.SUCCESS
        }
        return ScanOutcome(
            kind = kind,
            feedback = LibraryAccessFeedbackFacts.rescanCompleted(message, skippedRootsContext(skippedRoots)),
            session = session
        )
    }

    /**
     * Builds a shared no-work outcome when no library can be used.
     *
     * The scanner still protects its importer from no-work commands, but the rendered feedback stays
     * access-form-neutral so users do not see separate local, WebDAV, or catalog-backed library wording.
     */
    fun blocked(
        unavailableRoots: List<LibraryRootAvailabilityUpdate>,
        hasAvailableLibrary: Boolean
    ): ScanOutcome {
        val message = if (!hasAvailableLibrary) {
            FeedbackMessages.scanBlockedNoAvailableLibraries()
        } else {
            buildUnavailableRootsSyncMessage(unavailableRoots)
        }
        return ScanOutcome(
            kind = ScanOutcomeKind.BLOCKED,
            feedback = LibraryAccessFeedbackFacts.rescanBlocked(message, skippedRootsContext(unavailableRoots))
        )
    }

    /**
     * Reports a successful no-op when another library access form is available.
     *
     * Catalog-backed libraries can be available even when the directory importer has no SAF or WebDAV root
     * to traverse. This outcome avoids telling the listener that no library is available while keeping the
     * scan command result non-failing.
     */
    fun noScanWorkRequired(): ScanOutcome =
        ScanOutcome(
            kind = ScanOutcomeKind.SUCCESS,
            feedback = LibraryAccessFeedbackFacts.rescanCompleted(
                message = FeedbackMessages.scanAlreadyUpToDate(),
                context = FeedbackContext.Global
            )
        )

    /**
     * Classifies scan exceptions for Worker retry and user feedback.
     * IO failures are treated as transient retry candidates; other exceptions are permanent command failures until proven otherwise.
     */
    fun fromFailure(error: Throwable): ScanOutcome {
        val kind = if (error is IOException) ScanOutcomeKind.RETRY else ScanOutcomeKind.FAILED
        val message = if (kind == ScanOutcomeKind.RETRY) {
            FeedbackMessages.scanRetryLater()
        } else {
            FeedbackMessages.scanFailed(error.message ?: error::class.java.simpleName)
        }
        return ScanOutcome(
            kind = kind,
            feedback = LibraryAccessFeedbackFacts.rescanFailed(message),
            cause = error
        )
    }

    /**
     * Keys rescan feedback to a single skipped root when one is identifiable.
     *
     * A lone skipped root keeps its stable, non-sensitive [FeedbackContext.LibraryRoot] identity; multiple
     * skipped roots fall back to [FeedbackContext.Global] because the message reports only a count.
     */
    private fun skippedRootsContext(skippedRoots: List<LibraryRootAvailabilityUpdate>): FeedbackContext =
        if (skippedRoots.size == 1) {
            val root = skippedRoots.first().root
            LibraryAccessFeedbackFacts.libraryRootContext(
                rootId = root.id,
                accessForm = LibraryAccessFeedbackFacts.accessFormOf(root.sourceType)
            )
        } else {
            FeedbackContext.Global
        }
}

/**
 * Combines resource-backed fragments for scan summaries.
 *
 * Scan outcomes still expose one feedback fact, while individual fragments remain resource keys for
 * localization and snapshot testing.
 */
private fun FeedbackMessage.appendIf(
    condition: Boolean,
    suffix: () -> FeedbackMessage
): FeedbackMessage =
    if (condition) {
        FeedbackMessage.Composite(flattenFeedbackParts() + suffix().flattenFeedbackParts())
    } else {
        this
    }

private fun FeedbackMessage.flattenFeedbackParts(): List<FeedbackMessage> =
    when (this) {
        is FeedbackMessage.Composite -> parts
        else -> listOf(this)
    }
