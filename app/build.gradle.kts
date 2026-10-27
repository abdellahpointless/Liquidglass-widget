import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.liquidgets"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.liquidgets"
        minSdk = 26
        targetSdk = 36
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "0.1.$versionCode"
    }

    signingConfigs {
        // shortcut: a committed debug key so test builds install over each other; Play releases need a private upload key.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
}
