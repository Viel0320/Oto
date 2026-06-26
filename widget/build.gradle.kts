plugins {
    id("oto.android.library")
    alias(libs.plugins.kotlin.compose)
}

// Widget Module (Owns Glance rendering, receivers, and widget-local state storage)
android {
    namespace = "com.viel.oto.widget"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":runtime:observability"))
    implementation(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
}
