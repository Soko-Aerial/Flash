plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.calling"

    compileSdk = 35

    defaultConfig {
        minSdk = 24

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "core-calling"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// Strict explicit-API mode, matching the other core modules (ADR-023).
kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:common"))
    // Public API exposes Flow/StateFlow call-state streams.
    api(libs.kotlinx.coroutines.core)
    // WebRTC media engine (ADR-025): MIT, wraps io.github.webrtc-sdk:android (BSD-3).
    api(libs.webrtc.kmp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
