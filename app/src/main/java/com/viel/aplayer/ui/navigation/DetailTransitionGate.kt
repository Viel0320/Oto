package com.viel.aplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.viel.aplayer.application.library.detail.DetailBookItem
import com.viel.aplayer.ui.detail.DetailEntrySource
import com.viel.aplayer.ui.detail.DetailViewModel

/*
 * Detail Open Request (Navigation-level shared-element handoff command)
 *
 * Carries the already mapped Detail scene item and its source surface so the app shell can
 * queue rapid re-entry without letting Home or Search mutate DetailViewModel directly.
 */
data class DetailOpenRequest(
    val book: DetailBookItem?,
    val entrySource: DetailEntrySource
)

/*
 * Detail Transition Gate (Protect shared-element return chains)
 *
 * Queues a new Detail open request while the existing DetailOverlay is still entering or exiting,
 * then replays the latest request once the overlay reports an idle transition state.
 */
class DetailTransitionGate(
    private val detailViewModel: DetailViewModel
) {
    private var isTransitionIdle = true
    private var pendingOpenRequest: PendingDetailOpenRequest? = null

    /*
     * Request Detail Open (Gate rapid overlay retargeting)
     *
     * Opens immediately only when DetailOverlay is idle; otherwise stores the newest request so
     * the previous shared-element return path can complete before the next Detail target appears.
     */
    fun requestOpen(
        request: DetailOpenRequest,
        beforeOpen: () -> Unit = {}
    ) {
        val pendingRequest = PendingDetailOpenRequest(
            request = request,
            beforeOpen = beforeOpen
        )
        if (isTransitionIdle) {
            openNow(pendingRequest)
        } else {
            pendingOpenRequest = pendingRequest
        }
    }

    /*
     * Transition Idle Update (Flush queued request after visual completion)
     *
     * Receives DetailOverlay lifecycle updates and replays a queued open once the overlay becomes
     * idle, preserving the old source-to-detail return animation before starting the next entry.
     */
    fun onTransitionIdleChanged(isIdle: Boolean) {
        isTransitionIdle = isIdle
        if (isIdle) {
            pendingOpenRequest?.let { pendingRequest ->
                pendingOpenRequest = null
                openNow(pendingRequest)
            }
        }
    }

    private fun openNow(pendingRequest: PendingDetailOpenRequest) {
        val request = pendingRequest.request
        val startsOverlayTransition = request.book != null && !detailViewModel.uiState.value.isVisible
        pendingRequest.beforeOpen()
        detailViewModel.selectBook(
            book = request.book,
            entrySource = request.entrySource
        )
        if (startsOverlayTransition) {
            /*
             * Immediate Open Busy Mark (Close the same-frame retarget window)
             *
             * Opening Detail from a hidden state starts an overlay enter transition, but the
             * animation callback arrives after recomposition; marking busy here prevents a
             * rapid second tap from retargeting Detail before the overlay reports motion.
             */
            isTransitionIdle = false
        }
    }

    /*
     * Pending Detail Open Request (Retain source-side effects until the real open)
     *
     * Stores the selected detail target together with source cleanup work, such as closing
     * Search, so queued opens keep their shared-element source composed until the handoff starts.
     */
    private data class PendingDetailOpenRequest(
        val request: DetailOpenRequest,
        val beforeOpen: () -> Unit
    )
}

/*
 * Remember Detail Transition Gate (Compose lifecycle factory)
 *
 * Keeps one gate instance beside the app shell's DetailViewModel and clears any queued request
 * when the ViewModel instance changes, which matches Activity-scoped navigation ownership.
 */
@Composable
fun rememberDetailTransitionGate(
    detailViewModel: DetailViewModel
): DetailTransitionGate {
    return remember(detailViewModel) {
        DetailTransitionGate(detailViewModel)
    }
}
