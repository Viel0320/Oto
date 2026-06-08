package com.viel.aplayer.library.scan

import com.viel.aplayer.data.entity.ScanSessionEntity
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.FeedbackMessages
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.availability.buildUnavailableRootsSyncMessage
import java.io.IOException

/**
 * Scan Outcome Kind (Stable command result categories for scan callers)
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
 * Scan Outcome (Command-level scan result contract)
 * Carries the persisted session when one exists plus the user-facing message and WorkManager result mapping for background callers.
 */
data class ScanOutcome(
    val kind: ScanOutcomeKind,
    val message: FeedbackMessage?,
    val session: ScanSessionEntity? = null,
    val cause: Throwable? = null
)

/**
 * Scan Outcome Policy (Maps scan sessions, preflight blocks, and exceptions into one command result language)
 * Keeps user feedback text, retry classification, and empty-library success handling out of runners, services, and workers.
 */
object ScanOutcomePolicy {

    /**
     * Completed Scan Mapping (Turns a persisted scan session into success or partial semantics)
     * Partial imports remain successful commands but keep a distinct outcome kind so callers can surface softer warnings consistently.
     */
    fun fromCompletedSession(
        session: ScanSessionEntity,
        isLibraryEmpty: Boolean,
        skippedRoots: List<LibraryRootAvailabilityUpdate> = emptyList()
    ): ScanOutcome {
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
            message = message,
            session = session
        )
    }

    /**
     * Blocked Scan Mapping (Builds a shared no-work outcome for unavailable or missing roots)
     * The scanner command can complete without retry when there is no reachable directory root to traverse.
     */
    fun blocked(unavailableRoots: List<LibraryRootAvailabilityUpdate>): ScanOutcome {
        val message = if (unavailableRoots.isEmpty()) {
            FeedbackMessages.scanBlockedNoDirectoryRoots()
        } else {
            buildUnavailableRootsSyncMessage(unavailableRoots)
        }
        return ScanOutcome(
            kind = ScanOutcomeKind.BLOCKED,
            message = message
        )
    }

    /**
     * Failure Mapping (Classifies scan exceptions for Worker retry and user feedback)
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
            message = message,
            cause = error
        )
    }
}

/**
 * Composite Feedback Message Builder (Combines resource-backed fragments for scan summaries)
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
