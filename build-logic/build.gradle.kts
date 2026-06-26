plugins {
    `kotlin-dsl`
}

// Convention Plugins (Centralize Android library, Kotlin JVM target, and shared test wiring across modules)
dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}
