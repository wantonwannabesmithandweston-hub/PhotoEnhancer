
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
        versionCode = 8
        versionName = "1.7"

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
    // Persistent, on-device enhancement work
    implementation("androidx.work:work-runtime-ktx:2.12.0")

    // Existing Real-ESRGAN support
    implementation("com.google.android.gms:play-services-litert:17.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

}
