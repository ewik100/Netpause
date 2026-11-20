plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.netpause.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.netpause.app"
        minSdk = 26
        targetSdk = 33
        versionCode = 5
        versionName = "0.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
