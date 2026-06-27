import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // Android Kotlin Library Baseline (AGP 9 provides built-in Kotlin support for Android modules)
    // The convention applies only the Android library plugin, then configures the Kotlin extension that AGP exposes for library source sets.
    id("com.android.library")
}

// Oto Android Library Convention (Centralizes compileSdk, minSdk, Java 21, and the Kotlin JVM target across modules)
android {
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}
