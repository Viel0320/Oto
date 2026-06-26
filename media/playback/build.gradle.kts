plugins {
    id("oto.android.library")
}

// Media Playback Module (Owns playback plans, Media3 controller runtime, VFS playback data source, and recovery policies)
android {
    namespace = "com.viel.oto.media.playback"

    testOptions {
        unitTests {
            // Playback data-source and cache tests exercise Android URI, cache-dir, and Media3 behavior through Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":shared"))
    api(project(":data:store"))
    api(project(":library:vfs"))
    api(project(":media:metadata"))
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.datasource)
    api(libs.androidx.media3.session)

    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
