plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.persistence"
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

    sourceSets {
        // Room schema export location (C1.7): schemas are versioned in-repo.
        getByName("test").assets.directories.add("$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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
            artifactId = "core-persistence"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

// Phase 3 Task 3.2: strict explicit-API mode. See docs/publishing/PHASE-03-api-surface.md.
// NOTE: the Room data layer (entities, DAOs, FlashDatabase) is transitively forced public
// because :app wires the DB directly via FlashDatabaseOpener → FlashDatabase → *Dao accessors.
// Gating that layer behind @FlashInternalApi is Phase 4 (persistence-decoupling) work.
kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:common"))
    // Public API returns kotlinx.coroutines Flow/StateFlow (settings/DataStore), so coroutines
    // must be `api` (implementation would keep those return types off a consumer's classpath).
    api(libs.kotlinx.coroutines.core)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
}
