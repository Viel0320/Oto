package com.viel.oto.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins orchestration package ownership.
 * Ensures use cases stay in the application layer while the domain package remains free of workflow orchestration classes.
 */
class ApplicationUseCasePackageArchitectureTest {

    @Test
    fun useCasesDeclareTheApplicationPackage() {
        val sourceRoot = ArchitectureSourceRoots.applicationMain()
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
                source.contains("package com.viel.oto.application.usecase")
            )
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must not declare the retired domain usecase package.",
                !source.contains("package ${retiredUseCasePackage()}")
            )
        }
    }

    @Test
    fun retiredDomainUseCaseDirectoriesStayEmpty() {
        val oldMainFiles = listOf(ArchitectureSourceRoots.appMain(), ArchitectureSourceRoots.applicationMain())
            .flatMap { sourceRoot -> sourceRoot.resolve("domain/usecase").walkKotlinFilesIfPresent() }
        val oldTestFiles = listOf(ArchitectureSourceRoots.appTest(), ArchitectureSourceRoots.applicationTest())
            .flatMap { sourceRoot -> sourceRoot.resolve("domain/usecase").walkKotlinFilesIfPresent() }

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
        val guardedFiles = listOf(
            ArchitectureSourceRoots.appMain(),
            ArchitectureSourceRoots.applicationMain(),
            ArchitectureSourceRoots.appTest(),
            ArchitectureSourceRoots.applicationTest()
        ).flatMap { sourceRoot -> sourceRoot.walkKotlinFiles() }
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
        val sourceRoot = ArchitectureSourceRoots.appMain()
        val domainFiles = sourceRoot.resolve("domain")
            .walkKotlinFilesIfPresent()

        domainFiles.forEach { file ->
            val source = file.readText()
            assertTrue(
                "${file.toRelativeString(sourceRoot)} must not depend on data-layer entities.",
                !source.contains("import com.viel.oto.data.entity")
            )
        }
    }

    private fun retiredUseCasePackage(): String {
        return listOf("com.viel.oto.domain", "usecase").joinToString(".")
    }

    private fun File.walkKotlinFiles(): List<File> {
        return walkTopDown()
            .filter { file -> file.isFile && file.name.endsWith(".kt") }
            .toList()
    }

    private fun File.walkKotlinFilesIfPresent(): List<File> {
        return if (isDirectory) walkKotlinFiles() else emptyList()
    }
}
