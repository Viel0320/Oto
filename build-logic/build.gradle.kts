plugins {
    `kotlin-dsl`
}

// Convention Plugin Classpath (Compile-time only on purpose)
// The precompiled script plugin only needs the AGP/Kotlin Gradle DSL types at compile time; the applied
// `com.android.library` plugin is resolved through its plugin marker when a consumer module applies the convention.
// Keep these compileOnly: promoting them to implementation pins kotlin-gradle-plugin 2.4.0 onto the runtime
// classpath, which clashes with Gradle 9.6's embedded kotlin-dsl compiler (Kotlin 2.3.21) and breaks
// compilePluginsBlocks with "Unable to parse script-resolver-environment argument".
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}
