package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Player Edit Architecture Test (Pins the stage-four player and edit dependency migration)
 * Prevents PlayerViewModel, BookmarkManager, MediaPlaybackDelegate, and EditBookViewModel from drifting back to the broad library facade.
 */
class PlayerEditArchitectureTest {

    @Test
    fun playerAndEditUiCallersDoNotImportLibraryFacade() {
        val sourceRoot = resolveSourceRoot()
        val guardedFiles = listOf(
            "ui/player/PlayerViewModel.kt",
            "ui/player/components/bookmarks/BookmarkManager.kt",
            "ui/player/MediaPlaybackDelegate.kt",
            "ui/edit/EditBookViewModel.kt"
        )

        guardedFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import the broad library facade.",
                !source.contains("import com.viel.aplayer.data.LibraryFacade")
            )
        }
    }

    @Test
    fun playerViewModelConsumesPlayerSceneDependenciesOnly() {
        val playerViewModelSource = resolveSourceRoot().resolve("ui/player/PlayerViewModel.kt").readText()

        assertTrue(
            "PlayerViewModel must resolve the player-specific dependency view.",
            playerViewModelSource.contains("getPlayerScreenDependencies")
        )
        assertTrue(
            "PlayerViewModel must consume player read and bookmark scene interfaces.",
            playerViewModelSource.contains("playerLibraryReadModel") &&
                playerViewModelSource.contains("playerBookmarkCommands")
        )
        assertTrue(
            "PlayerViewModel must not call the old library presentation dependency provider.",
            !playerViewModelSource.contains("getLibraryPresentationDependencies")
        )
    }

    @Test
    fun editViewModelConsumesEditSceneDependenciesOnly() {
        val editViewModelSource = resolveSourceRoot().resolve("ui/edit/EditBookViewModel.kt").readText()

        assertTrue(
            "EditBookViewModel must resolve the edit-specific dependency view.",
            editViewModelSource.contains("getEditScreenDependencies")
        )
        assertTrue(
            "EditBookViewModel must consume edit read and command scene interfaces.",
            editViewModelSource.contains("editBookReadModel") &&
                editViewModelSource.contains("editBookCommands")
        )
        assertTrue(
            "EditBookViewModel must not call the old library presentation dependency provider.",
            !editViewModelSource.contains("getLibraryPresentationDependencies")
        )
    }

    @Test
    fun playerAndEditDependencyViewsDoNotInheritLibraryPresentationDependencies() {
        val dependenciesSource = resolveSourceRoot().resolve("dependencies/PresentationDependencies.kt").readText()
        val playerInterface = dependenciesSource.substringAfter("interface PlayerScreenDependencies")
            .substringBefore("/**\n * Edit Screen Dependencies")
        val editInterface = dependenciesSource.substringAfter("interface EditScreenDependencies")

        assertTrue(
            "PlayerScreenDependencies must not inherit LibraryPresentationDependencies because that would re-expose LibraryFacade.",
            !playerInterface.substringBefore("{").contains("LibraryPresentationDependencies")
        )
        assertTrue(
            "PlayerScreenDependencies must expose the player read model and bookmark commands.",
            playerInterface.contains("val playerLibraryReadModel: PlayerLibraryReadModel") &&
                playerInterface.contains("val playerBookmarkCommands: PlayerBookmarkCommands")
        )
        assertTrue(
            "EditScreenDependencies must expose edit read and command interfaces.",
            editInterface.contains("val editBookReadModel: EditBookReadModel") &&
                editInterface.contains("val editBookCommands: EditBookCommands")
        )
    }

    @Test
    fun playerAndEditModulesDoNotImportTheBroadFacade() {
        val sourceRoot = resolveSourceRoot()
        val moduleRoots = listOf(
            sourceRoot.resolve("application/library/player"),
            sourceRoot.resolve("application/library/edit")
        )
        val moduleFiles = moduleRoots
            .flatMap { root -> root.walkTopDown().filter { file -> file.isFile && file.name.endsWith(".kt") }.toList() }

        assertTrue(
            "Player and edit scene module files must exist for the stage-four migration.",
            moduleFiles.isNotEmpty()
        )
        assertTrue(
            "Player and edit scene modules must be backed by granular gateways rather than the broad facade.",
            moduleFiles.all { file -> !file.readText().contains("import com.viel.aplayer.data.LibraryFacade") }
        )
    }

    @Test
    fun playerReadModelAndUiExposeSceneProjectionsInsteadOfRoomEntities() {
        val sourceRoot = resolveSourceRoot()
        val playerInterfaceFiles = listOf(
            "application/library/player/PlayerLibraryReadModel.kt",
            "application/library/player/PlayerBookmarkCommands.kt"
        )
        val playerUiFiles = sourceRoot.resolve("ui/player")
            .walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()
        val forbiddenTypes = listOf(
            "BookWithProgress",
            "BookEntity",
            "BookmarkEntity",
            "ChapterWithBookFile",
            "ChapterEntity"
        )

        playerInterfaceFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import Room entity packages in the player scene interface.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
            assertTrue(
                "$relativePath must expose player-scene projections instead of Room row types.",
                forbiddenTypes.all { typeName -> !source.contains(typeName) }
            )
        }

        playerUiFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must not import Room entity packages in player UI.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
        }
    }

    @Test
    fun editReadModelAndUiExposeDraftsInsteadOfRoomEntities() {
        val sourceRoot = resolveSourceRoot()
        val readModelSource = sourceRoot.resolve("application/library/edit/EditBookReadModel.kt").readText()
        val guardedUiFiles = listOf(
            "ui/edit/EditBookViewModel.kt",
            "ui/edit/EditBookRoute.kt",
            "ui/edit/EditBookScreen.kt",
            "ui/edit/EditBookOverlay.kt"
        )

        assertTrue(
            "EditBookReadModel must return the edit draft type rather than the Room entity.",
            readModelSource.contains("suspend fun getEditableBook(bookId: String): EditBookDraft?")
        )
        assertTrue(
            "EditBookReadModel must not import Room entity packages.",
            !readModelSource.contains("import com.viel.aplayer.data.entity")
        )
        assertTrue(
            "EditBookReadModel must not expose BookEntity or BookWithProgress in its interface.",
            !readModelSource.contains("BookEntity") &&
                !readModelSource.contains("BookWithProgress")
        )

        guardedUiFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import Room entity packages for edit metadata.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
            assertTrue(
                "$relativePath must not mention Room row types in the edit UI contract.",
                !source.contains("BookEntity") &&
                    !source.contains("BookWithProgress")
            )
        }
    }

    private fun resolveSourceRoot(): File {
        // Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from different directories, so the test checks both stable source-root candidates.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app source root for player/edit architecture test.")
    }
}
