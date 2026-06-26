plugins {
    id("oto.android.library")
}

// Lifecycle Android Library Module (Keeps graph shutdown ordering variant-aware without depending on app startup)
android {
    namespace = "com.viel.oto.runtime.lifecycle"
}

dependencies {
    testImplementation(libs.junit)
}
