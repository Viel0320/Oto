plugins {
    id("oto.android.library")
    alias(libs.plugins.ksp)
}

// ABS Module (Owns AudiobookShelf protocol mapping, sync, auth, progress, cover cache, and VFS adapter)
android {
    namespace = "com.viel.oto.abs"

    testOptions {
        unitTests {
            // ABS tests exercise DataStore, Android context, WorkManager-facing worker seams, and protocol resources through Robolectric-ready JVM tests.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":library:import"))
    api(project(":media:playback"))

    implementation(project(":data:store"))
    implementation(project(":library:vfs"))
    implementation(project(":media:metadata"))
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(project(":shared"))
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
