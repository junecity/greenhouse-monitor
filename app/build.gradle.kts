plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun quotedBuildValue(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val armOnlyBuild = providers.gradleProperty("GREENHOUSE_ARM_ONLY")
    .orElse(providers.environmentVariable("GREENHOUSE_ARM_ONLY"))
    .map(String::toBoolean)
    .getOrElse(false)

android {
    namespace = "studio.junecity.greenhouse"
    compileSdk = 36

    defaultConfig {
        applicationId = "studio.junecity.greenhouse"
        minSdk = 29
        targetSdk = 36
        versionCode = 16
        versionName = "2.7.3"
        if (armOnlyBuild) {
            ndk {
                abiFilters += setOf("armeabi-v7a", "arm64-v8a")
            }
        }
        val cloudValue = { name: String ->
            providers.gradleProperty(name).orElse(providers.environmentVariable(name)).getOrElse("")
        }
        buildConfigField("String", "GREENHOUSE_AWS_REGION", quotedBuildValue(cloudValue("GREENHOUSE_AWS_REGION")))
        buildConfigField("String", "GREENHOUSE_COGNITO_USER_POOL_ID", quotedBuildValue(cloudValue("GREENHOUSE_COGNITO_USER_POOL_ID")))
        buildConfigField("String", "GREENHOUSE_COGNITO_APP_CLIENT_ID", quotedBuildValue(cloudValue("GREENHOUSE_COGNITO_APP_CLIENT_ID")))
        buildConfigField("String", "GREENHOUSE_AWS_API_BASE_URL", quotedBuildValue(cloudValue("GREENHOUSE_AWS_API_BASE_URL")))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.12.3")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.amplifyframework:aws-auth-cognito:2.43.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
