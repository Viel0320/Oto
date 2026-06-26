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

    /**
     * Resolves the extracted UI module so architecture guards keep following Compose code after it
     * leaves the app source set.
     */
    fun uiMain(): File = resolveRequiredRoot(
        description = "ui main source root",
        candidates = listOf(
            File("../ui/src/main/java/com/viel/oto"),
            File("ui/src/main/java/com/viel/oto")
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

    /**
     * Resolves the extracted event module so architecture guards keep scanning feedback delivery
     * contracts after they leave the app source set.
     */
    fun eventMain(): File = resolveRequiredRoot(
        description = "event main source root",
        candidates = listOf(
            File("../event/src/main/kotlin/com/viel/oto"),
            File("event/src/main/kotlin/com/viel/oto")
        )
    )

    /**
     * Resolves event module tests for future guards that compare production and test feedback roots
     * during staged extraction.
     */
    fun eventTest(): File = resolveRequiredRoot(
        description = "event test source root",
        candidates = listOf(
            File("../event/src/test/kotlin/com/viel/oto"),
            File("event/src/test/kotlin/com/viel/oto")
        )
    )

    fun appMainFile(relativePath: String): File = appMain().resolve(relativePath)

    fun applicationMainFile(relativePath: String): File = applicationMain().resolve(relativePath)

    fun uiMainFile(relativePath: String): File = uiMain().resolve(relativePath)

    fun eventMainFile(relativePath: String): File = eventMain().resolve(relativePath)

    private fun resolveRequiredRoot(description: String, candidates: List<File>): File =
        candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate $description from ${File(".").absolutePath}")
}
