package com.viel.oto.architecture

import com.viel.oto.di.GraphClosePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Captures the current package dependency graph while Gradle module extraction proceeds.
 *
 * The migration uses this allow-list as a regression baseline: each phase must either preserve the
 * known graph or intentionally tighten it while updating this test in the same reviewed change.
 */
class MultiModuleMigrationBaselineTest {

    @Test
    fun topLevelPackageImportsStayOnTheMigrationBaseline() {
        val sourceRoots = resolveMigrationSourceRoots()
        val actualEdges = sourceRoots
            .flatMap { sourceRoot ->
                sourceRoot.walkKotlinFiles()
                    .flatMap { file -> file.topLevelImportEdges(sourceRoot) }
            }
            .toSortedSet()

        assertTrue(
            "Production imports must be discovered before enforcing the migration baseline.",
            actualEdges.isNotEmpty()
        )

        val unexpectedEdges = actualEdges - expectedTopLevelImportEdges
        val missingEdges = expectedTopLevelImportEdges - actualEdges

        assertTrue(
            buildString {
                appendLine("Top-level imports changed outside the multi-module migration baseline.")
                appendLine("Unexpected edges:")
                unexpectedEdges.forEach { edge -> appendLine("- $edge") }
                appendLine("Missing baseline edges:")
                missingEdges.forEach { edge -> appendLine("- $edge") }
            },
            unexpectedEdges.isEmpty() && missingEdges.isEmpty()
        )
    }

    @Test
    fun koinCompositionRootKeepsTheStageZeroModuleOrder() {
        val source = resolveAppSourceRoot()
            .resolve("di/OtoKoinApplication.kt")
            .readText()
        val actualModules = koinModuleReferenceRegex.findAll(source)
            .map { match -> match.groupValues[1] }
            .toList()

        assertEquals(expectedKoinModuleOrder, actualModules)
    }

    @Test
    fun graphClosePolicyKeepsTheStageZeroLifecycleOrder() {
        val actualStages = GraphClosePolicy.Stage.entries.map { stage -> stage.name }

        assertEquals(
            listOf("Media", "Download", "Abs", "Library", "UiEvents", "Data"),
            actualStages
        )
    }

    private fun File.topLevelImportEdges(sourceRoot: File): List<String> {
        val sourcePackage = topLevelSourcePackage(sourceRoot)
        return readLines().mapNotNull { line ->
            val targetPackage = topLevelImportRegex.matchEntire(line.trim())
                ?.groupValues
                ?.get(1)
            targetPackage
                ?.takeUnless { target -> target == sourcePackage }
                ?.let { target -> "$sourcePackage->$target" }
        }
    }

    private fun File.topLevelSourcePackage(sourceRoot: File): String {
        val relativePath = sourceRoot.toPath()
            .relativize(toPath())
            .toString()
            .replace(File.separatorChar, '/')
        return if (relativePath.contains('/')) {
            relativePath.substringBefore('/')
        } else {
            APP_SHELL_PACKAGE
        }
    }

    private fun resolveMigrationSourceRoots(): List<File> {
        val candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto"),
            File("../settings/model/src/main/kotlin/com/viel/oto"),
            File("settings/model/src/main/kotlin/com/viel/oto"),
            File("../network/policy/src/main/kotlin/com/viel/oto"),
            File("network/policy/src/main/kotlin/com/viel/oto"),
            File("../runtime/lifecycle/src/main/kotlin/com/viel/oto"),
            File("runtime/lifecycle/src/main/kotlin/com/viel/oto"),
            File("../runtime/observability/src/main/java/com/viel/oto"),
            File("runtime/observability/src/main/java/com/viel/oto"),
            File("../data/store/src/main/java/com/viel/oto"),
            File("data/store/src/main/java/com/viel/oto"),
            File("../library/vfs/src/main/java/com/viel/oto"),
            File("library/vfs/src/main/java/com/viel/oto"),
            File("../library/import/src/main/java/com/viel/oto"),
            File("library/import/src/main/java/com/viel/oto"),
            File("../media/metadata/src/main/java/com/viel/oto"),
            File("media/metadata/src/main/java/com/viel/oto"),
            File("../media/playback/src/main/java/com/viel/oto"),
            File("media/playback/src/main/java/com/viel/oto"),
            File("../media/service/src/main/java/com/viel/oto"),
            File("media/service/src/main/java/com/viel/oto"),
            File("../abs/src/main/java/com/viel/oto"),
            File("abs/src/main/java/com/viel/oto"),
            File("../work/policy/src/main/java/com/viel/oto"),
            File("work/policy/src/main/java/com/viel/oto"),
            File("../application/src/main/java/com/viel/oto"),
            File("application/src/main/java/com/viel/oto"),
            File("../event/src/main/kotlin/com/viel/oto"),
            File("event/src/main/kotlin/com/viel/oto"),
            File("../ui/src/main/java/com/viel/oto"),
            File("ui/src/main/java/com/viel/oto")
        )
        return candidates
            .filter { candidate -> candidate.isDirectory }
            .also { roots ->
                check(roots.isNotEmpty()) {
                    "Could not locate any production source roots for multi-module migration baseline test."
                }
            }
    }

    private fun resolveAppSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app main source root for multi-module migration baseline test.")
    }

    private fun File.walkKotlinFiles(): List<File> =
        walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

    private companion object {
        private const val APP_SHELL_PACKAGE = "app"

        private val topLevelImportRegex = Regex("""import com\.viel\.oto\.([A-Za-z0-9_]+)(?:\.|$).*""")
        private val koinModuleReferenceRegex = Regex("""\b([A-Za-z0-9_]+Module)\.module""")

        private val expectedKoinModuleOrder = listOf(
            "CoreDataModule",
            "CoreSettingsModule",
            "UiEventModule",
            "MediaModule",
            "MediaMetadataModule",
            "MediaSubtitleModule",
            "MediaServiceModule",
            "MediaPlaybackControllerModule",
            "DownloadModule",
            "DownloadReadModelModule",
            "LibraryBookGatewayModule",
            "LibraryCoverModule",
            "LibraryScanModule",
            "LibraryUseCaseModule",
            "LibrarySceneModule",
            "AbsModule",
            "AbsSyncModule",
            "SettingsUseCaseModule",
            "ViewModelModule"
        )

        private val expectedTopLevelImportEdges = setOf(
            "abs->data",
            "abs->library",
            "abs->logger",
            "abs->media",
            "abs->network",
            "abs->shared",
            "abs->timeline",
            "abs->work",
            "app->abs",
            "app->MainActivity",
            "app->application",
            "app->data",
            "app->di",
            "app->i18n",
            "app->logger",
            "app->media",
            "app->shared",
            "app->ui",
            "app->widget",
            "application->abs",
            "application->data",
            "application->library",
            "application->logger",
            "application->media",
            "application->shared",
            "data->logger",
            "data->shared",
            "data->timeline",
            "di->abs",
            "di->app",
            "di->application",
            "di->data",
            "di->event",
            "di->library",
            "di->logger",
            "di->media",
            "di->ui",
            "event->application",
            "event->abs",
            "event->data",
            "event->library",
            "event->media",
            "event->shared",
            "i18n->logger",
            "i18n->shared",
            "library->data",
            "library->logger",
            "library->media",
            "library->network",
            "library->shared",
            "library->timeline",
            "library->work",
            "media->data",
            "media->library",
            "media->logger",
            "media->network",
            "media->shared",
            "media->timeline",
            "network->shared",
            "timeline->data",
            "ui->application",
            "ui->event",
            "ui->i18n",
            "ui->library",
            "ui->logger",
            "ui->media",
            "ui->network",
            "ui->shared",
            "work->data"
        )
    }
}
