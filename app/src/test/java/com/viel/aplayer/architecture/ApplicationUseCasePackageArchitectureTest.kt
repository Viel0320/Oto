package com.viel.aplayer.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Application Use Case Package Architecture Test (Pins orchestration package ownership)
 * Ensures use cases stay in the application layer while the domain package remains free of workflow orchestration classes.
 */
class ApplicationUseCasePackageArchitectureTest {

    @Test
    fun useCasesDeclareTheApplicationPackage() {
        val sourceRoot = resolveMainSourceRoot()
        val useCaseFiles = sourceRoot.resolve("application/usecase")
            .walkKotlinFiles()

        assertTrue(
            "Application use case files must exist before enforcing package ownership.",
            useCaseFiles.isNotEmpty()
        )
        useCaseFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must declare the application usecase package.",
                source.contains("package com.viel.aplayer.application.usecase")
            )
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must not declare the retired domain usecase package.",
                !source.contains("package ${retiredUseCasePackage()}")
            )
        }
    }

    @Test
    fun retiredDomainUseCaseDirectoriesStayEmpty() {
        val oldMainFiles = resolveMainSourceRoot().resolve("domain/usecase")
            .walkKotlinFilesIfPresent()
        val oldTestFiles = resolveTestSourceRoot().resolve("domain/usecase")
            .walkKotlinFilesIfPresent()

        assertTrue(
            "The old main domain/usecase directory must not regain Kotlin files.",
            oldMainFiles.isEmpty()
        )
        assertTrue(
            "The old test domain/usecase directory must not regain Kotlin files.",
            oldTestFiles.isEmpty()
        )
    }

    @Test
    fun sourceFilesDoNotImportRetiredDomainUseCasePackage() {
        val guardedFiles = resolveMainSourceRoot().walkKotlinFiles() +
            resolveTestSourceRoot().walkKotlinFiles()
        val retiredPackage = retiredUseCasePackage()

        guardedFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.name} must not reference the retired domain usecase package.",
                !source.contains(retiredPackage)
            )
        }
    }

    @Test
    fun domainModelsDoNotImportDataEntities() {
        val domainFiles = resolveMainSourceRoot().resolve("domain")
            .walkKotlinFilesIfPresent()

        domainFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.toRelativeString(resolveMainSourceRoot())} must not depend on data-layer entities.",
                !source.contains("import com.viel.aplayer.data.entity")
            )
        }
    }

    private fun retiredUseCasePackage(): String {
        // Retired Package Literal Assembly (Keeps this test from matching its own guard string)
        // The guarded package is assembled at runtime so source scans only fail on real production or test imports.
        return listOf("com.viel.aplayer.domain", "usecase").joinToString(".")
    }

    private fun resolveMainSourceRoot(): File {
        // Main Source Root Resolution (Supports both module and repository working directories)
        // Gradle can execute JVM tests from app/ or the repository root, so both stable source-root candidates are checked.
        val candidates = listOf(
            File("src/main/java/com/viel/aplayer"),
            File("app/src/main/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app main source root for usecase package architecture test.")
    }

    private fun resolveTestSourceRoot(): File {
        // Test Source Root Resolution (Supports both module and repository working directories)
        // Architecture tests scan test sources as well because stale imports often survive there after package moves.
        val candidates = listOf(
            File("src/test/java/com/viel/aplayer"),
            File("app/src/test/java/com/viel/aplayer")
        )
        return candidates.firstOrNull { candidate -> candidate.isDirectory }
            ?: error("Could not locate app test source root for usecase package architecture test.")
    }

    private fun File.walkKotlinFiles(): List<File> {
        // Kotlin File Discovery (Provides one small reusable scanner for source-boundary assertions)
        // Returning a list gives each test stable diagnostics while avoiding repeated directory traversal details.
        return walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()
    }

    private fun File.walkKotlinFilesIfPresent(): List<File> {
        // Optional Kotlin File Discovery (Allows retired directories to be absent or empty)
        // Missing old package directories are valid, while any Kotlin file inside them is treated as a regression.
        return if (isDirectory) walkKotlinFiles() else emptyList()
    }
}
