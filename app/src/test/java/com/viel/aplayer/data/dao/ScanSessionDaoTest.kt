package com.viel.aplayer.data.dao

import androidx.room.Room
import com.viel.aplayer.data.db.AppDatabase
import com.viel.aplayer.data.db.AudiobookSchema
import com.viel.aplayer.data.entity.ScanSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Room Scan Session Lifecycle Harness (Runs the real DAO SQL against an in-memory database)
// The abandoned-state bug lives in the persistence update statement, so this test verifies the concrete Room query instead of a fake.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScanSessionDaoTest {

    @Test
    fun `mark abandoned should persist explicit abandoned status`() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java
        )
            .allowMainThreadQueries()
            .build()
        try {
            database.scanSessionDao().insertSession(
                ScanSessionEntity(
                    id = SCAN_ID,
                    trigger = AudiobookSchema.ScanTrigger.USER,
                    status = AudiobookSchema.ScanStatus.RUNNING,
                    startedAt = 1_000L
                )
            )

            database.scanSessionDao().markAbandoned(id = SCAN_ID, abandonedAt = 2_000L)

            val session = database.scanSessionDao().getSessionById(SCAN_ID)

            // Abandoned Status Persistence (Prevents failed scans from remaining indistinguishable from running rows)
            // The returned session must carry ABANDONED so outcome mapping cannot treat the failed lifecycle as a completed empty scan.
            assertEquals(AudiobookSchema.ScanStatus.ABANDONED, session?.status)
            assertEquals(2_000L, session?.abandonedAt)
            assertNull(session?.completedAt)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SCAN_ID = "scan-abandoned"
    }
}
