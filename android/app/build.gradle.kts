plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.notrace.messenger"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.notrace.messenger"
        minSdk = 26 // Android 8.0+, per the plan
        targetSdk = 34
        versionCode = 1
        versionName = "0.5.0-phase5"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    // --- Jetpack Compose ---
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended") // Phase 6: real icons replacing text-button placeholders
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")

    // --- Background work (disappearing-message engine, Phase 3) ---
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // --- Local encrypted storage ---
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // --- Security / Keystore ---
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")

    // --- Networking (Phase 2+) ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0") // WebSocket client to the signaling server
    implementation("io.getstream:stream-webrtc-android:1.3.10") // WebRTC data channels + audio/video (Phase 2/4) — maintained fork; Google stopped publishing google-webrtc

    // --- Cryptography (Phase 2): libsignal-client — same X3DH + Double
    // Ratchet implementation Signal itself uses. Published to Maven Central
    // under org.signal since v0.24+; verify the latest version at
    // https://mvnrepository.com/artifact/org.signal/libsignal-client
    // before building — pin an exact version, don't float on a range.
    implementation("org.signal:libsignal-client:0.58.3")

    // --- Coroutines (WebSocket client, session ops off the main thread) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- Testing ---
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
