buildscript {
    dependencies {
        // AGP 9 includes Kotlin support. This pins the compiler and compiler plugins
        // to the Kotlin release documented as compatible with the chosen AGP line.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
