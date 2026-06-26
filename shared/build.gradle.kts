plugins {
    id("oto.android.library")
}

// Shared Module (Owns cross-layer utilities and the consolidated user-visible resource catalog)
android {
    namespace = "com.viel.oto.shared"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }
}

dependencies {
    testImplementation(libs.junit)
}
