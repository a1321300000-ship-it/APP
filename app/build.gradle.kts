plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val fixedKeystoreFile = rootProject.file("vault-release-key.jks")
val storePasswordValue = System.getenv("VAULT_KEYSTORE_PASSWORD")
val fixedSigningAvailable =
    fixedKeystoreFile.exists() &&
    !storePasswordValue.isNullOrBlank() &&
    !System.getenv("VAULT_KEY_ALIAS").isNullOrBlank()

android {
    namespace = "he.vault.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "he.vault.app"
        minSdk = 24
        targetSdk = 28
        versionCode = 4
        versionName = "1.3"
    }

    // This APK is distributed directly, not through Google Play.
    // Keep the project's existing targetSdk behavior and suppress only Play's target-age policy lint.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    signingConfigs {
        if (fixedSigningAvailable) {
            create("vaultFixed") {
                storeFile = fixedKeystoreFile
                // The supplied keystore is PKCS12; its private-key password matches the store password.
                storePassword = storePasswordValue
                keyAlias = System.getenv("VAULT_KEY_ALIAS")
                keyPassword = storePasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
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


dependencies {
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
}
