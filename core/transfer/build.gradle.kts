plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.transfer"
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
            artifactId = "core-transfer"

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
    // Public API returns kotlinx.coroutines Flow/StateFlow (e.g. activeTransfers), so coroutines
    // must be `api` (implementation would keep those return types off a consumer's classpath).
    api(libs.kotlinx.coroutines.core)
    implementation(project(":core:security"))
    implementation(project(":core:network"))
    // Phase 4 (ADR-024): transfer no longer depends on core:persistence. Storage is behind the
    // transfer-owned TransferStore port; core:engine's RoomTransferStore adapts Room to it. This
    // keeps Room/SQLCipher (4 native ABIs) off a lightweight core-transfer consumer's classpath.
    implementation(project(":core:discovery"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
