plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.hujunhan.hamsterwheeltracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hujunhan.hamsterwheeltracker"
        minSdk = 31
        targetSdk = 31
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

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("test").resources.srcDir("../../shared/test-vectors")
    }
}

dependencies {
    val cameraXVersion = "1.4.2"
    val roomVersion = "2.6.1"

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")

    implementation("androidx.room:room-runtime:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Small embedded HTTP server used for the read-only LAN dashboard.
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // Official OpenCV Android AAR from Maven Central. 4.10 is intentionally
    // conservative for the dedicated Android 12 device.
    implementation("org.opencv:opencv:4.10.0")

    testImplementation("junit:junit:4.13.2")
}
