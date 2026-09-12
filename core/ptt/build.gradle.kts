plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.core.ptt"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The engine's inbound seams (ping routing, dedup, fail-closed rejection, audio magic) are
    // pure lambdas plus wire codecs, so they run as host-JVM unit tests. The class also calls
    // `android.util.Log` and `android.os.SystemClock`; returning default values for un-mocked
    // platform calls keeps those tests free of Robolectric. Anything opening a real
    // AudioRecord/AudioTrack stays out of scope on purpose — that needs a physical device.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
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
            artifactId = "core-ptt"
            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(project(":core:messaging"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
