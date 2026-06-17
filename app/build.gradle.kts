plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ctf.bilisb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ctf.bilisb"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }

    buildFeatures {
        buildConfig = true
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
    compileOnly("io.github.libxposed:api:101.0.1")
}
