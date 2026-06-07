package com.viel.aplayer.ui.navigation.shell

import android.content.Context
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import com.viel.aplayer.event.AppShellEvent
import com.viel.aplayer.event.feedback.FeedbackMessage
import com.viel.aplayer.event.feedback.render

/**
 * App Feedback Renderer (Centralizes app-shell feedback event mapping)
 *
 * Converts process-wide shell events into presentation commands so APlayerApp only collects the
 * event stream and delegates the rendering rule.
 */
interface AppFeedbackRenderer {
    fun render(event: AppShellEvent): AppFeedbackCommand
}

/**
 * App Feedback Command (Represents the concrete presentation effect requested by a shell event)
 *
 * Keeps the mapping testable without requiring Android Toast or PlayerViewModel instances in unit
 * tests.
 */
sealed interface AppFeedbackCommand {
    /**
     * Toast Command (Carries resource-backed transient feedback to the Android renderer)
     *
     * The message remains unresolved until the command is dispatched with a Context.
     */
    data class ToastMessage(val message: FeedbackMessage) : AppFeedbackCommand

    /**
     * Track Unavailable Dialog Command (Forwards playback recovery dialog parameters)
     *
     * The command preserves identifiers while leaving the actual state mutation in PlayerViewModel.
     */
    data class TrackUnavailableDialog(val bookId: String, val queueIndex: Int) : AppFeedbackCommand
}

/**
 * Default App Feedback Renderer (Defines the production shell event mapping)
 *
 * This small object fixes the event-to-command rules in one place before any Android rendering
 * side-effect occurs.
 */
object DefaultAppFeedbackRenderer : AppFeedbackRenderer {
    override fun render(event: AppShellEvent): AppFeedbackCommand =
        when (event) {
            is AppShellEvent.ShowToast -> AppFeedbackCommand.ToastMessage(event.message)
            is AppShellEvent.ShowTrackUnavailableDialog -> AppFeedbackCommand.TrackUnavailableDialog(
                bookId = event.bookId,
                queueIndex = event.queueIndex
            )
        }
}

/**
 * Dispatch App Feedback Command (Runs Android or ViewModel side effects from resolved commands)
 *
 * APlayerApp supplies the Context and dialog callback, while this helper owns Toast construction and
 * text alignment.
 */
fun AppFeedbackCommand.dispatch(
    context: Context,
    onTrackUnavailableDialog: (bookId: String, queueIndex: Int) -> Unit
) {
    when (this) {
        is AppFeedbackCommand.ToastMessage -> showCenteredToast(context, message)
        is AppFeedbackCommand.TrackUnavailableDialog -> onTrackUnavailableDialog(bookId, queueIndex)
    }
}

/**
 * Centered Toast Rendering (Keeps transient feedback formatting consistent)
 *
 * Resource-backed messages are localized at the app-shell edge and then centered before Android
 * Toast display.
 */
private fun showCenteredToast(context: Context, message: FeedbackMessage) {
    val renderedMessage = message.render(context)
    val spannable = SpannableString(renderedMessage)
    spannable.setSpan(
        AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),
        0,
        renderedMessage.length,
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
    )
    Toast.makeText(context, spannable, Toast.LENGTH_SHORT).show()
}
