val sdkVersion = "1.3.0"
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.cocoapods)
    alias(libs.plugins.maven.publish)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        publishLibraryVariants("release")
    }

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // CocoaPods is used ONLY for cinterop binding generation at build time.
    // Consumers of this SDK do NOT need CocoaPods — the ONNX Runtime symbols
    // are statically linked into the output framework.
    cocoapods {
        summary = "TTS Supertonic 3 — on-device text-to-speech SDK"
        homepage = "https://github.com/NateHolland/tts-supertonic-3"
        ios.deploymentTarget = "16.0"
        version = sdkVersion

        framework {
            baseName = "TtsSupertonic3"
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

// ── Maven Central Publishing ──────────────────────────────────────────

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    // `-PnoSign` for a local (publishToMavenLocal) build without the release key.
    if (!project.hasProperty("noSign")) signAllPublications()

    coordinates("coach.kima.tts", "tts-supertonic-3", sdkVersion)

    pom {
        name.set("TTS Supertonic 3")
        description.set("Kotlin Multiplatform on-device text-to-speech SDK using ONNX Runtime with the Supertone supertonic-3 model.")
        url.set("https://github.com/NateHolland/tts-supertonic-3")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("nateholland")
                name.set("Nate Holland")
                email.set("nate@kima.coach")
                url.set("https://kima.coach")
            }
        }

        scm {
            url.set("https://github.com/NateHolland/tts-supertonic-3")
            connection.set("scm:git:https://github.com/NateHolland/tts-supertonic-3.git")
            developerConnection.set("scm:git:ssh://git@github.com/NateHolland/tts-supertonic-3.git")
        }
    }
}
