import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

// Work Policy Module (Owns reusable WorkManager queue semantics without owning Worker implementations)
android {
    namespace = "com.viel.oto.work.policy"
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
    api(project(":data:store"))
    api(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
}
