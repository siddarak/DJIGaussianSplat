plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.drones"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.drones"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/rxjava.properties",
                "META-INF/proguard/androidx-*.pro"
            )
        }
        doNotStrip.addAll(listOf(
            "*/armeabi-v7a/libdjivideo.so",
            "*/arm64-v8a/libdjivideo.so",
            "*/armeabi-v7a/libSDKRelativeJNI.so",
            "*/arm64-v8a/libSDKRelativeJNI.so",
            "*/armeabi-v7a/libFlyForbid.so",
            "*/arm64-v8a/libFlyForbid.so",
            "*/armeabi-v7a/libduml_vision_pencil.so",
            "*/arm64-v8a/libduml_vision_pencil.so"
        ))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // DJI Mobile SDK V5
    implementation("com.dji:dji-sdk-v5-aircraft:5.9.0")
    compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.9.0")
    runtimeOnly("com.dji:dji-sdk-v5-networkImp:5.9.0")
}