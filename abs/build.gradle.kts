import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// ABS Module (Owns AudiobookShelf protocol mapping, sync, auth, progress, cover cache, and VFS adapter)
android {
    namespace = "com.viel.oto.abs"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // ABS tests exercise DataStore, Android context, WorkManager-facing worker seams, and protocol resources through Robolectric-ready JVM tests.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":data:store"))
    api(project(":library:vfs"))
    api(project(":library:import"))
    api(project(":media:metadata"))
    api(project(":media:playback"))

    implementation(project(":settings:model"))
    implementation(project(":network:policy"))
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(project(":work:policy"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.android)
    implementation(libs.moshi)
    implementation(libs.okhttp)

    ksp(libs.moshi.kotlin.codegen)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.moshi.kotlin)
    testImplementation(libs.robolectric)
}
