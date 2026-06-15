package com.viel.aplayer.application.library.settings

import android.net.Uri
import com.viel.aplayer.abs.sync.AbsSyncPlan
import com.viel.aplayer.abs.sync.AbsSyncTaskOrigin
import com.viel.aplayer.application.usecase.LibraryRootSettingsSnapshot
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.LibraryRootEntity
import com.viel.aplayer.data.gateway.LibraryRootGateway
import com.viel.aplayer.data.gateway.ScanScheduler
import com.viel.aplayer.library.availability.AvailabilityResult
import com.viel.aplayer.library.availability.LibraryRootAvailabilityUpdate
import com.viel.aplayer.library.scan.ScanOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Settings Root Module Test (Locks settings-scene root delegation)
 * Verifies root snapshots, root registration triggers, status refreshes, and scan scheduling without touching SettingsViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsRootModuleTest {

    @Test
    fun rootSnapshotsAreExposedFromTheReadSource() = runBlocking {
        val snapshot = LibraryRootSettingsSnapshot(
            rootId = ROOT_ID,
            sourceType = AudiobookSchema.LibrarySourceType.SAF,
            sourceUri = "content://library/root",
            basePath = "",
            credentialId = null,
            displayName = "Library",
            status = AudiobookSchema.LibraryRootStatus.ACTIVE,
            availabilityStatus = AudiobookSchema.AvailabilityStatus.UNKNOWN,
            lastScannedAt = 0L,
            absLastError = null,
            absLastFullSyncAt = null,
            importedBookCount = 7
        )
        val module = moduleFor(rootSnapshots = listOf(snapshot))

        assertEquals(listOf(snapshot), module.observeRootSnapshots().first())
    }

    @Test
    fun localRootRegistrationUsesUserSyncTrigger() {
        val gateway = FakeLibraryRootGateway()
        val module = moduleFor(rootGateway = gateway)
        val uri = Uri.parse("content://library/root")

        module.addLocalRootAndScheduleSync(uri)

        assertEquals(listOf(LocalRootCall(uri = "content://library/root", trigger = "USER")), gateway.localRootCalls)
    }

    @Test
    fun webDavRootRegistrationPassesParametersAndUserTrigger() {
        val gateway = FakeLibraryRootGateway()
        val module = moduleFor(rootGateway = gateway)

        module.addWebDavRootAndScheduleSync(
            url = "https://dav.example.test",
            username = "reader",
            password = "secret",
            displayName = "Remote Library",
            basePath = "/audio"
        )

        assertEquals(
            listOf(
                WebDavRootCall(
                    url = "https://dav.example.test",
                    username = "reader",
                    password = "secret",
                    displayName = "Remote Library",
                    basePath = "/audio",
                    trigger = "USER"
                )
            ),
            gateway.webDavRootCalls
        )
    }

    @Test
    fun refreshAllRootStatusesDelegatesToTheRootGateway() = runBlocking {
        val gateway = FakeLibraryRootGateway()
        val module = moduleFor(rootGateway = gateway)

        module.refreshAllRootStatuses()

        assertEquals(1, gateway.refreshAllCount)
    }

    @Test
    fun inspectManualAbsSyncReturnsReadyPlanAfterAvailablePreflight() = runBlocking {
        val update = LibraryRootAvailabilityUpdate(
            root = root(sourceType = AudiobookSchema.LibrarySourceType.ABS, displayName = "ABS Library"),
            availability = AvailabilityResult(status = AudiobookSchema.AvailabilityStatus.AVAILABLE)
        )
        val gateway = FakeLibraryRootGateway(preflightByRootId = mapOf(ROOT_ID to update))
        val inspectedRootIds = mutableListOf<String>()
        val module = moduleFor(
            rootGateway = gateway,
            inspectAbsSyncPlan = { root ->
                // ABS Plan Fake (Record the rehydrated root passed to the application adapter)
                // The test verifies SettingsRootModule keeps entity access internal while returning a Room-free inspection result.
                inspectedRootIds += root.id
                AbsSyncPlan(totalItems = 42, batchSize = 20, requiresConfirmation = true)
            }
        )

        val result = module.inspectManualAbsSync(ROOT_ID)

        assertEquals(
            SettingsAbsSyncInspection.Ready(
                rootId = ROOT_ID,
                displayName = "ABS Library",
                totalItems = 42,
                requiresConfirmation = true
            ),
            result
        )
        assertEquals(listOf(ROOT_ID), gateway.refreshedRootIds)
        assertEquals(listOf(ROOT_ID), inspectedRootIds)
    }

    @Test
    fun startAbsSyncUsesSceneSpecificOrigins() {
        val startedTasks = mutableListOf<StartedAbsTask>()
        val module = moduleFor(
            startAbsSyncTask = { rootId, origin ->
                // ABS Task Fake (Capture rootId and origin without starting background work)
                // This locks the settings command interface to rootId-only scheduling while preserving manual and auto-add origin semantics.
                startedTasks += StartedAbsTask(rootId = rootId, origin = origin)
                true
            }
        )

        assertEquals(true, module.startManualAbsSync(ROOT_ID))
        assertEquals(true, module.startAutoAbsSync(ROOT_ID))

        assertEquals(
            listOf(
                StartedAbsTask(rootId = ROOT_ID, origin = AbsSyncTaskOrigin.MANUAL),
                StartedAbsTask(rootId = ROOT_ID, origin = AbsSyncTaskOrigin.AUTO_ADD)
            ),
            startedTasks
        )
    }

    @Test
    fun scheduleUserSyncDelegatesToTheScanScheduler() {
        val scheduler = FakeScanScheduler()
        val module = moduleFor(scanScheduler = scheduler)

        module.scheduleUserSync()

        assertEquals(listOf(ScheduledScan(trigger = "USER", requiresNetwork = false)), scheduler.scheduledScans)
    }

    private fun moduleFor(
        rootSnapshots: List<LibraryRootSettingsSnapshot> = emptyList(),
        rootGateway: FakeLibraryRootGateway = FakeLibraryRootGateway(),
        scanScheduler: FakeScanScheduler = FakeScanScheduler(),
        inspectAbsSyncPlan: suspend (LibraryRootEntity) -> AbsSyncPlan = { unexpected("inspectAbsSyncPlan") },
        startAbsSyncTask: (String, AbsSyncTaskOrigin) -> Boolean = { _, _ -> unexpected("startAbsSyncTask") }
    ): DefaultSettingsRootModule {
        // Settings Root Module Fixture (Supplies fake source streams and gateway adapters)
        // The fakes record only settings-root calls so the test fails fast if the module reaches outside this scene surface.
        return DefaultSettingsRootModule(
            observeRootSnapshotsSource = { flowOf(rootSnapshots) },
            libraryRootGateway = rootGateway,
            scanScheduler = scanScheduler,
            inspectAbsSyncPlan = inspectAbsSyncPlan,
            startAbsSyncTask = startAbsSyncTask
        )
    }

    private class FakeLibraryRootGateway(
        private val preflightByRootId: Map<String, LibraryRootAvailabilityUpdate?> = emptyMap()
    ) : LibraryRootGateway {
        val localRootCalls = mutableListOf<LocalRootCall>()
        val webDavRootCalls = mutableListOf<WebDavRootCall>()
        val refreshedRootIds = mutableListOf<String>()
        var refreshAllCount = 0

        override fun observeLibraryRoots(): Flow<List<LibraryRootEntity>> = unexpected("observeLibraryRoots")
        override fun getCachedLibraryRoots(): List<LibraryRootEntity> = unexpected("getCachedLibraryRoots")
        override suspend fun getAllRootsOnce(): List<LibraryRootEntity> = unexpected("getAllRootsOnce")
        override suspend fun setLibraryRoot(uri: Uri): LibraryRootEntity = unexpected("setLibraryRoot")
        override suspend fun addWebDavLibraryRoot(url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("addWebDavLibraryRoot")
        override suspend fun addAbsLibraryRoot(credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("addAbsLibraryRoot")

        override fun addLibraryRootAndScheduleSync(uri: Uri, trigger: String) {
            localRootCalls += LocalRootCall(uri = uri.toString(), trigger = trigger)
        }

        override fun addWebDavLibraryRootAndScheduleSync(
            url: String,
            username: String,
            password: String,
            displayName: String,
            basePath: String,
            trigger: String
        ) {
            webDavRootCalls += WebDavRootCall(
                url = url,
                username = username,
                password = password,
                displayName = displayName,
                basePath = basePath,
                trigger = trigger
            )
        }

        override suspend fun refreshLibraryRootStatuses() {
            refreshAllCount += 1
        }

        override suspend fun refreshLibraryRootStatus(rootId: String): LibraryRootAvailabilityUpdate? {
            refreshedRootIds += rootId
            return preflightByRootId[rootId]
        }

        override suspend fun deleteLibraryRootDataOnly(root: LibraryRootEntity): Unit = unexpected("deleteLibraryRootDataOnly")
        override suspend fun updateSafLibraryRoot(id: String, newUri: Uri): LibraryRootEntity = unexpected("updateSafLibraryRoot")
        override suspend fun updateWebDavLibraryRoot(id: String, url: String, username: String, password: String, displayName: String, basePath: String): LibraryRootEntity = unexpected("updateWebDavLibraryRoot")
        override suspend fun updateAbsLibraryRoot(id: String, credentialId: String, libraryId: String, displayName: String): LibraryRootEntity = unexpected("updateAbsLibraryRoot")
    }

    private class FakeScanScheduler : ScanScheduler {
        val scheduledScans = mutableListOf<ScheduledScan>()

        override suspend fun syncLibrary(trigger: String): ScanOutcome = unexpected("syncLibrary")

        override fun scheduleLibrarySync(trigger: String, requiresNetwork: Boolean) {
            scheduledScans += ScheduledScan(trigger = trigger, requiresNetwork = requiresNetwork)
        }
    }

    private data class LocalRootCall(
        val uri: String,
        val trigger: String
    )

    private data class WebDavRootCall(
        val url: String,
        val username: String,
        val password: String,
        val displayName: String,
        val basePath: String,
        val trigger: String
    )

    private data class ScheduledScan(
        val trigger: String,
        val requiresNetwork: Boolean
    )

    private data class StartedAbsTask(
        val rootId: String,
        val origin: AbsSyncTaskOrigin
    )

    private companion object {
        private const val ROOT_ID = "root-id"

        // UpdateTestHelperSourceType: Adapt root helper to accept type-safe LibrarySourceType instead of legacy String.
        private fun root(
            sourceType: AudiobookSchema.LibrarySourceType = AudiobookSchema.LibrarySourceType.SAF,
            displayName: String = "Library"
        ): LibraryRootEntity =
            LibraryRootEntity(
                id = ROOT_ID,
                sourceType = sourceType,
                sourceUri = "content://library/root",
                displayName = displayName
            )

        private fun unexpected(methodName: String): Nothing {
            error("Unexpected gateway call in SettingsRootModuleTest: $methodName")
        }
    }
}
