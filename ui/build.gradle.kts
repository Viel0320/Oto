import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

// UI Module (Owns Compose routes, screens, overlays, ViewModels, theme, and app locale UI support)
android {
    namespace = "com.viel.oto.ui"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // UI resource tests exercise Compose/resource-backed formatting on the JVM.
            isIncludeAndroidResources = true
        }
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}

dependencies {
    implementation(project(":runtime:observability"))
    implementation(project(":application"))
    implementation(project(":event"))
    implementation(project(":library:vfs"))
    implementation(project(":library:import"))
    implementation(project(":media:metadata"))
    implementation(project(":media:playback"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material3.windowsize)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.animation.graphics)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.media3.common)
    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.material.kolor)
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.ui.tooling)
}
