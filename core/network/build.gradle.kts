plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.network"
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
            artifactId = "core-network"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// Phase 3 Task 3.2: strict explicit-API mode. See docs/publishing/PHASE-03-api-surface.md.
kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:common"))
    // Public API returns kotlinx.coroutines Flow/StateFlow, so coroutines must be `api`
    // (implementation would keep those return types off a consumer's classpath).
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:security"))
    // C3→C4 route binder consumes discovery endpoint models (one-way dep; no cycle).
    implementation(project(":core:discovery"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // TEST-ONLY: self-signed cert generation for JVM handshake tests (C4.1).
    // Production certs come from the Android platform keystore (docs/security.md
    // §2) — bouncycastle never ships in the AAR/APK.
    testImplementation(libs.bouncycastle.pkix)
}
