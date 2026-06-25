import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Runtime Observability Module (Keeps Android Logcat, Coil, and OkHttp diagnostics outside the app module)
android {
    namespace = "com.viel.oto.runtime.observability"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.coil)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
}
