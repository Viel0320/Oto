// Top-level build file where you can add configuration options common to all subprojects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Android Runtime Modules (Compile extracted Android library modules without app packaging tasks)
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    // AboutLibraries Android Plugin (Declared once for app-module application)
    // The app module applies this plugin so variant builds generate the raw license metadata resource automatically.
    alias(libs.plugins.aboutlibraries.android) apply false
}
