plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "pl.somaskan.questgpt2"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.somaskan.questgpt2"
        minSdk = 34
        targetSdk = 35
        versionCode = 20300 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        versionName = "2.3." + (System.getenv("GITHUB_RUN_NUMBER") ?: "1")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        getByName("debug") { ndk { abiFilters += "x86_64" } }
        getByName("release") { ndk { abiFilters += "arm64-v8a" } }
    }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes.add("META-INF/LICENSE") }
    lint { abortOnError = true }
}

dependencies {
    implementation("com.meta.spatial:meta-spatial-sdk:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:0.14.0")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:0.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
