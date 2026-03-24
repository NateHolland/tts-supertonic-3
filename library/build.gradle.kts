plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.cocoapods)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // CocoaPods is used ONLY for cinterop binding generation at build time.
    // Consumers of this SDK do NOT need CocoaPods — the ONNX Runtime symbols
    // are statically linked into the output framework.
    cocoapods {
        summary = "TTS Supertonic 2 — on-device text-to-speech SDK"
        homepage = "https://github.com/nate/tts-supertonic-2"
        ios.deploymentTarget = "16.0"
        version = "1.0.0"

        framework {
            baseName = "TtsSupertonic2"
            isStatic = true
        }

        pod("onnxruntime-objc") {
            version = "~> 1.19.2"
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.onnxruntime.android)
        }

        iosMain.dependencies {}
    }
}

android {
    namespace = "com.nate.tts"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
