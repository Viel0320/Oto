package com.viel.oto.library.scan

import com.viel.oto.data.db.AudiobookSchema

/**
 * Library-owned user notice emitted by scan commands.
 *
 * This model keeps scan results independent from app resources and event delivery. The app shell maps it
 * to FeedbackFact only at the presentation edge, while the import module can remain free of `R` and UI
 * dependencies.
 */
data class ScanNotice(
    val message: ScanNoticeMessage,
    val context: ScanNoticeContext,
    val severity: ScanNoticeSeverity
)

/**
 * Stable scan notice message keys.
 *
 * The keys describe scan outcomes and formatting arguments without carrying Android string resources.
 * Presentation adapters own the final localized resource lookup.
 */
sealed interface ScanNoticeMessage {
    data object Separator : ScanNoticeMessage
    data object UnavailableRootsNone : ScanNoticeMessage
    data class UnavailableRoot(
        val rootName: String,
        val availabilityStatus: AudiobookSchema.AvailabilityStatus,
        val fallbackCode: String
    ) : ScanNoticeMessage
    data class UnavailableRootCount(val rootCount: Int) : ScanNoticeMessage
    data class Composite(val parts: List<ScanNoticeMessage>) : ScanNoticeMessage
    data class CompletedWithDiscoveredBooks(val discoveredCount: Int) : ScanNoticeMessage
    data object Completed : ScanNoticeMessage
    data object LibraryEmpty : ScanNoticeMessage
    data object AlreadyUpToDate : ScanNoticeMessage
    data class CompletedSuffixUpdated(val updatedCount: Int) : ScanNoticeMessage
    data class CompletedSuffixPartial(val partialCount: Int) : ScanNoticeMessage
    data object BlockedNoAvailableLibraries : ScanNoticeMessage
    data object RetryLater : ScanNoticeMessage
    data class Failed(val errorMessage: String) : ScanNoticeMessage
}

/**
 * Non-sensitive aggregation context for scan notices.
 *
 * Context identifies the user-visible scope of a scan result without exposing display names, paths, URLs,
 * tokens, or credentials.
 */
sealed interface ScanNoticeContext {
    data object Global : ScanNoticeContext
    data class LibraryRoot(
        val rootId: String,
        val accessForm: ScanNoticeAccessForm
    ) : ScanNoticeContext
}

/**
 * User-visible library access form for scan notice identity.
 */
enum class ScanNoticeAccessForm {
    LOCAL_FOLDER,
    WEBDAV,
    AUDIOBOOKSHELF
}

/**
 * User-perceived scan result severity used by the app feedback adapter.
 */
enum class ScanNoticeSeverity {
    COMPLETED,
    BLOCKED,
    FAILED
}

/**
 * Delivery seam for scan notices.
 *
 * ScanSchedulerImpl emits notices through this narrow seam so the library import module does not depend
 * on the app event stream or Android resource-backed feedback facts.
 */
fun interface ScanNoticeSink {
    fun emitNotice(notice: ScanNotice)
}

/**
 * Default sink used by tests or background callers that only need the structured ScanOutcome.
 */
object NoOpScanNoticeSink : ScanNoticeSink {
    override fun emitNotice(notice: ScanNotice) = Unit
}
