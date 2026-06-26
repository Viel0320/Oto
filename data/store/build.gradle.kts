import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

// Data Store Module (Owns Room, DataStore, gateway contracts, and persistence services outside the app shell)
android {
    namespace = "com.viel.oto.data.store"
    compileSdk = 37

    defaultConfig {
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            // Room and DataStore JVM tests use Robolectric resources when migration fixtures are loaded from assets.
            isIncludeAndroidResources = true
        }
    }
    sourceSets {
        // Room Schema Test Assets (Keeps migration tests pointed at the established checked-in schema fixtures)
        getByName("debug").assets.directories.add(rootProject.layout.projectDirectory.dir("app/schemas").asFile.path)
        getByName("androidTest").assets.directories.add(rootProject.layout.projectDirectory.dir("app/schemas").asFile.path)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":settings:model"))
    api(libs.androidx.datastore.preferences)
    api(libs.androidx.room.runtime)

    implementation(project(":runtime:lifecycle"))
    implementation(project(":runtime:observability"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)
    implementation(libs.androidx.room.ktx)

    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

// Room Schema Exporting (Keeps the established app/schemas release-policy path during module extraction)
ksp {
    arg("room.schemaLocation", rootProject.layout.projectDirectory.dir("app/schemas").asFile.path)
}
