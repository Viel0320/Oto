package com.viel.oto.ui.navigation.shell

import android.content.Context
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.widget.Toast
import com.viel.oto.event.AppShellEvent
import com.viel.oto.event.feedback.FeedbackMessage
import com.viel.oto.event.feedback.FeedbackRenderMode
import com.viel.oto.event.feedback.render

/**
 * Centralizes app-shell feedback render-mode mapping.
 *
 * Converts process-wide shell events into concrete render requests so OtoApp only collects the
 * event stream and delegates the consumer selection rule.
 */
interface AppFeedbackRenderRouter {
    fun route(event: AppShellEvent): AppFeedbackRenderRequest
}

/**
 * Represents the concrete consumer requested by a shell event.
 *
 * Keeps the mapping testable without requiring Android Toast or PlayerViewModel instances in unit
 * tests.
 */
sealed interface AppFeedbackRenderRequest {
    /**
     * Carries resource-backed feedback to the Android Toast renderer.
     *
     * The message remains unresolved until the command is dispatched with a Context.
     */
    data class Toast(val message: FeedbackMessage) : AppFeedbackRenderRequest

    /**
     * Carries a strong-interaction feedback message to the shell dispatcher.
     *
     * Dialog requests keep routing data render-mode-independent until dispatch can call the feature owner
     * that already manages the corresponding dialog state.
     */
    data class Dialog(val message: FeedbackMessage) : AppFeedbackRenderRequest
}

/**
 * Defines the production shell event mapping.
 *
 * This small object fixes the event-to-render-request rules in one place before any Android rendering
 * side-effect occurs.
 */
object DefaultAppFeedbackRenderRouter : AppFeedbackRenderRouter {
    override fun route(event: AppShellEvent): AppFeedbackRenderRequest =
        when (event) {
            is AppShellEvent.RenderFeedback -> when (event.renderMode) {
                FeedbackRenderMode.TOAST -> AppFeedbackRenderRequest.Toast(event.fact.message)
                FeedbackRenderMode.DIALOG -> AppFeedbackRenderRequest.Dialog(event.fact.message)
            }
        }
}

/**
 * Runs Android or ViewModel side effects from resolved requests.
 *
 * OtoApp supplies the Context and dialog callback, while this helper owns Toast construction and
 * text alignment.
 */
fun AppFeedbackRenderRequest.dispatch(
    context: Context,
    onTrackUnavailableDialog: (bookId: String, queueIndex: Int, bookTitle: String?) -> Unit,
    onGenericDialog: (FeedbackMessage) -> Unit
) {
    when (this) {
        is AppFeedbackRenderRequest.Toast -> showCenteredToast(context, message)
        is AppFeedbackRenderRequest.Dialog -> dispatchDialog(
            message = message,
            onTrackUnavailableDialog = onTrackUnavailableDialog,
            onGenericDialog = onGenericDialog
        )
    }
}

/**
 * Routes strong-interaction messages to their feature owner.
 *
 * The app shell keeps dialog rendering state in ViewModels and dialog hosts, while the message factory
 * supplies the typed payload that selects the correct app-owned interaction.
 */
private fun dispatchDialog(
    message: FeedbackMessage,
    onTrackUnavailableDialog: (bookId: String, queueIndex: Int, bookTitle: String?) -> Unit,
    onGenericDialog: (FeedbackMessage) -> Unit
) {
    when (message) {
        is FeedbackMessage.PlaybackTrackUnavailable ->
            onTrackUnavailableDialog(message.bookId, message.queueIndex, message.bookTitle)
        else -> onGenericDialog(message)
    }
}

/**
 * Keeps Toast-mode feedback formatting consistent.
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
