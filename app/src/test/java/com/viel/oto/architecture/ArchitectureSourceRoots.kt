package com.viel.oto.architecture

import java.io.File

/**
 * Resolves production and test source roots while the migration splits app-owned and application-owned code.
 * Architecture tests run from both repository and app-module working directories, so each lookup keeps both path shapes.
 */
internal object ArchitectureSourceRoots {
    fun appMain(): File = resolveRequiredRoot(
        description = "app main source root",
        candidates = listOf(
            File("src/main/java/com/viel/oto"),
            File("app/src/main/java/com/viel/oto")
        )
    )

    fun applicationMain(): File = resolveRequiredRoot(
        description = "application main source root",
        candidates = listOf(
            File("../application/src/main/java/com/viel/oto"),
            File("application/src/main/java/com/viel/oto")
        )
    )

    fun appTest(): File = resolveRequiredRoot(
        description = "app test source root",
        candidates = listOf(
            File("src/test/java/com/viel/oto"),
            File("app/src/test/java/com/viel/oto")
        )
    )

    fun applicationTest(): File = resolveRequiredRoot(
        description = "application test source root",
        candidates = listOf(
            File("../application/src/test/java/com/viel/oto"),
            File("application/src/test/java/com/viel/oto")
        )
    )

    fun appMainFile(relativePath: String): File = appMain().resolve(relativePath)

    fun applicationMainFile(relativePath: String): File = applicationMain().resolve(relativePath)

    private fun resolveRequiredRoot(description: String, candidates: List<File>): File =
        candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate $description from ${File(".").absolutePath}")
}
