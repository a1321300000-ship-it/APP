plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = rootProject.file("vault-release-key.jks")
val fixedSigningAvailable =
    fixedKeystoreFile.exists() &&
    !System.getenv("VAULT_KEYSTORE_PASSWORD").isNullOrBlank() &&
    !System.getenv("VAULT_KEY_ALIAS").isNullOrBlank() &&
    !System.getenv("VAULT_KEY_PASSWORD").isNullOrBlank()

android {
    namespace = "he.vault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "he.vault.app"
        minSdk = 24
        targetSdk = 28
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        if (fixedSigningAvailable) {
            create("vaultFixed") {
                storeFile = fixedKeystoreFile
                storePassword = System.getenv("VAULT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VAULT_KEY_ALIAS")
                keyPassword = System.getenv("VAULT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (fixedSigningAvailable) {
                signingConfig = signingConfigs.getByName("vaultFixed")
            }
        }
        debug {
            isDebuggable = true
            if (fixedSigningAvailable) {
                signingConfig = signingConfigs.getByName("vaultFixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
