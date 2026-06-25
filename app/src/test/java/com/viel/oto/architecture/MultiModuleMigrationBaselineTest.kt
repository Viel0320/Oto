package com.viel.oto.architecture

import com.viel.oto.di.GraphClosePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Captures the current single-module dependency graph before Gradle module extraction begins.
 *
 * The migration uses this allow-list as a regression baseline: each phase must either preserve the
 * known graph or intentionally tighten it while updating this test in the same reviewed change.
 */
class MultiModuleMigrationBaselineTest {

    @Test
    fun topLevelPackageImportsStayOnTheMigrationBaseline() {
        val sourceRoot = resolveMainSourceRoot()
        val actualEdges = sourceRoot.walkKotlinFiles()
            .flatMap { file -> file.topLevelImportEdges(sourceRoot) }
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
        val source = resolveMainSourceRoot()
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

    private fun resolveMainSourceRoot(): File {
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
            "abs->event",
            "abs->library",
            "abs->logger",
            "abs->media",
            "abs->network",
            "abs->shared",
            "abs->timeline",
            "abs->work",
            "app->abs",
            "app->application",
            "app->data",
            "app->di",
            "app->i18n",
            "app->logger",
            "app->media",
            "app->ui",
            "application->R",
            "application->abs",
            "application->data",
            "application->event",
            "application->library",
            "application->logger",
            "application->media",
            "application->network",
            "application->shared",
            "data->BuildConfig",
            "data->abs",
            "data->event",
            "data->library",
            "data->logger",
            "data->media",
            "data->shared",
            "data->timeline",
            "data->work",
            "di->abs",
            "di->application",
            "di->data",
            "di->event",
            "di->library",
            "di->media",
            "di->ui",
            "event->R",
            "event->application",
            "event->data",
            "event->media",
            "i18n->logger",
            "i18n->shared",
            "library->abs",
            "library->data",
            "library->event",
            "library->logger",
            "library->media",
            "library->network",
            "library->timeline",
            "logger->data",
            "media->MainActivity",
            "media->R",
            "media->abs",
            "media->application",
            "media->data",
            "media->library",
            "media->logger",
            "media->network",
            "media->shared",
            "media->timeline",
            "media->widget",
            "network->shared",
            "timeline->data",
            "ui->BuildConfig",
            "ui->R",
            "ui->application",
            "ui->event",
            "ui->i18n",
            "ui->logger",
            "ui->media",
            "ui->shared",
            "widget->MainActivity",
            "widget->R",
            "widget->logger",
            "widget->media",
            "widget->shared",
            "work->data"
        )
    }
}
