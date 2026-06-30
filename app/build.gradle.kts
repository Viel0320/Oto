import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

fun getReleaseVersionName(): String {
    val releaseDatePattern = Regex("\\d{2}\\.\\d{2}\\.\\d{2}")
    val ciReleaseBuild = System.getenv("OTO_CI_RELEASE_BUILD").equals("true", ignoreCase = true)
    val ciReleaseDate = System.getenv("OTO_RELEASE_DATE")?.trim().orEmpty()

    if (ciReleaseBuild) {
        if (ciReleaseDate.isBlank()) {
            throw GradleException("OTO_RELEASE_DATE is required for CI release builds.")
        }
        if (!releaseDatePattern.matches(ciReleaseDate)) {
            throw GradleException("OTO_RELEASE_DATE must use yy.MM.dd format.")
        }
        return ciReleaseDate
    }

    if (ciReleaseDate.isNotBlank()) {
        if (!releaseDatePattern.matches(ciReleaseDate)) {
            throw GradleException("OTO_RELEASE_DATE must use yy.MM.dd format.")
        }
        return ciReleaseDate
    }

    return LocalDate.now(ZoneId.of("Asia/Shanghai"))
        .format(DateTimeFormatter.ofPattern("yy.MM.dd"))
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
    // CI Release Signing Gate (Fail release publishing when the workflow requires a real release keystore)
    // CI release mode accepts only environment-provided signing values so a workspace keystore.properties file cannot override the ephemeral runner keystore.
    val requireReleaseSigning = System.getenv("OTO_REQUIRE_RELEASE_SIGNING").equals("true", ignoreCase = true)
    val storeFile = if (requireReleaseSigning) {
        System.getenv("KEYSTORE_FILE")
    } else {
        properties.getProperty("KEYSTORE_FILE") ?: System.getenv("KEYSTORE_FILE")
    }
    val storePassword = if (requireReleaseSigning) {
        System.getenv("KEYSTORE_PASSWORD")
    } else {
        properties.getProperty("KEYSTORE_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
    }
    val keyAlias = if (requireReleaseSigning) {
        System.getenv("KEY_ALIAS")
    } else {
        properties.getProperty("KEY_ALIAS") ?: System.getenv("KEY_ALIAS")
    }
    val keyPassword = if (requireReleaseSigning) {
        System.getenv("KEY_PASSWORD")
    } else {
        properties.getProperty("KEY_PASSWORD") ?: System.getenv("KEY_PASSWORD")
    }
    val hasCustomSigning = !storeFile.isNullOrBlank() &&
        !storePassword.isNullOrBlank() &&
        !keyAlias.isNullOrBlank() &&
        !keyPassword.isNullOrBlank()
    if (requireReleaseSigning && !hasCustomSigning) {
        throw GradleException("Release signing is required, but KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, or KEY_PASSWORD is missing.")
    }

    defaultConfig {
        applicationId = "com.viel.oto"
        // SDK Level Bump (Raise minSdk to 33 to support hardware-level blur effects)
        // This targets Android 13 (API 33) to allow hardware-accelerated window blur rendering.
        minSdk = 33
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = getGitCommitCount()
        versionName = getReleaseVersionName()

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
                this.storeFile = rootProject.file(storeFile)
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
    // Android Feature Modules (Resolve extracted domain Android libraries through the app shell)
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(project(":data:store"))
    implementation(project(":library:vfs"))
    implementation(project(":library:import"))
    implementation(project(":media:metadata"))
    implementation(project(":media:playback"))
    implementation(project(":media:service"))
    implementation(project(":abs"))
    implementation(project(":work:policy"))
    implementation(project(":application"))
    implementation(project(":event"))
    implementation(project(":widget"))
    implementation(project(":shared"))
    implementation(project(":ui"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))

    // Coil ImageLoader Runtime (The application owns the process-wide image cache factory)
    implementation(libs.coil)

    // AboutLibraries Material 3 UI (Display Gradle-generated open-source license metadata in Compose)
    // This keeps the visible license page synchronized with the dependency graph resolved by Gradle.
    implementation(libs.aboutlibraries.compose.m3)

    // Jetpack Glance AppWidget Runtime (Routes playback snapshots from the app-owned sink to widget instances)
    implementation(libs.androidx.glance.appwidget)

    // WorkManager Runtime (Hosts the app-owned orphan cleanup Worker declared by the application manifest)
    implementation(libs.androidx.work.runtime.ktx)

    // Koin Dependency Injection (Replace manual DI graphs with Koin modules)
    // Provides Android runtime context integration for the app composition root.
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    // AndroidX DataStore JVM Runtime (Runs Android API-dependent storage tests against a realistic SDK)
    // Prevents local unit tests from exercising android.jar stub defaults that never occur on the app's supported devices.
    testImplementation(libs.robolectric)
}
