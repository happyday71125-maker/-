plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.prolauncher.ai"
    compileSdk = 35
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
        applicationId = "app.prolauncher.ai.v3"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.let { 105000 + it } ?: 106600)
            versionName = "0.0.3"
    }
    if (file("fixed.keystore").let { it.exists() && it.length() > 100L }) {
        signingConfigs { getByName("debug") { storeFile = file("fixed.keystore") } }
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs { pickFirsts += setOf("**/libc++_shared.so", "**/libonnxruntime.so", "**/libonnxruntime4j_jni.so") }
        resources { excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
