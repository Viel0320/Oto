package com.viel.oto.data.cleanup

import com.viel.oto.data.abs.playback.AbsPendingProgressSyncDao
import com.viel.oto.data.abs.playback.AbsPendingProgressSyncEntity
import com.viel.oto.data.abs.playback.AbsPlaybackSessionDao
import com.viel.oto.data.abs.playback.AbsPlaybackSessionEntity
import com.viel.oto.data.db.AudiobookSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Locks book-scoped ABS residue deletion.
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

        assertNull(sessionDao.getByBookId(BOOK_ID))
        assertNull(pendingDao.getByBookId(BOOK_ID))

        assertSame(sessionDao.otherSession, sessionDao.getByBookId(OTHER_BOOK_ID))
        assertSame(pendingDao.otherPendingProgress, pendingDao.getByBookId(OTHER_BOOK_ID))
    }

    private class FakePlaybackSessionDao : AbsPlaybackSessionDao {
        private val rows = linkedMapOf<String, AbsPlaybackSessionEntity>()
        lateinit var otherSession: AbsPlaybackSessionEntity
            private set

        override suspend fun insertOrReplace(session: AbsPlaybackSessionEntity) {
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
