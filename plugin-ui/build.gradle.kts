plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.hyperduo.plugin.ui"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    api("top.yukonga.miuix.kmp:miuix-android:0.5.1")
    api("androidx.activity:activity-compose:1.10.1")
    api("androidx.compose.foundation:foundation:1.8.2")
    api("androidx.compose.ui:ui:1.8.2")
}
