package com.viel.aplayer.application.startup

import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class APlayerStartupWarmupTest {
    @Test
    fun `startup warmup should schedule only stale active abs roots`() = runBlocking {
        val scheduledRootIds = mutableListOf<String>()
        val warmup = APlayerStartupWarmup(
            dependencies = RecordingStartupWarmupDependencies(
                activeAbsRoots = listOf(
                    sampleRoot(id = "fresh-abs"),
                    sampleRoot(id = "stale-abs")
                ),
                refreshDue = { rootId, nowMillis ->
                    // Startup Gate Fixture (Models root-level freshness without constructing ABS network dependencies)
                    // The fixed clock proves the coordinator asks the freshness policy per active ABS root and schedules only stale results.
                    assertEquals(10_000L, nowMillis)
                    rootId == "stale-abs"
                }
            ),
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId }
        )

        val staleRootIds = warmup.warmUpAbsProgressIfStale(nowMillis = 10_000L)

        // Stale Root Scheduling Guard (Prevents cold start from refreshing every ABS root)
        // Fresh ABS roots, inactive ABS roots, and non-ABS roots must not enter the root-scoped WorkManager queue.
        assertEquals(listOf("stale-abs"), staleRootIds)
        assertEquals(listOf("stale-abs"), scheduledRootIds)
    }

    @Test
    fun `startup warmup should still self heal when abs progress is fresh`() = runBlocking {
        val scheduledRootIds = mutableListOf<String>()
        var selfHealCount = 0
        val warmup = APlayerStartupWarmup(
            dependencies = RecordingStartupWarmupDependencies(
                activeAbsRoots = listOf(sampleRoot(id = "fresh-abs")),
                refreshDue = { _, _ -> false },
                selfHeal = { selfHealCount += 1 }
            ),
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId }
        )

        warmup.run(nowMillis = 10_000L)

        // Self-Healing Independence (Keeps local progress repair decoupled from remote progress freshness)
        // Skipping ABS scheduling because the root is fresh must not skip the cold-start local recovery pass.
        assertEquals(emptyList<String>(), scheduledRootIds)
        assertEquals(1, selfHealCount)
    }

    @Test
    fun `startup warmup should not ask freshness policy when there are no active abs roots`() = runBlocking {
        var freshnessChecks = 0
        val warmup = APlayerStartupWarmup(
            dependencies = RecordingStartupWarmupDependencies(
                activeAbsRoots = emptyList(),
                refreshDue = { _, _ ->
                    freshnessChecks += 1
                    true
                }
            ),
            enqueueAbsRootSync = {}
        )

        val staleRootIds = warmup.warmUpAbsProgressIfStale(nowMillis = 10_000L)

        // Empty Root Gate (Keeps freshness policy adapters unresolved when no ABS root can be scheduled)
        // This guards the construction path that previously touched ABS synchronization even before stale roots existed.
        assertEquals(emptyList<String>(), staleRootIds)
        assertEquals(0, freshnessChecks)
    }

    // UpdateSampleRootHelper: Adapt helper function signature to accept type-safe enums instead of raw Strings.
    private fun sampleRoot(
        id: String,
        sourceType: AudiobookSchema.LibrarySourceType = AudiobookSchema.LibrarySourceType.ABS,
        status: AudiobookSchema.LibraryRootStatus = AudiobookSchema.LibraryRootStatus.ACTIVE
    ): LibraryRootEntity =
        LibraryRootEntity(
            id = id,
            sourceType = sourceType,
            sourceUri = "https://example.com/$id",
            basePath = "library-$id",
            credentialId = "credential-$id",
            displayName = id,
            status = status
        )

    private class RecordingStartupWarmupDependencies(
        private val activeAbsRoots: List<LibraryRootEntity>,
        private val refreshDue: suspend (rootId: String, nowMillis: Long) -> Boolean = { _, _ -> false },
        private val selfHeal: suspend () -> Unit = {}
    ) : StartupWarmupDependencies {
        override suspend fun activeAbsRoots(): List<LibraryRootEntity> =
            activeAbsRoots

        override suspend fun isAuthorizedProgressRefreshDue(rootId: String, nowMillis: Long): Boolean =
            refreshDue(rootId, nowMillis)

        override suspend fun performColdStartSelfHealing() {
            // Startup Dependencies Fixture (Records only the commands each test cares about)
            // Keeping the fake inside this test prevents coordinator behavior from depending on production di adapters.
            selfHeal()
        }
    }
}
