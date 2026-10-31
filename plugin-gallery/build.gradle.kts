plugins { id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "local.duogallerywidget"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.hyperduo.gallery"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }
    packaging { resources { merges += "META-INF/xposed/*" } }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) } }

dependencies {
    implementation(project(":plugin-ui"))
    implementation(project(":plugin-api"))
    compileOnly(files(rootProject.file("app/libs/classes.jar")))
}
