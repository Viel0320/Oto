package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the post-facade gateway deepening.
 *
 * Prevents production callers from depending on the compatibility BookQueryGateway aggregate after the
 * catalog, metadata, chapter, bookmark, and deletion seams were split.
 */
class BookGatewaySplitArchitectureTest {
    @Test
    fun productionSourcesDoNotDependOnBookQueryGatewayAggregate() {
        val sourceRoot = resolveSourceRoot()
        val offenders = sourceRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .filterNot { file -> file.toRelativeString(sourceRoot) == "data/gateway/BookQueryGateway.kt" }
            .filter { file ->
                val source = file.readText()
                source.contains("import com.viel.aplayer.data.gateway.BookQueryGateway") ||
                    source.contains(": BookQueryGateway")
            }
            .map { file -> file.toRelativeString(sourceRoot) }
            .toList()

        assertTrue(
            "Production sources must depend on split book gateways instead of the compatibility aggregate: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun splitGatewayFilesExistForEachBookCapability() {
        val gatewayRoot = resolveSourceRoot().resolve("data/book")
        val expectedFiles = listOf(
            "BookCatalogGateway.kt",
            "BookMetadataGateway.kt",
            "ChapterGateway.kt",
            "BookmarkGateway.kt",
            "BookDeletionGateway.kt"
        )

        assertTrue(
            "Split book gateway files must exist beside the compatibility aggregate.",
            expectedFiles.all { name -> gatewayRoot.resolve(name).isFile }
        )
    }

    @Test
    fun bookDaoDoesNotImportMediaRuntime() {
        val sourceRoot = resolveSourceRoot()
        val bookDaoSource = sourceRoot.resolve("data/dao/BookDao.kt").readText()

        assertTrue(!bookDaoSource.contains("import com.viel.aplayer.media."))
        assertTrue(bookDaoSource.contains("import com.viel.aplayer.timeline.PositionMapper"))
    }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for book gateway split architecture test.")
    }
}
