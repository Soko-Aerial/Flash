plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.common"
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
            artifactId = "core-common"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

// Phase 3 Task 3.2: strict explicit-API mode. Every declaration that is part of the
// module's API must state its visibility, so nothing leaks into the published ABI by
// omission. See docs/publishing/PHASE-03-api-surface.md.
kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
