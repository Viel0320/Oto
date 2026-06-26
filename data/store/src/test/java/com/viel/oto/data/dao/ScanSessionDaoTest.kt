package com.viel.oto.data.dao

import androidx.room.Room
import com.viel.oto.data.db.AppDatabase
import com.viel.oto.data.db.AudiobookSchema
import com.viel.oto.data.entity.ScanSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

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
