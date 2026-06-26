package com.viel.oto.library.scan

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.ScanSessionEntity
import com.viel.oto.library.availability.LibraryRootAvailabilityUpdate
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
 * Carries the persisted session when one exists plus a library-owned notice and WorkManager result mapping for background callers.
 */
data class ScanOutcome(
    val kind: ScanOutcomeKind,
    val notice: ScanNotice?,
    val session: ScanSessionEntity? = null,
    val cause: Throwable? = null
)

/**
 * Maps scan sessions, preflight blocks, and exceptions into one command result language.
 * Keeps notice selection, retry classification, and empty-library success handling out of runners, services, and workers.
 */
object ScanOutcomePolicy {

    /**
     * Turns a persisted scan session into success or partial semantics.
     * Partial imports remain successful commands but keep a distinct outcome kind so callers can surface softer warnings consistently.
     */
    fun fromCompletedSession(
        session: ScanSessionEntity,
        isLibraryEmpty: Boolean,
        skippedRoots: List<LibraryRootAvailabilityUpdate> = emptyList(),
        primaryContext: ScanNoticeContext = ScanNoticeContext.Global
    ): ScanOutcome {
        if (session.status != AudiobookSchema.ScanStatus.COMPLETED) {
            return fromFailure(
                IllegalStateException("Scan session ended as ${session.status}")
            )
        }
        val changedCount = session.discoveredBookCount + session.updatedBookCount + session.partialBookCount
        val baseMessage = when {
            session.discoveredBookCount > 0 -> ScanNoticeMessage.CompletedWithDiscoveredBooks(session.discoveredBookCount)
            changedCount > 0 -> ScanNoticeMessage.Completed
            isLibraryEmpty -> ScanNoticeMessage.LibraryEmpty
            else -> ScanNoticeMessage.AlreadyUpToDate
        }
        val message = baseMessage
            .appendIf(session.updatedBookCount > 0) {
                ScanNoticeMessage.CompletedSuffixUpdated(session.updatedBookCount)
            }
            .appendIf(session.partialBookCount > 0) {
                ScanNoticeMessage.CompletedSuffixPartial(session.partialBookCount)
            }
            .appendIf(skippedRoots.isNotEmpty()) {
                ScanNoticeMessage.Composite(
                    listOf(
                        ScanNoticeMessage.Separator,
                        unavailableRootsNotice(skippedRoots)
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
            notice = ScanNotice(
                message = message,
                context = resolveContext(skippedRoots, primaryContext),
                severity = ScanNoticeSeverity.COMPLETED
            ),
            session = session
        )
    }

    /**
     * Builds the single Global summary notice for a cold-start fan-out that changed the catalog.
     *
     * Per-root cold-start jobs stay silent; the scheduler emits this one aggregated fact so a background scan of many
     * roots never produces a burst of per-root toasts.
     */
    fun coldStartSummaryNotice(discoveredBookCount: Int): ScanNotice {
        val message = if (discoveredBookCount > 0) {
            ScanNoticeMessage.CompletedWithDiscoveredBooks(discoveredBookCount)
        } else {
            ScanNoticeMessage.Completed
        }
        return ScanNotice(
            message = message,
            context = ScanNoticeContext.Global,
            severity = ScanNoticeSeverity.COMPLETED
        )
    }

    /**
     * Builds a shared no-work outcome when no library can be used.
     *
     * The scanner still protects its importer from no-work commands, but the rendered notice stays
     * access-form-neutral so users do not see separate local, WebDAV, or catalog-backed library wording.
     */
    fun blocked(
        unavailableRoots: List<LibraryRootAvailabilityUpdate>,
        hasAvailableLibrary: Boolean
    ): ScanOutcome {
        val message = if (!hasAvailableLibrary) {
            ScanNoticeMessage.BlockedNoAvailableLibraries
        } else {
            unavailableRootsNotice(unavailableRoots)
        }
        return ScanOutcome(
            kind = ScanOutcomeKind.BLOCKED,
            notice = ScanNotice(
                message = message,
                context = skippedRootsContext(unavailableRoots),
                severity = ScanNoticeSeverity.BLOCKED
            )
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
            notice = ScanNotice(
                message = ScanNoticeMessage.AlreadyUpToDate,
                context = ScanNoticeContext.Global,
                severity = ScanNoticeSeverity.COMPLETED
            )
        )

    /**
     * Classifies scan exceptions for Worker retry and user notice mapping.
     * IO failures are treated as transient retry candidates; other exceptions are permanent command failures until proven otherwise.
     */
    fun fromFailure(error: Throwable): ScanOutcome {
        val kind = if (error is IOException) ScanOutcomeKind.RETRY else ScanOutcomeKind.FAILED
        val message = if (kind == ScanOutcomeKind.RETRY) {
            ScanNoticeMessage.RetryLater
        } else {
            ScanNoticeMessage.Failed(error.message ?: error::class.java.simpleName)
        }
        return ScanOutcome(
            kind = kind,
            notice = ScanNotice(
                message = message,
                context = ScanNoticeContext.Global,
                severity = ScanNoticeSeverity.FAILED
            ),
            cause = error
        )
    }

    /**
     * Keys rescan notice context to a single skipped root when one is identifiable.
     *
     * A lone skipped root keeps its stable, non-sensitive [ScanNoticeContext.LibraryRoot] identity;
     * multiple skipped roots fall back to [ScanNoticeContext.Global] because the message reports only a count.
     */
    private fun skippedRootsContext(skippedRoots: List<LibraryRootAvailabilityUpdate>): ScanNoticeContext =
        if (skippedRoots.size == 1) {
            val root = skippedRoots.first().root
            ScanNoticeContext.LibraryRoot(
                rootId = root.id,
                accessForm = scanAccessFormOf(root.sourceType)
            )
        } else {
            ScanNoticeContext.Global
        }

    /**
     * Picks the rescan notice context, preferring the caller-supplied [primaryContext] (a single user-scanned
     * root) when nothing was skipped, and keying to a lone skipped root otherwise.
     */
    private fun resolveContext(
        skippedRoots: List<LibraryRootAvailabilityUpdate>,
        primaryContext: ScanNoticeContext
    ): ScanNoticeContext =
        when {
            skippedRoots.isEmpty() -> primaryContext
            else -> skippedRootsContext(skippedRoots)
        }

    /**
     * Maps persisted root source types to scan notice identity without importing app feedback classes.
     */
    fun scanAccessFormOf(sourceType: AudiobookSchema.LibrarySourceType): ScanNoticeAccessForm =
        when (sourceType) {
            AudiobookSchema.LibrarySourceType.SAF -> ScanNoticeAccessForm.LOCAL_FOLDER
            AudiobookSchema.LibrarySourceType.WEBDAV -> ScanNoticeAccessForm.WEBDAV
            AudiobookSchema.LibrarySourceType.ABS -> ScanNoticeAccessForm.AUDIOBOOKSHELF
        }

    /**
     * Creates the compact scan notice message for skipped roots.
     */
    private fun unavailableRootsNotice(updates: List<LibraryRootAvailabilityUpdate>): ScanNoticeMessage {
        if (updates.isEmpty()) return ScanNoticeMessage.UnavailableRootsNone
        if (updates.size == 1) return unavailableRootNotice(updates.first())
        return ScanNoticeMessage.UnavailableRootCount(updates.size)
    }

    private fun unavailableRootNotice(update: LibraryRootAvailabilityUpdate): ScanNoticeMessage {
        val rootName = update.root.displayName.ifBlank { update.root.sourceUri }
        val status = update.availability.status
        return ScanNoticeMessage.UnavailableRoot(
            rootName = rootName,
            availabilityStatus = status,
            fallbackCode = update.availability.errorCode ?: status.name
        )
    }
}

/**
 * Combines resource-backed fragments for scan summaries.
 *
 * Scan outcomes still expose one notice, while individual fragments remain separate keys for
 * localization and app-side mapping.
 */
private fun ScanNoticeMessage.appendIf(
    condition: Boolean,
    suffix: () -> ScanNoticeMessage
): ScanNoticeMessage =
    if (condition) {
        ScanNoticeMessage.Composite(flattenNoticeParts() + suffix().flattenNoticeParts())
    } else {
        this
    }

private fun ScanNoticeMessage.flattenNoticeParts(): List<ScanNoticeMessage> =
    when (this) {
        is ScanNoticeMessage.Composite -> parts
        else -> listOf(this)
    }
