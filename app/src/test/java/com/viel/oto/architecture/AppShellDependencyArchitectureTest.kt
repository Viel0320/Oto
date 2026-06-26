package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the Stage 8 app-shell boundary after feature code moves into domain modules.
 *
 * Non-app modules may be merged into the final APK, but they must not know the concrete
 * Application, Activity, or app-owned adapter package names. App-shell entrypoints should be
 * provided by manifest merging, launcher resolution, or app composition-root adapters.
 */
class AppShellDependencyArchitectureTest {

    @Test
    fun domainModulesDoNotReferenceAppShellClasses() {
        val violations = resolveDomainProductionRoots()
            .flatMap { root ->
                root.walkTopDown()
                    .filter { file -> file.isFile && file.extension in sourceExtensions }
                    .flatMap { file -> file.findForbiddenAppShellReferences(root) }
            }

        assertTrue(
            buildString {
                appendLine("Domain modules must not reference app-shell classes or app-owned adapter packages.")
                violations.forEach { violation -> appendLine("- $violation") }
            },
            violations.isEmpty()
        )
    }

    /**
     * Reports forbidden references with module-relative file paths so migration failures point to
     * the module that leaked an app-shell dependency.
     */
    private fun File.findForbiddenAppShellReferences(root: File): List<String> {
        val relativePath = relativeTo(root).invariantSeparatorsPath
        return readLines().flatMapIndexed { index, line ->
            forbiddenReferences.mapNotNull { forbidden ->
                if (line.contains(forbidden)) {
                    "$relativePath:${index + 1} contains `$forbidden`"
                } else {
                    null
                }
            }
        }
    }

    /**
     * Resolves non-app production roots from both repository-level and app-module Gradle working
     * directories. Stage 8 keeps `:app` as the only owner of shell classes, so every other module is
     * scanned as a domain module here.
     */
    private fun resolveDomainProductionRoots(): List<File> =
        domainModuleSourceRoots
            .mapNotNull { candidate -> candidate.takeIf { it.isDirectory } }
            .distinctBy { candidate -> candidate.canonicalFile }
            .also { roots ->
                check(roots.isNotEmpty()) {
                    "Could not locate domain production roots for app-shell dependency architecture test."
                }
            }

    private companion object {
        private val sourceExtensions = setOf("kt", "java", "xml")

        private val forbiddenReferences = listOf(
            "com.viel.oto.MainActivity",
            "com.viel.oto.OtoApplication",
            "com.viel.oto.app."
        )

        private val domainModuleSourceRoots = listOf(
            File("../runtime/lifecycle/src/main"),
            File("runtime/lifecycle/src/main"),
            File("../runtime/observability/src/main"),
            File("runtime/observability/src/main"),
            File("../data/store/src/main"),
            File("data/store/src/main"),
            File("../library/vfs/src/main"),
            File("library/vfs/src/main"),
            File("../library/import/src/main"),
            File("library/import/src/main"),
            File("../media/metadata/src/main"),
            File("media/metadata/src/main"),
            File("../media/playback/src/main"),
            File("media/playback/src/main"),
            File("../media/service/src/main"),
            File("media/service/src/main"),
            File("../abs/src/main"),
            File("abs/src/main"),
            File("../work/policy/src/main"),
            File("work/policy/src/main"),
            File("../application/src/main"),
            File("application/src/main"),
            File("../event/src/main"),
            File("event/src/main"),
            File("../shared/src/main"),
            File("shared/src/main"),
            File("../ui/src/main"),
            File("ui/src/main"),
            File("../widget/src/main"),
            File("widget/src/main")
        )
    }
}
