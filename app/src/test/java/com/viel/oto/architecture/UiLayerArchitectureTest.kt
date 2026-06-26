package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test

class UiLayerArchitectureTest {
    /**
     * Guards the presentation boundary by rejecting direct imports from data-layer packages.
     * UI code must consume application, shared, or UI-owned projections so Room entities, DAOs, gateways, and database schema constants stay behind scene adapters.
     */
    @Test
    fun uiLayerDoesNotImportDataLayerDirectly() {
        val sourceRoot = resolveSourceRoot()
        val uiFiles = sourceRoot.resolve("ui")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

        assertTrue(
            "UI source files must exist before enforcing layer boundaries.",
            uiFiles.isNotEmpty()
        )

        val violations = uiFiles.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val importLine = line.trim()
                if (importLine.startsWith("import com.viel.oto.data.")) {
                    "${file.toRelativeString(sourceRoot)}:${index + 1} $importLine"
                } else {
                    null
                }
            }
        }

        assertTrue(
            "UI layer must not import data layer directly; leaking imports:\n${
                violations.joinToString(
                    "\n"
                )
            }",
            violations.isEmpty()
        )
    }

    /**
     * Resolves the production source root from both repository-level and module-level Gradle test working directories.
     */
    private fun resolveSourceRoot() =
        ArchitectureSourceRoots.uiMain()
}
