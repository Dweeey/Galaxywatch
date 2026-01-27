plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.galaxywatch"
    compileSdk = 34 // Use stable SDK 34 to avoid preview bugs

    defaultConfig {
        applicationId = "com.example.galaxywatch"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    // --- Standard Wear OS & Google Libraries ---
    implementation("com.google.android.gms:play-services-wearable:18.1.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // --- Compose & UI (Hardcoded versions to ensure compatibility) ---
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // *** WEAR OS UI (Crucial: Using version 1.3.0) ***
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")

    // *** HEALTH SERVICES (The Sensor Code) ***
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.guava:guava:31.1-android")

    // --- Debugging Tools ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}