package com.viel.aplayer.ui.settings

import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.sync.AbsConnectionTestResult
import com.viel.aplayer.application.usecase.AbsConnectionReuseSnapshot
import com.viel.aplayer.application.usecase.shouldReuseAbsConnectionSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsConnectionReuseSnapshotTest {

    @Test
    fun `reuse snapshot should match same server same user and selected library`() {
        val snapshot = AbsConnectionReuseSnapshot(
            baseUrl = "https://example.com/audiobookshelf",
            username = "demo",
            token = "token-1",
            connection = AbsConnectionTestResult(
                serverVersion = "2.35.1",
                userId = "user-1",
                username = "demo",
                bookLibraries = listOf(
                    AbsLibraryDto(id = "lib-1", name = "Audiobooks", mediaType = "book")
                )
            )
        )

        // Connection reuse verification. Same server address with or without a trailing slash is treated as the same connection snapshot,
        // allowing instant add operations after successful connection tests to hit the reuse path.
        assertTrue(
            shouldReuseAbsConnectionSnapshot(
                snapshot = snapshot,
                baseUrl = "https://example.com/audiobookshelf/",
                username = "demo",
                libraryId = "lib-1"
            )
        )
    }

    @Test
    fun `reuse snapshot should reject changed server user or library`() {
        val snapshot = AbsConnectionReuseSnapshot(
            baseUrl = "https://example.com/audiobookshelf",
            username = "demo",
            token = "token-1",
            connection = AbsConnectionTestResult(
                serverVersion = "2.35.1",
                userId = "user-1",
                username = "demo",
                bookLibraries = listOf(
                    AbsLibraryDto(id = "lib-1", name = "Audiobooks", mediaType = "book")
                )
            )
        )

        // Connection reuse rejection. Abandon the old snapshot and force a retest if any of the server, account, or target library changes,
        // preventing the incorrect reuse of alternative connection contexts.
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://other.example/audiobookshelf", "demo", "lib-1"))
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://example.com/audiobookshelf", "other", "lib-1"))
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://example.com/audiobookshelf", "demo", "lib-2"))
    }
}
