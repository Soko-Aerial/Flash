plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.security"
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
            artifactId = "core-security"

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
    // Phase 4 (ADR-024): security no longer depends on core:persistence / Room. The only coupling
    // was the unused RoomTrustedStore adapter (deleted); the live trust store is the
    // SharedPreferences-backed AndroidPreferencesTrustStore. This keeps Room/SQLCipher off the
    // classpath of security and of everything downstream of it (notably core:transfer).
    implementation(libs.androidx.core.ktx)
    // Public API returns kotlinx.coroutines Flow/StateFlow (FlashPairingProtocol/trust), so
    // coroutines must be `api` — an `implementation` scope keeps those return types off a
    // downstream consumer's classpath. lifecycle-runtime-ktx below stays for the Main dispatcher.
    api(libs.kotlinx.coroutines.core)
    // Provides kotlinx-coroutines (Flow/StateFlow used by FlashPairingProtocol). Previously leaked
    // in transitively via Room; now declared directly, matching core:network / core:discovery.
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
