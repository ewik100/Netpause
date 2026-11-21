val stableKeystorePath = System.getenv("NETPAUSE_KEYSTORE_FILE")
val stableStorePassword = System.getenv("NETPAUSE_KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("NETPAUSE_KEY_ALIAS") ?: "netpause"
val stableKeyPassword = System.getenv("NETPAUSE_KEY_PASSWORD") ?: stableStorePassword
val stableSigningEnabled =
    !stableKeystorePath.isNullOrBlank() &&
    !stableStorePassword.isNullOrBlank() &&
    !stableKeyPassword.isNullOrBlank() &&
    file(stableKeystorePath).exists()

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
        versionCode = 12
        versionName = "1.5"
    }

    signingConfigs {
        if (stableSigningEnabled) {
            create("stable") {
                storeFile = file(stableKeystorePath!!)
                storeType = "PKCS12"
                storePassword = stableStorePassword!!
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword!!
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (stableSigningEnabled) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
