plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.yixing233.hyperduo"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.hyperduo.airpods"
        minSdk = 30
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }
    buildFeatures { buildConfig = true; compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources { merges += "META-INF/xposed/*" } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    implementation(project(":plugin-ui"))
    implementation(project(":plugin-api"))
    compileOnly(files(rootProject.file("app/libs/classes.jar")))
}
