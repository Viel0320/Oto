plugins {
    id("oto.android.library")
}

// Media Metadata Module (Owns parser and manifest logic without packaging playback services or UI screens)
android {
    namespace = "com.viel.oto.media.metadata"

    testOptions {
        unitTests {
            // Parser and cover tests exercise Android bitmap, palette, and URI behavior through Robolectric when needed.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":data:store"))
    api(project(":library:vfs"))

    implementation(project(":runtime:observability"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.koin.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
