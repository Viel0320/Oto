import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Setup Kotlin Serialization (Apply compiler serialization plugin) Required for type-safe routes serialization in Navigation 3.
    alias(libs.plugins.kotlin.serialization)
    // AboutLibraries Metadata Generation (Generate dependency license metadata during Android variant builds)
    // The Settings about page reads the generated R.raw.aboutlibraries file instead of maintaining a hand-written license list.
    alias(libs.plugins.aboutlibraries.android)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

fun getGitCommitCount(): Int {
    return try {
        val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
            .start()
        val result = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        result.toIntOrNull() ?: 1
    } catch (e: Exception) {
        1
    }
}

fun getGitHash(): String {
    return try {
        val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .start()
        val result = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        result.takeIf { it.isNotBlank() } ?: "unknown"
    } catch (e: Exception) {
        "unknown"
    }
}

android {
    namespace = "com.viel.oto"
    compileSdk = 37

    val properties = Properties()
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    if (keystorePropertiesFile.exists()) {
        try {
            properties.load(keystorePropertiesFile.inputStream())
        } catch (e: Exception) {
            println("Warning: Could not load keystore.properties file: ${e.message}")
        }
    }
    val storeFile = properties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
    val storePassword = properties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
    val keyAlias = properties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
    val keyPassword = properties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")
    val hasCustomSigning = storeFile != null && storePassword != null && keyAlias != null && keyPassword != null

    defaultConfig {
        applicationId = "com.viel.oto"
        // SDK Level Bump (Raise minSdk to 33 to support hardware-level blur effects)
        // This targets Android 13 (API 33) to allow hardware-accelerated window blur rendering.
        minSdk = 33
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = getGitCommitCount()
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        @Suppress("UnstableApiUsage")
        androidResources {
            // App Locale Packaging (Keep every declared app-language resource in the APK)
            // The filtered list mirrors locales_config.xml so Android settings and packaged resources stay in sync.
            localeFilters += listOf("en", "zh-rCN", "zh-rHK", "zh-rTW", "ja", "fr", "de", "ru", "es", "pt")
        }
        vectorDrawables {
            useSupportLibrary = true
        }

        manifestPlaceholders["appName"] = "Oto"
    }

    signingConfigs {
        if (hasCustomSigning) {
            register("releaseCustom") {
                this.storeFile = rootProject.file(storeFile!!)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (hasCustomSigning) {
                signingConfigs.getByName("releaseCustom")
            } else {
                signingConfigs.getByName("debug")
            }
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug.${getGitHash()}"
            buildConfigField("Boolean", "LOG_ENABLED", "true")
            buildConfigField("int", "BUILD_LEVEL", "0")
            manifestPlaceholders["appName"] = "Oto (Debug)"
        }

        release {
            isProfileable = true
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (hasCustomSigning) signingConfigs.getByName("releaseCustom") else signingConfigs.getByName("debug")
            buildConfigField("Boolean", "LOG_ENABLED", "false")
            buildConfigField("int", "BUILD_LEVEL", "2")
            manifestPlaceholders["appName"] = "Oto"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("nonMinifiedRelease") {
            signingConfig = if (hasCustomSigning) signingConfigs.getByName("releaseCustom") else signingConfigs.getByName("debug")
            buildConfigField("Boolean", "LOG_ENABLED", "false")
            buildConfigField("int", "BUILD_LEVEL", "2")
            manifestPlaceholders["appName"] = "Oto (NonMinified)"
            matchingFallbacks += listOf("release")
        }

        create("benchmarkRelease") {
            isDebuggable = true
            signingConfig = if (hasCustomSigning) signingConfigs.getByName("releaseCustom") else signingConfigs.getByName("debug")
            buildConfigField("Boolean", "LOG_ENABLED", "true")
            buildConfigField("int", "BUILD_LEVEL", "1")
            manifestPlaceholders["appName"] = "Oto (Benchmark)"
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        // Runtime Version Source (Expose Gradle-owned version metadata to UI code)
        // AboutScreen reads BuildConfig.VERSION_NAME so translated resources only keep the localized display template.
        buildConfig = true
        compose = true
    }
    testOptions {
        unitTests {
            // Robolectric Resource Rendering Tests (Package app resources for local JVM tests)
            // Feedback plural rendering must exercise Android Resources directly, so unit tests need access to generated string and plurals tables.
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Stage 1 Foundation Modules (Resolve extracted settings, network policy, and lifecycle policy from Gradle modules)
    implementation(project(":settings:model"))
    implementation(project(":network:policy"))
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(project(":data:store"))
    implementation(project(":library:vfs"))
    implementation(project(":media:metadata"))

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
    // Compose AnimatedVectorDrawable support for bottom nav tab selected-state morphing.
    implementation(libs.androidx.compose.animation.graphics)
    // Migrate Navigation 2 to Navigation 3 (Transition core navigation containers) Replaced old navigation-compose with navigation3 runtime and UI.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Media3 (for audiobook playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    // Media3 Cache Runtime (Adds explicit cache and database artifacts for playback/download storage)
    // The playback cache graph uses these APIs directly instead of relying on transitive ExoPlayer dependencies.
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    // Coil (image loading for cover art)
    implementation(libs.coil.compose)

    // Palette (for dynamic background colors)
    implementation(libs.androidx.palette.ktx)

    // MaterialKolor (HCT/Monet dynamic color) Generates full Material 3 ColorSchemeS from a seed color (wallpaper or cover art).
    implementation(libs.material.kolor)
    // AboutLibraries Material 3 UI (Display Gradle-generated open-source license metadata in Compose)
    // This keeps the visible license page synchronized with the dependency graph resolved by Gradle.
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.datastore.preferences)

    // Introduce Haze Blur (Transition backdrop blur to dev.chrisbanes.haze) Replaced backdrop blur dependency with Haze libraries.
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
    // Transitional KSP Processor Classpath (Keep app KSP resolvable while Moshi DTOs still live in the app module)
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

    // Koin Dependency Injection (Replace manual DI graphs with Koin modules)
    // Provides android runtime, Compose integration, and ViewModel DSL for the app shell and scenes.
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    testImplementation(libs.junit)
    // API Testing Support (Utilize MockWebServer for local integration testing)
    // Solidifies API behaviors including HTTP methods, path routing, and authentication headers.
    testImplementation(libs.mockwebserver)
    // Search Flow Backpressure Test Support (Enable deterministic virtual-time assertions)
    // The search scene uses debounce to shield Room from keystroke churn, so JVM tests need TestScope scheduler controls.
    testImplementation(libs.kotlinx.coroutines.test)
    // AndroidX DataStore JVM Runtime (Runs Android API-dependent storage tests against a realistic SDK)
    // Prevents local unit tests from exercising android.jar stub defaults that never occur on the app's supported devices.
    testImplementation(libs.robolectric)
    // Room Migration Test Support (Validates exported schemas and non-destructive database upgrades)
    // Download metadata migrations must prove existing user data can upgrade without destructive rebuilds.
    testImplementation(libs.androidx.room.testing)
    // Koin Test Support (Verify module completeness and provide KoinTest helpers for JVM tests)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    // UI Tooling (Required as implementation to resolve Missing ComposeViewAdapter in Previews)
    implementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
