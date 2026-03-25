plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.example.galaxywatch"
    compileSdk = 35 // Use stable SDK 34 to avoid preview bugs

    aaptOptions {
        noCompress ("tflite")
    }

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
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // *** WEAR OS UI (Crucial: Using version 1.3.0) ***
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")

    // *** HEALTH SERVICES (The Sensor Code) ***
    implementation("androidx.health:health-services-client:1.1.0-alpha05")
    implementation("com.google.guava:guava:31.1-android")

    // --- Debugging Tools ---
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.1.0")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07") // Check for latest version
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")

//    Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.7.3")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha06") // Use latest alpha for records

//    QR
    implementation("com.google.zxing:core:3.5.3")

//    VOIP
    implementation("com.github.ZEGOCLOUD:zego_uikit_prebuilt_call_android:+")
    // 1. Google's Firebase Messaging (The Alarm Clock)
    implementation("com.google.firebase:firebase-messaging:23.4.0")
// Use latest version
    implementation("im.zego:zpns-fcm:2.8.0")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
// Optional but highly recommended for data formatting:
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
}