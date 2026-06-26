package com.viel.oto.event.feedback

/**
 * Shared source for app-shell feedback rendering.
 *
 * Callers emit one stable message source plus formatting or routing arguments. Android resource
 * resolution and render-mode-specific rendering stay in the app shell, so the same message can be
 * consumed as either a Toast or a Dialog without duplicating producer facts.
 */
sealed interface FeedbackMessage {
    /**
     * Fallback rendering mode for message producers.
     *
     * Most feedback renders as Toast by default. Fact factories only override this when a specific
     * workflow requires strong in-app interaction.
     */
    val defaultRenderMode: FeedbackRenderMode
        get() = FeedbackRenderMode.TOAST

    /**
     * Defers localized text lookup to the rendering layer.
     *
     * The numeric key is intentionally opaque to this module. App-owned adapters decide whether the
     * key resolves to Android resources, test fixtures, or another presentation catalog.
     */
    data class Resource(
        val resId: Int,
        val args: List<Any> = emptyList()
    ) : FeedbackMessage

    /**
     * Defers counted localized text lookup to the rendering layer.
     *
     * The quantity drives plural selection while args supply the formatted values, keeping producers
     * on language-neutral facts and allowing locales with complex plural forms to render correctly.
     */
    data class Quantity(
        val resId: Int,
        val quantity: Int,
        val args: List<Any> = listOf(quantity)
    ) : FeedbackMessage

    /**
     * Combines localized fragments into one feedback fact.
     *
     * Scan summaries can keep each phrase resource-backed while still emitting one message source.
     */
    data class Composite(val parts: List<FeedbackMessage>) : FeedbackMessage

    /**
     * Identifies a failed queue item for Toast or Dialog rendering.
     *
     * Toast rendering resolves this to the short unavailable-track copy; Dialog rendering uses the same
     * payload to open the app-owned recovery confirmation.
     */
    data class PlaybackTrackUnavailable(
        val bookId: String,
        val queueIndex: Int,
        val bookTitle: String? = null
    ) : FeedbackMessage
}
