package com.viel.oto.event

import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.event.feedback.FeedbackRenderMode

/**
 * One-shot feedback render requests consumed by the top-level app shell.
 *
 * This event model lives in the application event package because it represents rendering decisions
 * owned by `OtoApp`, not feature-local ViewModel events or media-core domain facts.
 */
sealed interface AppShellEvent {
    /**
     * Requests one app-shell rendering mode for one feedback fact.
     *
     * Callers provide a shared feedback fact plus the mutually exclusive render mode selected by the
     * event sink. Toast and Dialog are consumers of the same message source rather than separate event
     * families.
     *
     * @param fact The feedback fact (renderable message plus aggregation outcome).
     * @param renderMode The app-shell consumer that should render this fact.
     */
    data class RenderFeedback(
        val fact: FeedbackFact,
        val renderMode: FeedbackRenderMode
    ) : AppShellEvent
}
