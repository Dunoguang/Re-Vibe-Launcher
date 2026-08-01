plugins {
    id("com.android.application")
}

val signingKeystorePath = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = (signingKeystorePath != null && file(signingKeystorePath).exists() && signingStorePassword != null)

android {
    namespace = "com.dng.revibe.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dng.revibe.launcher"
        minSdk = 27
        targetSdk = 37
        // versionCode 由 CI 注入（基于 commit 数，稳定可命中构建缓存），本地默认 1
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingKeystorePath!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val shizuku_version = "13.1.5"
    implementation("dev.rikka.shizuku:api:$shizuku_version")
    implementation("dev.rikka.shizuku:provider:$shizuku_version")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.code.gson:gson:2.11.0")
}
