package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the stage-four player and edit dependency migration.
 * Prevents PlayerViewModel, BookmarkManager, MediaPlaybackDelegate, and EditBookViewModel from drifting back to the broad library facade.
 */
class PlayerEditArchitectureTest {

    @Test
    fun playerAndEditUiCallersDoNotImportLibraryFacade() {
        val sourceRoot = ArchitectureSourceRoots.appMain()
        val guardedFiles = listOf(
            "ui/player/PlaybackViewModel.kt",
            "ui/player/BookmarkViewModel.kt",
            "ui/player/PlayerSettingsViewModel.kt",
            "ui/player/components/bookmarks/BookmarkManager.kt",
            "ui/player/MediaPlaybackDelegate.kt",
            "ui/edit/EditBookViewModel.kt"
        )

        guardedFiles.forEach { relativePath ->
            val source = sourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import the broad library facade.",
                !source.contains("import com.viel.oto.data.LibraryFacade")
            )
        }
    }

    @Test
    fun playerViewModelsConsumePlayerSceneDependenciesOnly() {
        val sourceRoot = ArchitectureSourceRoots.appMain()
        val vms = listOf("PlaybackViewModel.kt", "BookmarkViewModel.kt", "PlayerSettingsViewModel.kt")
        vms.forEach { name ->
            val source = sourceRoot.resolve("ui/player/$name").readText()
            assertTrue(
                "$name must not call the old library presentation dependency provider.",
                !source.contains("getLibraryPresentationDependencies")
            )
        }

        val playbackSource = sourceRoot.resolve("ui/player/PlaybackViewModel.kt").readText()
        assertTrue(
            "PlaybackViewModel must consume player library read model.",
            playbackSource.contains("playerLibraryReadModel")
        )

        val bookmarkSource = sourceRoot.resolve("ui/player/BookmarkViewModel.kt").readText()
        assertTrue(
            "BookmarkViewModel must consume player bookmark commands.",
            bookmarkSource.contains("PlayerBookmarkCommands")
        )
    }

    @Test
    fun editViewModelConsumesEditSceneDependenciesOnly() {
        val editViewModelSource = ArchitectureSourceRoots.appMainFile("ui/edit/EditBookViewModel.kt").readText()

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
    fun playerAndEditModulesDoNotImportTheBroadFacade() {
        val sourceRoot = ArchitectureSourceRoots.applicationMain()
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
            moduleFiles.all { file -> !file.readText().contains("import com.viel.oto.data.LibraryFacade") }
        )
    }

    @Test
    fun playerReadModelAndUiExposeSceneProjectionsInsteadOfRoomEntities() {
        val sourceRoot = ArchitectureSourceRoots.appMain()
        val applicationSourceRoot = ArchitectureSourceRoots.applicationMain()
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
            val source = applicationSourceRoot.resolve(relativePath).readText()
            assertTrue(
                "$relativePath must not import Room entity packages in the player scene interface.",
                !source.contains("import com.viel.oto.data.entity")
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
                !source.contains("import com.viel.oto.data.entity")
            )
        }
    }

    @Test
    fun editReadModelAndUiExposeDraftsInsteadOfRoomEntities() {
        val sourceRoot = ArchitectureSourceRoots.appMain()
        val readModelSource = ArchitectureSourceRoots.applicationMainFile("application/library/edit/EditBookReadModel.kt").readText()
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
            !readModelSource.contains("import com.viel.oto.data.entity")
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
                !source.contains("import com.viel.oto.data.entity")
            )
            assertTrue(
                "$relativePath must not mention Room row types in the edit UI contract.",
                !source.contains("BookEntity") &&
                    !source.contains("BookWithProgress")
            )
        }
    }

}
