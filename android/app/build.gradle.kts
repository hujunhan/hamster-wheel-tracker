plugins {
    id("com.android.application")
}

android {
    namespace = "com.hujunhan.hamsterwheeltracker"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hujunhan.hamsterwheeltracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val cameraXVersion = "1.6.2"

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core:1.19.0")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    testImplementation("junit:junit:4.13.2")
}
