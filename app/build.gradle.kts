plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
}

android {
    namespace = "com.transfer.flash"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.transfer.flash"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // AGP 9.3+ DSL: one flag enables both R8 code shrinking/obfuscation and the
            // optimized resource-shrinker pipeline (replaces isMinifyEnabled +
            // isShrinkResources; default platform keep rules are included). No first-party
            // reflection exists in core/* (verified 2026-08-27); Room/SQLCipher/WebRTC ship
            // their own consumer rules.
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:persistence"))
    implementation(project(":core:security"))
    implementation(project(":core:discovery"))
    implementation(project(":core:network"))
    implementation(project(":core:transfer"))
    implementation(project(":core:messaging"))
    implementation(project(":core:engine"))
    implementation(project(":core:calling"))
    implementation(project(":core:ptt"))
    implementation(project(":ui:theme"))
    implementation(project(":ui:chat"))
    implementation(project(":ui:callui"))
    // PTT session overlay uses the platform permission seam (D7c) instead of a hand-rolled
    // ActivityResultLauncher, mirroring the composer voice-record flow.
    implementation(project(":ui:platform-shims"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.1")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Audit C1 (ADR-043): THIRD_PARTY_NOTICES.txt, generated from what the release APK actually ships and
// packaged as an asset for Settings → About → Open-source licences. Every variant shows the release
// list, because release is what gets distributed.
aboutLibraries {
    // offlineMode: the plugin would otherwise download licence texts during the build and silently leave
    // them out when offline. The texts are checked in under config/aboutlibraries/ instead.
    offlineMode = true
    collect {
        configPath = rootProject.file("config/aboutlibraries")
        // BOMs carry no code; listing them as "libraries" would only pad the notices.
        includePlatform = false
    }
    export { prettyPrint = true }
    exports {
        create("release") { outputFile = layout.buildDirectory.file("generated/aboutLibraries/release/aboutlibraries.json") }
    }
}

val thirdPartyNotices = tasks.register<ThirdPartyNoticesTask>("generateThirdPartyNotices") {
    platformName.set("Android")
    libraryDefinitions.set(layout.buildDirectory.file("generated/aboutLibraries/release/aboutlibraries.json"))
    dependsOn("exportLibraryDefinitionsRelease")
    outputDir.set(layout.buildDirectory.dir("generated/thirdPartyNotices"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(thirdPartyNotices, ThirdPartyNoticesTask::outputDir)
    }
}
