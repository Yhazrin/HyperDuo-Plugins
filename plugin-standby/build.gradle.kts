plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "local.hyperduo.standby"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.hyperduo.standby"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }
    buildFeatures { aidl = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }
dependencies {
    implementation(project(":plugin-api"))
    implementation(project(":plugin-ui"))
    implementation("dev.rikka.shizuku:api:13.1.5") { exclude(group = "androidx.annotation", module = "annotation") }
    implementation("dev.rikka.shizuku:provider:13.1.5") { exclude(group = "androidx.annotation", module = "annotation") }
}
