plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.outman.tvlauncher"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.outman.tvlauncher"
        minSdk = 24
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
}
