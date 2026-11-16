
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.privatephoto.enhancer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.privatephoto.enhancer"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        // This personal-use build targets the user's modern 64-bit Android phone.
        // Avoid bundling unused x86 and 32-bit native runtimes in the APK.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        )
    }
}

dependencies {
    // Existing Real-ESRGAN support
    implementation("com.google.android.gms:play-services-litert:17.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    // On-device text recognition (bundled model)
    implementation("com.google.mlkit:text-recognition:16.0.0-beta3")

    // On-device face detection (bundled model)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Image processing and inpainting
    implementation("org.opencv:opencv:4.10.0")
}
