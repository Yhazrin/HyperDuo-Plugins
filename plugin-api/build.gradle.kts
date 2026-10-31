plugins { id("com.android.library") }
android {
 namespace = "io.github.hyperduo.plugin"
 compileSdk = 35
 defaultConfig { minSdk = 30 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
}
