plugins {
    id("oto.android.library")
}

// Event Android Library Module (Keeps event contracts resource-neutral while matching the app's Android variant graph)
android {
    namespace = "com.viel.oto.event"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
