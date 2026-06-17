package com.viel.aplayer.data.cleanup

import com.viel.aplayer.abs.playback.AbsPendingProgressSyncDao
import com.viel.aplayer.abs.playback.AbsPendingProgressSyncEntity
import com.viel.aplayer.abs.playback.AbsPlaybackSessionDao
import com.viel.aplayer.abs.playback.AbsPlaybackSessionEntity
// Import AudiobookSchema (Brings in the nested enum definitions for db schemas)
import com.viel.aplayer.data.db.AudiobookSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Remote Playback Cleanup Service Test (Locks book-scoped ABS residue deletion)
 *
 * Verifies single-book deletion cleanup removes both persisted ABS runtime sessions and queued pending progress.
 */
class RemotePlaybackCleanupServiceTest {
    @Test
    fun deletePlaybackStateForBookRemovesSessionAndPendingProgressRows() = runBlocking {
        val sessionDao = FakePlaybackSessionDao().apply {
            insertOrReplace(absSession(BOOK_ID))
            insertOrReplace(absSession(OTHER_BOOK_ID))
        }
        val pendingDao = FakePendingProgressSyncDao().apply {
            insertOrReplace(absPendingProgress(BOOK_ID))
            insertOrReplace(absPendingProgress(OTHER_BOOK_ID))
        }
        val service = RemotePlaybackCleanupGatewayImpl(
            absPlaybackSessionDao = sessionDao,
            absPendingProgressSyncDao = pendingDao
        )

        service.deletePlaybackStateForBook(BOOK_ID)

        // Target Book Cleanup Assertion (The deleted book must leave no remote playback residue)
        // A later ABS flush should not discover either an active session row or queued pending progress for this bookId.
        assertNull(sessionDao.getByBookId(BOOK_ID))
        assertNull(pendingDao.getByBookId(BOOK_ID))

        // Neighbor Book Preservation Assertion (Cleanup remains scoped to the requested book)
        // Deleting one audiobook must not erase remote playback state that belongs to a different ABS mirror item.
        assertSame(sessionDao.otherSession, sessionDao.getByBookId(OTHER_BOOK_ID))
        assertSame(pendingDao.otherPendingProgress, pendingDao.getByBookId(OTHER_BOOK_ID))
    }

    private class FakePlaybackSessionDao : AbsPlaybackSessionDao {
        private val rows = linkedMapOf<String, AbsPlaybackSessionEntity>()
        lateinit var otherSession: AbsPlaybackSessionEntity
            private set

        override suspend fun insertOrReplace(session: AbsPlaybackSessionEntity) {
            // Fake Session Upsert (Mirror Room replace semantics for the in-memory regression fixture)
            // The service only depends on bookId-keyed replacement and deletion behavior, so no database is needed here.
            rows[session.bookId] = session
            if (session.bookId == OTHER_BOOK_ID) {
                otherSession = session
            }
        }

        override suspend fun getByBookId(bookId: String): AbsPlaybackSessionEntity? = rows[bookId]

        override suspend fun deleteByBookId(bookId: String) {
            rows.remove(bookId)
        }

        override suspend fun deleteByBookIds(bookIds: List<String>) {
            rows.keys.removeAll(bookIds.toSet())
        }
    }

    private class FakePendingProgressSyncDao : AbsPendingProgressSyncDao {
        private val rows = linkedMapOf<String, AbsPendingProgressSyncEntity>()
        lateinit var otherPendingProgress: AbsPendingProgressSyncEntity
            private set

        override suspend fun insertOrReplace(sync: AbsPendingProgressSyncEntity) {
            // Fake Pending Progress Upsert (Mirror Room replace semantics for queued ABS uploads)
            // The fixture keeps only the behavior needed to prove book-scoped cleanup and neighbor preservation.
            rows[sync.bookId] = sync
            if (sync.bookId == OTHER_BOOK_ID) {
                otherPendingProgress = sync
            }
        }

        override suspend fun getByBookId(bookId: String): AbsPendingProgressSyncEntity? = rows[bookId]

        override suspend fun deleteByBookId(bookId: String) {
            rows.remove(bookId)
        }

        override suspend fun deleteByBookIds(bookIds: List<String>) {
            rows.keys.removeAll(bookIds.toSet())
        }
    }

    private companion object {
        private const val BOOK_ID = "book-id"
        private const val OTHER_BOOK_ID = "other-book-id"

        private fun absSession(bookId: String): AbsPlaybackSessionEntity {
            // ABS Session Fixture (Create the minimum persisted runtime state for cleanup assertions)
            // Stable values keep the test focused on the bookId deletion key instead of protocol payload details.
            return AbsPlaybackSessionEntity(
                bookId = bookId,
                remoteItemId = "remote-$bookId",
                sessionId = "session-$bookId",
                currentTimeSec = 12.0,
                timeListenedSec = 2.0,
                openedAt = 1_000L,
                state = AudiobookSchema.AbsPlaybackSessionState.OPEN
            )
        }

        private fun absPendingProgress(bookId: String): AbsPendingProgressSyncEntity {
            // ABS Pending Progress Fixture (Create the minimum queued upload state for cleanup assertions)
            // Stable values keep the regression locked to persistence cleanup rather than progress conversion math.
            return AbsPendingProgressSyncEntity(
                bookId = bookId,
                remoteItemId = "remote-$bookId",
                currentTimeSec = 12.0,
                timeListenedSec = 2.0,
                durationSec = 100.0,
                updatedAt = 1_500L
            )
        }
    }
}
