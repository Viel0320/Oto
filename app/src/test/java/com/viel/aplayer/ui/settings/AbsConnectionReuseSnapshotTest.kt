package com.viel.aplayer.ui.settings

import com.viel.aplayer.abs.net.dto.AbsLibraryDto
import com.viel.aplayer.abs.sync.AbsConnectionTestResult
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

        // 详尽的中文注释：同一服务器地址即使只差一个尾斜杠，也应视为同一连接快照，
        // 这样“测试连接成功后立刻点击添加”才能命中复用路径。
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

        // 详尽的中文注释：只要服务器、账号或用户当前选中的书库任一项发生变化，
        // 就必须放弃复用旧快照，重新走登录与连接测试，避免误用别的连接上下文。
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://other.example/audiobookshelf", "demo", "lib-1"))
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://example.com/audiobookshelf", "other", "lib-1"))
        assertFalse(shouldReuseAbsConnectionSnapshot(snapshot, "https://example.com/audiobookshelf", "demo", "lib-2"))
    }
}
