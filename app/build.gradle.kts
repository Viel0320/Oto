import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Setup Kotlin Serialization (Apply compiler serialization plugin) Required for type-safe routes serialization in Navigation 3.
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.viel.aplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.viel.aplayer"
        // SDK Level Bump (Raise minSdk to 33 to support hardware-level blur effects)
        // This targets Android 13 (API 33) to allow hardware-accelerated window blur rendering using miuix-blur.
        minSdk = 32
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        @Suppress("UnstableApiUsage")
        androidResources {
            localeFilters += listOf("zh", "en")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "password"
            keyAlias = "aplayer"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // Window Size Class Integration (Introduce the official material3-window-size-class dependency)
    // This implements adaptive layout scaling using WindowWidthSizeClass and WindowHeightSizeClass.
    implementation(libs.androidx.compose.material3.windowsize)
    // Fix Compose Material Icons Extended Reference (Use dot notation instead of hyphens in Gradle Kotlin DSL)
    // Map libraries entry 'androidx-compose-material-icons-extended' correctly using dot syntax.
    implementation(libs.androidx.compose.material.icons.extended)
    // Migrate Navigation 2 to Navigation 3 (Transition core navigation containers) Replaced old navigation-compose with navigation3 runtime and UI.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Media3 (for audiobook playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Coil (image loading for cover art)
    implementation(libs.coil.compose)

    // Palette (for dynamic background colors)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Introduce Haze Blur (Transition backdrop blur from miuix-blur to dev.chrisbanes.haze) Replaced miuix-blur dependency with haze and haze-materials.
    implementation(libs.haze)
    implementation(libs.haze.materials)

    // Jetpack Glance Integration (Re-declare and introduce Jetpack Glance dependencies)
    // Includes core declarative API, AppWidget support, and Material 3 adaptive color mapping.
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Room (local database)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager & DocumentFile
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    // WebDAV Network Support (Utilize OkHttp client for handling network requests)
    // Standardizes WebDAV file listings (PROPFIND), stream downloads, and range-based media reading.
    implementation(libs.okhttp)
    // JSON Deserialization Strategy (Standardize on Moshi for structured API responses)
    // Decouples JSON structure mapping and prevents scattering raw protocol fields in business logic.
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // Moshi Code Generation (Configure ksp code generator for Moshi adapters)
    // Reduces runtime reflection overhead and stabilizes DTO parsing for API client modules.
    ksp(libs.moshi.kotlin.codegen)

    testImplementation(libs.junit)
    // API Testing Support (Utilize MockWebServer for local integration testing)
    // Solidifies API behaviors including HTTP methods, path routing, and authentication headers.
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Room Schema Exporting (Configure KSP processor arguments to specify schema export path)
// Enables compile-time database schema dumps for version control tracking and migration audits.
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}
