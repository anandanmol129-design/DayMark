plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.anmol.daymark"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "com.anmol.daymark"
        minSdk = 31
        targetSdk = 35
        versionCode = 26
        versionName = "1.19.0"
    }

    buildTypes {
        release {
            // R8 + resource shrinking make Compose far faster than the debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same key as the debug builds already installed, so updates keep their data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui:1.13.0-alpha01")
    implementation("androidx.compose.ui:ui-tooling-preview:1.13.0-alpha01")
    implementation("androidx.compose.foundation:foundation:1.13.0-alpha01")
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.13.0-alpha01")
}
