plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.yixing233.hyperduo"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.hyperduo.airpods"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging { resources { merges += "META-INF/xposed/*" } }
}

dependencies {
    implementation(project(":plugin-api"))
    compileOnly(files(rootProject.file("app/libs/classes.jar")))
}
