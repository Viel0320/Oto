package com.viel.oto.event.feedback

/**
 * Command-owner fact factory for home-screen widget integration.
 *
 * Pinning the playback widget is an app-wide device-integration task rather than per-book or per-root
 * work, so every outcome uses [FeedbackContext.Global] under the dedicated
 * [FeedbackCategory.DEVICE_INTEGRATION] family. The success path is handled by the system pin dialog, so
 * only the unsupported-launcher failure surfaces app feedback.
 */
object WidgetFeedbackFacts {

    /** The launcher does not support programmatic widget pinning, so the user must add it manually. */
    fun pinUnsupported(): FeedbackFact =
        FeedbackFact(
            message = FeedbackMessages.widgetPinUnsupported(),
            outcome = FeedbackOutcome(
                identity = FeedbackAggregationIdentity(
                    category = FeedbackCategory.DEVICE_INTEGRATION,
                    topic = FeedbackTopic.HomeScreenWidget,
                    context = FeedbackContext.Global
                ),
                severity = FeedbackSeverity.FAILED,
                lifecycle = FeedbackLifecycle.FINAL
            )
        )
}
