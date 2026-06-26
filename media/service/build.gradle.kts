plugins {
    id("oto.android.library")
}

// Media Service Module (Owns Media3 services, foreground notifications, audio focus, and service adapters)
android {
    namespace = "com.viel.oto.media.service"

    testOptions {
        unitTests {
            // Service policy tests stay on JVM while Android resources are packaged for Robolectric-ready cases.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":shared"))
    api(project(":media:playback"))

    implementation(project(":data:store"))
    implementation(project(":application"))
    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.koin.android)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
