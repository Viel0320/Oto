package com.viel.aplayer.event.feedback

/**
 * Command-owner fact factory for portable app-data export and import.
 *
 * Data transfer is a peer top-level category whose feedback applies to the whole app, so every outcome
 * uses [FeedbackContext.Global] and the export/import topics keep the two user tasks from absorbing each
 * other. Export and import are global single-shot tasks rather than per-book or per-root work; see
 * ADR 0003 for why this stays separate from library access. Resource keys are unchanged from the previous
 * inline SettingsViewModel messages.
 */
object DataTransferFeedbackFacts {

    /** The portable backup file was written successfully. */
    fun exportSucceeded(): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsExportSuccess(),
            topic = FeedbackTopic.DataExport,
            severity = FeedbackSeverity.COMPLETED
        )

    /** The destination output stream could not be opened. */
    fun exportStreamFailed(): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsExportStreamFailed(),
            topic = FeedbackTopic.DataExport,
            severity = FeedbackSeverity.FAILED
        )

    /** The export task failed while packaging app data. */
    fun exportFailed(errorMessage: String?): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsExportFailed(errorMessage),
            topic = FeedbackTopic.DataExport,
            severity = FeedbackSeverity.FAILED
        )

    /** The selected backup file could not be opened for reading. */
    fun importStreamFailed(): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsImportStreamFailed(),
            topic = FeedbackTopic.DataImport,
            severity = FeedbackSeverity.FAILED
        )

    /**
     * The backup came from a newer database schema.
     *
     * The version numbers are rendering arguments only and never enter the aggregation identity.
     */
    fun importVersionIncompatible(backupVersion: Int, currentVersion: Int): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsImportVersionIncompatible(backupVersion, currentVersion),
            topic = FeedbackTopic.DataImport,
            severity = FeedbackSeverity.FAILED
        )

    /** The import task failed while restoring app data. */
    fun importFailed(errorMessage: String?): FeedbackFact =
        dataTransferFact(
            message = FeedbackMessages.settingsImportFailed(errorMessage),
            topic = FeedbackTopic.DataImport,
            severity = FeedbackSeverity.FAILED
        )

    private fun dataTransferFact(
        message: FeedbackMessage,
        topic: FeedbackTopic,
        severity: FeedbackSeverity
    ): FeedbackFact =
        FeedbackFact(
            message = message,
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.DATA_TRANSFER,
                    topic = topic,
                    context = FeedbackContext.Global
                ),
                severity = severity,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )
}
