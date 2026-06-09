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
            getAllRootsOnce = {
                listOf(
                    sampleRoot(id = "fresh-abs"),
                    sampleRoot(id = "stale-abs"),
                    sampleRoot(id = "inactive-abs", status = AudiobookSchema.LibraryRootStatus.REVOKED),
                    sampleRoot(id = "local-root", sourceType = AudiobookSchema.LibrarySourceType.SAF)
                )
            },
            isAuthorizedProgressRefreshDue = { rootId, nowMillis ->
                // Startup Gate Fixture (Models root-level freshness without constructing ABS network dependencies)
                // The fixed clock proves the coordinator asks the freshness policy per active ABS root and schedules only stale results.
                assertEquals(10_000L, nowMillis)
                rootId == "stale-abs"
            },
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId },
            performColdStartSelfHealing = {}
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
            getAllRootsOnce = { listOf(sampleRoot(id = "fresh-abs")) },
            isAuthorizedProgressRefreshDue = { _, _ -> false },
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId },
            performColdStartSelfHealing = { selfHealCount += 1 }
        )

        warmup.run(nowMillis = 10_000L)

        // Self-Healing Independence (Keeps local progress repair decoupled from remote progress freshness)
        // Skipping ABS scheduling because the root is fresh must not skip the cold-start local recovery pass.
        assertEquals(emptyList<String>(), scheduledRootIds)
        assertEquals(1, selfHealCount)
    }

    private fun sampleRoot(
        id: String,
        sourceType: String = AudiobookSchema.LibrarySourceType.ABS,
        status: String = AudiobookSchema.LibraryRootStatus.ACTIVE
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
}
