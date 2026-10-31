plugins { id("com.android.application") }
android {
    namespace = "local.duogallerywidget"
    compileSdk = 35
    defaultConfig {
        applicationId = "local.hyperduo.gallery"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
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
