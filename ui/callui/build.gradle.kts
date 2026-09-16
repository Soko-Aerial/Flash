plugins {
    // `com.android.library` is INCOMPATIBLE with the Kotlin Multiplatform plugin under AGP 9+, so a
    // converted module swaps it for these two rather than adding to it. Same pair as `:ui:theme`
    // (17/18), `:ui:shims` (19) and `:ui:chat` (20). Conversion: Phase 25 S3b.
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.compose)
    `maven-publish`
}

kotlin {
    // No explicitApi(): the ui/* modules were never part of the ADR-023 rollout (R1/R7).
    android {
        namespace = "com.transfer.flash.ui.calling"
        compileSdk = 37
        minSdk = 24

        // Was `defaultConfig { consumerProguardFiles(…) }` — dropped in SILENCE if omitted.
        optimization {
            consumerKeepRules.apply {
                file("consumer-rules.pro")
                publish = true
            }
        }

        // Replaces `buildTypes { release { … } }`. `proguard-rules.pro` becomes unreferenced (the
        // same note `:ui:chat` carries): minification was off, so the block never applied.
        localDependencySelection {
            selectBuildTypeFrom.set(listOf("release"))
        }

        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }

        withHostTest { }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:common"))
            // api(), not implementation(): FlashCallScreen's SIGNATURE exposes FlashCallUiState and
            // FlashCallMedia, so a consumer cannot call it without those types on its compile
            // classpath. This also re-exports webrtc-kmp transitively (:core:calling api()s it,
            // ADR-025) — which the renderer seam needs for VideoStreamTrack.
            api(project(":core:calling"))
            implementation(project(":ui:theme"))
            // Phase 25 S3b: `:ui:platform-shims` supplies the FlashBackHandler actual and (on
            // desktop) the Swing host the video renderer needs — replacing this module's last
            // `androidx.activity.compose.BackHandler` import.
            implementation(project(":ui:platform-shims"))

            // The multiplatform `compose.*` coordinates: on Android each redirects to the same
            // `androidx.compose.*` artifacts the BOM below pins, so the Android compile classpath is
            // unchanged; on jvm() they resolve to the Skiko desktop artifacts.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.animation)
            // The CMP @Preview annotation — an AAR-free coordinate, unlike androidx's.
            implementation(compose.components.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            // The androidx BOM tier stays Android-side ON PURPOSE: it is what pins the versions the
            // `compose.*` coordinates resolve to for Android, so this module's Android compile
            // classpath is bit-identical to 1.1.0's.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui)
            implementation(libs.androidx.compose.ui.graphics)
            implementation(libs.androidx.compose.material3)
            implementation("androidx.compose.foundation:foundation")
            // Kept for POM parity (R1) — same parked-dead-dependency precedent as `:ui:chat`.
            implementation(libs.androidx.compose.ui.tooling.preview)
            implementation(libs.androidx.activity.compose)
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.lifecycle.runtime.ktx)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(compose.desktop.currentOs)
        }
    }
}

publishing {
    publications {
        // KMP generates the publications; rename in place to keep the `ui-callui` coordinate that
        // 1.1.0 consumers use (root umbrella + `-android`/`-jvm` children).
        withType<MavenPublication>().configureEach {
            artifactId = artifactId.replace("callui", "ui-callui")
        }
    }
}
