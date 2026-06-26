plugins {
    id("oto.android.library")
}

// Work Policy Module (Owns reusable WorkManager queue semantics without owning Worker implementations)
android {
    namespace = "com.viel.oto.work.policy"
}

dependencies {
    api(project(":data:store"))
    api(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}
