import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

android {
    namespace = "com.viel.aplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.viel.aplayer"
        // 根据用户要求，将 minSdk 从 31 提升至 33 (Android 13)，以适配 miuix-blur 模糊库所需的硬件级高阶模糊渲染。
        minSdk = 32
        //noinspection OldTargetApi
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        @Suppress("UnstableApiUsage")
        androidResources {
            localeFilters += listOf("zh", "en")
        }
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "password"
            keyAlias = "aplayer"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    
    // Media3 (for audiobook playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    // Coil (image loading for cover art)
    implementation(libs.coil.compose)

    // Palette (for dynamic background colors)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.androidx.datastore.preferences)

    // 引入新依赖 miuix-blur 模糊库，实现 Android 13 原生硬件级视窗高阶磨砂模糊渲染。
    implementation(libs.miuix.blur)

    // 详尽的中文注释：重新声明并引入 Jetpack Glance 相关依赖，包括核心声明式包、桌面 AppWidget 小组件包，以及 Material 3 配色自适应支持包
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Room (local database)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager & DocumentFile
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    // WebDAV 标准件使用 OkHttp 统一执行 PROPFIND、GET 和 Range 流式读取。
    implementation(libs.okhttp)
    // ABS REST 客户端统一使用 Moshi 做结构化 JSON 解析，避免把协议字段名散落在字符串解析代码里。
    implementation(libs.moshi)
    implementation(libs.moshi.kotlin)
    // Moshi DTO 使用代码生成适配器，减少反射成本并保证阶段 1 样本解析稳定。
    ksp(libs.moshi.kotlin.codegen)

    testImplementation(libs.junit)
    // ABS API 客户端阶段 1 需要用 MockWebServer 固化 method、path 和鉴权头行为。
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// 配置 KSP 插件传入 Room Schema 物理导出相对路径，激活编译时 Schema 文件自动落盘，保证数据库版本可留痕追溯 (H-16, H-17)
ksp {
    arg("room.schemaLocation", "${projectDir}/schemas")
}
