plugins {
    id("oto.android.library")
}

// Library Import Module (Owns scanning, import orchestration, source-root lifecycle, and availability checks)
android {
    namespace = "com.viel.oto.library.importing"

    testOptions {
        unitTests {
            // Library root and scan tests exercise Android context, URI, WorkManager, and DataStore behavior through Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":data:store"))
    api(project(":library:vfs"))
    api(project(":media:metadata"))

    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(project(":shared"))
    implementation(project(":work:policy"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
