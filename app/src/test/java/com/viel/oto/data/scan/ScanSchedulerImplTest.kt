package com.viel.oto.data.scan

import com.viel.oto.data.cover.CoverRecoveryGateway
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.BookEntity
import com.viel.oto.data.entity.ScanSessionEntity
import com.viel.oto.event.AppEventSink
import com.viel.oto.event.AppShellEvent
import com.viel.oto.event.feedback.FeedbackDeliveryResult
import com.viel.oto.event.feedback.FeedbackFact
import com.viel.oto.library.scan.ScanOutcome
import com.viel.oto.library.scan.ScanOutcomeKind
import com.viel.oto.library.vfs.VfsFileInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Verifies the per-root fan-out scheduler: parallelism, the shared concurrency cap, same-root supersession without
 * requeue, cold-start yielding to user jobs, and outcome aggregation. The real ScanSession path is replaced through
 * the RootScanExecutor / coldStartRootIdsProvider seams so no Android scan infrastructure is exercised.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScanSchedulerImplTest {

    @Test
    fun `cold start fans out one scoped command per active root`() = runBlocking {
        val seen = Collections.synchronizedList(mutableListOf<Set<String>>())
        val scheduler = scheduler(
            coldRootIds = listOf("r1", "r2", "r3"),
            executor = { command ->
                seen += command.targetRootIds
                successOutcome()
            }
        )

        scheduler.syncLibrary("COLD_START")

        assertEquals(setOf(setOf("r1"), setOf("r2"), setOf("r3")), seen.toSet())
    }

    @Test
    fun `concurrent scans are capped at four`() = runBlocking {
        val current = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val scheduler = scheduler(
            coldRootIds = (1..8).map { "r$it" },
            executor = {
                val concurrent = current.incrementAndGet()
                peak.updateAndGet { observed -> max(observed, concurrent) }
                delay(50)
                current.decrementAndGet()
                successOutcome()
            }
        )

        scheduler.syncLibrary("COLD_START")

        assertEquals(4, peak.get())
    }

    @Test
    fun `aggregates per-root outcomes to the most severe kind`() = runBlocking {
        val scheduler = scheduler(
            coldRootIds = emptyList(),
            executor = { command ->
                when (command.targetRootIds.single()) {
                    "r1" -> ScanOutcome(ScanOutcomeKind.SUCCESS, feedback = null)
                    "r2" -> ScanOutcome(ScanOutcomeKind.PARTIAL, feedback = null)
                    else -> ScanOutcome(ScanOutcomeKind.SUCCESS, feedback = null)
                }
            }
        )

        val outcome = scheduler.syncLibrary("USER", setOf("r1", "r2", "r3"))

        assertEquals(ScanOutcomeKind.PARTIAL, outcome.kind)
    }

    @Test
    fun `user scan on a root cancels the in-flight scan for that root and does not requeue`() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Boolean>()
        val callCount = AtomicInteger(0)
        val scheduler = scheduler(
            coldRootIds = emptyList(),
            executor = {
                if (callCount.incrementAndGet() == 1) {
                    firstStarted.complete(Unit)
                    try {
                        releaseFirst.await()
                        successOutcome()
                    } catch (cancellation: kotlinx.coroutines.CancellationException) {
                        firstCancelled.complete(true)
                        throw cancellation
                    }
                } else {
                    successOutcome()
                }
            }
        )

        val firstScan = launch { runCatching { scheduler.syncLibrary("USER", setOf("r1")) } }
        firstStarted.await()

        val secondResult = scheduler.syncLibrary("USER", setOf("r1"))

        assertEquals(ScanOutcomeKind.SUCCESS, secondResult.kind)
        assertTrue(firstCancelled.await())
        assertEquals(2, callCount.get())
        firstScan.cancel()
    }

    @Test
    fun `cold start skips a root that has an in-flight user scan`() = runBlocking {
        val userStarted = CompletableDeferred<Unit>()
        val releaseUser = CompletableDeferred<Unit>()
        val coldTouchedUserRoot = AtomicBoolean(false)
        val scheduler = scheduler(
            coldRootIds = listOf("r1", "r2"),
            executor = { command ->
                val rootId = command.targetRootIds.single()
                if (command.trigger == AudiobookSchema.ScanTrigger.USER && rootId == "r1") {
                    userStarted.complete(Unit)
                    releaseUser.await()
                }
                if (command.trigger == AudiobookSchema.ScanTrigger.COLD_START && rootId == "r1") {
                    coldTouchedUserRoot.set(true)
                }
                successOutcome()
            }
        )

        val userScan = launch { scheduler.syncLibrary("USER", setOf("r1")) }
        userStarted.await()

        scheduler.syncLibrary("COLD_START")

        assertFalse(coldTouchedUserRoot.get())
        releaseUser.complete(Unit)
        userScan.join()
    }

    private fun scheduler(
        coldRootIds: List<String>,
        executor: RootScanExecutor
    ): ScanSchedulerImpl {
        val context = RuntimeEnvironment.getApplication()
        return ScanSchedulerImpl(
            context = context,
            coverRecoveryGateway = NoOpCoverRecoveryGateway,
            vfsFileInterface = VfsFileInterface(context),
            appEventSink = RecordingAppEventSink(),
            rootScanExecutor = executor,
            coldStartRootIdsProvider = { coldRootIds }
        )
    }

    private fun successOutcome(discovered: Int = 0): ScanOutcome =
        ScanOutcome(
            kind = ScanOutcomeKind.SUCCESS,
            feedback = null,
            session = ScanSessionEntity(
                id = "scan",
                trigger = AudiobookSchema.ScanTrigger.COLD_START,
                status = AudiobookSchema.ScanStatus.COMPLETED,
                discoveredBookCount = discovered
            )
        )

    private object NoOpCoverRecoveryGateway : CoverRecoveryGateway {
        override fun triggerRecovery(book: BookEntity) = Unit
        override suspend fun forceRegenerate(bookId: String): Boolean = false
        override suspend fun recoverMissingCovers() = Unit
    }

    private class RecordingAppEventSink : AppEventSink {
        private val _events = MutableSharedFlow<AppShellEvent>()
        override val events: SharedFlow<AppShellEvent> = _events
        val emitted = Collections.synchronizedList(mutableListOf<FeedbackFact>())
        override fun emitFeedback(fact: FeedbackFact): FeedbackDeliveryResult {
            emitted += fact
            return FeedbackDeliveryResult.Delivered(fact)
        }
    }
}
