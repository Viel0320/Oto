plugins {
    id("oto.android.library")
}

// Library VFS Module (Owns SAF/WebDAV source adapters, virtual file access, and source range caching)
android {
    namespace = "com.viel.oto.library.vfs"

    testOptions {
        unitTests {
            // WebDAV and SAF-adjacent unit tests exercise Android URI and context behavior through Robolectric.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    api(project(":data:store"))

    implementation(project(":runtime:observability"))
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.koin.android)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.robolectric)
}
