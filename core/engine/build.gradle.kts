plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.engine"
    compileSdk = 35
    // Namespaces all bundled resources (e.g. @drawable/flash_bolt) so a consuming app's
    // resources can never collide with this published AAR's.
    resourcePrefix = "flash_"

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
            artifactId = "core-engine"

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
    api(project(":core:security"))
    api(project(":core:discovery"))
    api(project(":core:network"))
    api(project(":core:transfer"))
    api(project(":core:messaging"))
    api(project(":core:persistence"))
    // Engine is the composition root that opens/closes the encrypted Room DB (Flash.create):
    // it needs Room's Migration + RoomDatabase.close() on its own compile classpath. Kept
    // `implementation` (not `api`) so Room stays an internal detail and is not re-leaked to
    // library consumers — consistent with the persistence decoupling (ADR-024).
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
}
