plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true

            // The :library module uses CocoaPods for ONNX Runtime cinterop.
            // When linking the sample's framework, we need to tell the linker
            // where to find the onnxruntime-objc pod framework that was fetched
            // by the library's CocoaPods integration.
            val podsDir = project(":library").layout.buildDirectory
                .dir("cocoapods/synthetic/ios/build/Release-iphonesimulator").get().asFile
            val podsDeviceDir = project(":library").layout.buildDirectory
                .dir("cocoapods/synthetic/ios/build/Release-iphoneos").get().asFile
            val podFrameworkDir = when (iosTarget.name) {
                "iosArm64" -> podsDeviceDir
                else -> podsDir
            }
            linkerOpts("-F${podFrameworkDir}", "-framework", "onnxruntime_objc")
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":library"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.lifecycle.viewmodel.compose)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.kotlinx.coroutines.android)
        }
    }
}

android {
    namespace = "com.nate.tts.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nate.tts.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Include model assets from tts-model-pack
    sourceSets {
        getByName("main") {
            assets.srcDirs(
                "src/main/assets",
                "${project(":tts-model-pack").projectDir}/src/main/assets",
            )
        }
    }
}
