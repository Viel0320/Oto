plugins {
    id("oto.android.library")
}

// Runtime Observability Module (Keeps Android Logcat, Coil, and OkHttp diagnostics outside the app module)
android {
    namespace = "com.viel.oto.runtime.observability"
}

dependencies {
    implementation(libs.coil)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
