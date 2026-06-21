package com.viel.aplayer.event.feedback

import com.viel.aplayer.data.db.AudiobookSchema

/**
 * Command-owner fact factory for library access outcomes.
 *
 * Library access feedback follows the user task as its topic: connection testing, library root changes,
 * sync, and rescan, while the access form and the specific root or draft stay as context so one access
 * form or root never absorbs another. Audiobookshelf sync feedback (manual and background) and the ABS
 * remote-progress save failure all classify as [FeedbackTopic.LibrarySync]; the remote-progress failure
 * stays keyed to the book instead of the server root. Display names, paths, URLs, tokens, and credentials
 * never enter the aggregation identity. Resource keys are unchanged from the previous inline messages.
 */
object LibraryAccessFeedbackFacts {


    /** A draft WebDAV configuration passed its connection test. */
    fun webDavConnectionSucceeded(draftId: String): FeedbackFact =
        connectionTestFact(
            message = FeedbackMessages.settingsWebDavConnectionSucceeded(),
            draftId = draftId,
            accessForm = LibraryAccessForm.WEBDAV,
            severity = FeedbackSeverity.COMPLETED
        )

    /** A draft WebDAV configuration failed its connection test. */
    fun webDavConnectionFailed(draftId: String, friendlyMessage: String): FeedbackFact =
        connectionTestFact(
            message = FeedbackMessages.settingsWebDavConnectionFailed(friendlyMessage),
            draftId = draftId,
            accessForm = LibraryAccessForm.WEBDAV,
            severity = FeedbackSeverity.FAILED
        )

    /** A draft Audiobookshelf configuration reached the server. */
    fun absConnectionSucceeded(draftId: String, libraryCount: Int): FeedbackFact =
        connectionTestFact(
            message = FeedbackMessages.settingsAbsConnectionSucceeded(libraryCount),
            draftId = draftId,
            accessForm = LibraryAccessForm.AUDIOBOOKSHELF,
            severity = FeedbackSeverity.COMPLETED
        )

    /** A draft Audiobookshelf configuration failed to reach the server. */
    fun absConnectionFailed(draftId: String, friendlyMessage: String): FeedbackFact =
        connectionTestFact(
            message = FeedbackMessages.settingsAbsConnectionFailed(friendlyMessage),
            draftId = draftId,
            accessForm = LibraryAccessForm.AUDIOBOOKSHELF,
            severity = FeedbackSeverity.FAILED
        )


    /** A SAF root was repointed to a new tree. */
    fun localLibraryRelocated(rootId: String): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsLocalLibraryRelocated(),
            context = libraryRootContext(rootId, LibraryAccessForm.LOCAL_FOLDER),
            severity = FeedbackSeverity.COMPLETED
        )

    /** Repointing a SAF root failed. */
    fun localLibraryRelocationFailed(rootId: String, errorMessage: String?): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsLocalLibraryRelocationFailed(errorMessage),
            context = libraryRootContext(rootId, LibraryAccessForm.LOCAL_FOLDER),
            severity = FeedbackSeverity.FAILED
        )

    /** An existing WebDAV root's configuration was saved. */
    fun webDavRootUpdated(rootId: String): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsWebDavUpdated(),
            context = libraryRootContext(rootId, LibraryAccessForm.WEBDAV),
            severity = FeedbackSeverity.COMPLETED
        )

    /** Saving an existing WebDAV root's configuration failed. */
    fun webDavRootUpdateFailed(rootId: String, errorMessage: String?): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsWebDavUpdateFailed(errorMessage),
            context = libraryRootContext(rootId, LibraryAccessForm.WEBDAV),
            severity = FeedbackSeverity.FAILED
        )

    /** A new or edited Audiobookshelf root was persisted. */
    fun absServerSaved(rootId: String, editing: Boolean): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsAbsServerSaved(editing = editing),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.COMPLETED
        )

    /**
     * Persisting an Audiobookshelf root failed.
     *
     * The failure branch has no persisted root id yet, so it uses the explicit missing-object context.
     */
    fun absServerSaveFailed(redactedMessage: String): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsAbsServerSaveFailed(redactedMessage),
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.FAILED
        )

    /** A registered root was deregistered from the library. */
    fun rootRemoved(
        rootId: String,
        sourceType: AudiobookSchema.LibrarySourceType,
        playbackWasStopped: Boolean
    ): FeedbackFact =
        rootChangeFact(
            message = FeedbackMessages.settingsLibraryRootRemoved(playbackWasStopped),
            context = libraryRootContext(rootId, accessFormOf(sourceType)),
            severity = FeedbackSeverity.COMPLETED
        )


    /** A manual Audiobookshelf catalog sync was scheduled. */
    fun syncStarted(rootId: String): FeedbackFact =
        syncFact(
            message = FeedbackMessages.settingsAbsSyncStarted(),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.STARTED
        )

    /** A sync for this root is already in progress; nothing changed. */
    fun syncAlreadyRunning(rootId: String): FeedbackFact =
        syncFact(
            message = FeedbackMessages.settingsAbsSyncAlreadyRunning(),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.HINT
        )

    /** The Audiobookshelf root for the requested sync no longer exists. */
    fun syncRootMissing(): FeedbackFact =
        syncFact(
            message = FeedbackMessages.absBackgroundSyncRootMissing(),
            context = FeedbackContext.MissingObject,
            severity = FeedbackSeverity.BLOCKED
        )

    /** The Audiobookshelf root is unavailable, so sync was skipped before any request. */
    fun syncBlocked(rootId: String, detailMessage: FeedbackMessage): FeedbackFact =
        syncFact(
            message = FeedbackMessages.absBackgroundSyncUnavailable(detailMessage),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.BLOCKED
        )

    /** A background Audiobookshelf catalog sync finished. */
    fun syncCompleted(rootId: String, addedBooks: Int, failedItems: Int): FeedbackFact =
        syncFact(
            message = FeedbackMessages.absBackgroundSyncCompleted(addedBooks, failedItems),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.COMPLETED
        )

    /** A background Audiobookshelf catalog sync failed. */
    fun syncFailed(rootId: String, redactedMessage: String): FeedbackFact =
        syncFact(
            message = FeedbackMessages.absBackgroundSyncFailed(redactedMessage),
            context = libraryRootContext(rootId, LibraryAccessForm.AUDIOBOOKSHELF),
            severity = FeedbackSeverity.FAILED
        )

    /**
     * Saving accepted remote ABS progress for a book failed.
     *
     * This is an Audiobookshelf sync result rather than a playback-content recovery, so it shares the
     * sync topic but stays keyed to the book; different books never absorb each other, and it never
     * merges with root-bound background sync feedback. The redacted error is a rendering argument only.
     */
    fun remoteProgressSaveFailed(bookId: String, redactedMessage: String?): FeedbackFact =
        FeedbackFact(
            message = FeedbackMessages.playbackRemoteProgressSaveFailed(redactedMessage),
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.LIBRARY_ACCESS,
                    topic = FeedbackTopic.LibrarySync,
                    context = FeedbackContext.Book(bookId)
                ),
                severity = FeedbackSeverity.FAILED,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )


    /** A library scan finished; the policy owns the composed summary message. */
    fun rescanCompleted(message: FeedbackMessage, context: FeedbackContext): FeedbackFact =
        rescanFact(message, context, FeedbackSeverity.COMPLETED)

    /** No reachable directory root was available to scan. */
    fun rescanBlocked(message: FeedbackMessage, context: FeedbackContext): FeedbackFact =
        rescanFact(message, context, FeedbackSeverity.BLOCKED)

    /** A library scan failed; transient retries are not surfaced as failures here. */
    fun rescanFailed(message: FeedbackMessage): FeedbackFact =
        rescanFact(message, FeedbackContext.Global, FeedbackSeverity.FAILED)


    /**
     * Builds a stable, non-sensitive root identity for aggregation.
     *
     * Exposed so the scan policy can key a single skipped root without duplicating the source-type to
     * access-form mapping.
     */
    fun libraryRootContext(rootId: String, accessForm: LibraryAccessForm): FeedbackContext.LibraryRoot =
        FeedbackContext.LibraryRoot(rootId, accessForm)

    /** Maps a persisted source type to the user-visible access form. */
    fun accessFormOf(sourceType: AudiobookSchema.LibrarySourceType): LibraryAccessForm =
        when (sourceType) {
            AudiobookSchema.LibrarySourceType.SAF -> LibraryAccessForm.LOCAL_FOLDER
            AudiobookSchema.LibrarySourceType.WEBDAV -> LibraryAccessForm.WEBDAV
            AudiobookSchema.LibrarySourceType.ABS -> LibraryAccessForm.AUDIOBOOKSHELF
        }

    private fun connectionTestFact(
        message: FeedbackMessage,
        draftId: String,
        accessForm: LibraryAccessForm,
        severity: FeedbackSeverity
    ): FeedbackFact =
        libraryAccessFact(
            message = message,
            topic = FeedbackTopic.ConnectionTesting,
            context = FeedbackContext.DraftLibraryAccess(draftId, accessForm),
            severity = severity
        )

    private fun rootChangeFact(
        message: FeedbackMessage,
        context: FeedbackContext,
        severity: FeedbackSeverity
    ): FeedbackFact =
        libraryAccessFact(message, FeedbackTopic.LibraryRootChange, context, severity)

    private fun syncFact(
        message: FeedbackMessage,
        context: FeedbackContext,
        severity: FeedbackSeverity
    ): FeedbackFact =
        libraryAccessFact(message, FeedbackTopic.LibrarySync, context, severity)

    private fun rescanFact(
        message: FeedbackMessage,
        context: FeedbackContext,
        severity: FeedbackSeverity
    ): FeedbackFact =
        libraryAccessFact(message, FeedbackTopic.Rescan, context, severity)

    private fun libraryAccessFact(
        message: FeedbackMessage,
        topic: FeedbackTopic,
        context: FeedbackContext,
        severity: FeedbackSeverity
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.LIBRARY_ACCESS,
                    topic = topic,
                    context = context
                ),
                severity = severity,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )
}
