plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.netpause.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.netpause.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "1.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
