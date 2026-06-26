package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins shared boundary packages as dependency-free homes for resources, pure policies, and value models.
 */
class SharedModuleArchitectureTest {

    @Test
    fun sharedKotlinSourcesStayInPolicyOrModelPackages() {
        val offenders = sharedBoundaryRoots().flatMap { root ->
            sharedKotlinFiles(root)
                .filterNot { file ->
                    val source = file.readText(Charsets.UTF_8)
                    source.contains("package com.viel.oto.shared.policy") ||
                        source.contains("package com.viel.oto.shared.model")
                }
                .map { file -> file.toRelativeString(root) }
            }

        assertTrue(
            "Shared Kotlin production files must live in shared.policy or shared.model: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun sharedKotlinSourcesDoNotImportOtherOtoPackages() {
        val offenders = sharedBoundaryRoots().flatMap { root ->
            sharedKotlinFiles(root).flatMap { file ->
                file.readLines(Charsets.UTF_8).mapIndexedNotNull { index, line ->
                    val importLine = line.trim()
                    if (importLine.startsWith("import com.viel.oto.") &&
                        !importLine.startsWith("import com.viel.oto.shared.")
                    ) {
                        "${file.toRelativeString(root)}:${index + 1} $importLine"
                    } else {
                        null
                    }
                }
            }
        }

        assertTrue(
            "Shared Kotlin production files must not import other Oto packages: $offenders",
            offenders.isEmpty()
        )
    }

    private fun sharedBoundaryRoots(): List<File> = listOf(
        ArchitectureSourceRoots.sharedMainFile("shared")
    )

    private fun sharedKotlinFiles(root: File): List<File> =
        root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
}
