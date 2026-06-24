package com.viel.oto.application.startup

import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.LibraryRootEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OtoStartupWarmupTest {
    @Test
    fun `startup warmup should schedule only stale active abs roots`() = runBlocking {
        val scheduledRootIds = mutableListOf<String>()
        val warmup = OtoStartupWarmup(
            dependencies = RecordingStartupWarmupDependencies(
                activeAbsRoots = listOf(
                    sampleRoot(id = "fresh-abs"),
                    sampleRoot(id = "stale-abs")
                ),
                refreshDue = { rootId, nowMillis ->
                    assertEquals(10_000L, nowMillis)
                    rootId == "stale-abs"
                }
            ),
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId }
        )

        val staleRootIds = warmup.warmUpAbsProgressIfStale(nowMillis = 10_000L)

        assertEquals(listOf("stale-abs"), staleRootIds)
        assertEquals(listOf("stale-abs"), scheduledRootIds)
    }

    @Test
    fun `startup warmup should still self heal when abs progress is fresh`() = runBlocking {
        val scheduledRootIds = mutableListOf<String>()
        var selfHealCount = 0
        val warmup = OtoStartupWarmup(
            dependencies = RecordingStartupWarmupDependencies(
                activeAbsRoots = listOf(sampleRoot(id = "fresh-abs")),
                refreshDue = { _, _ -> false },
                selfHeal = { selfHealCount += 1 }
            ),
            enqueueAbsRootSync = { rootId -> scheduledRootIds += rootId }
        )

        warmup.run(nowMillis = 10_000L)

        assertEquals(emptyList<String>(), scheduledRootIds)
        assertEquals(1, selfHealCount)
    }

    @Test
    fun `startup warmup should not ask freshness policy when there are no active abs roots`() = runBlocking {
        var freshnessChecks = 0
        val warmup = OtoStartupWarmup(
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

        assertEquals(emptyList<String>(), staleRootIds)
        assertEquals(0, freshnessChecks)
    }

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
            selfHeal()
        }
    }
}
