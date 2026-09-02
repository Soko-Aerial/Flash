plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.transfer.flash.ui.calling"

    compileSdk = 37

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
    buildFeatures {
        compose = true
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
            artifactId = "ui-callui"

            afterEvaluate {
                from(components["release"])
            }
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    // api(), not implementation(): FlashCallScreen's signature exposes FlashCallUiState and
    // FlashCallMedia, so a consumer of this module cannot call it without them on the compile
    // classpath. This also re-exports webrtc-kmp transitively (:core:calling api()s it, ADR-025),
    // which FlashVideoRenderer needs for SurfaceViewRenderer.
    api(project(":core:calling"))
    implementation(project(":ui:theme"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.foundation:foundation")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // webrtc-kmp is exposed via api() from :core:calling (ADR-025); no direct dep here.
    testImplementation(libs.junit)
}
