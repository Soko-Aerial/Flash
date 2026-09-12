import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    // A pure-JVM Compose Desktop application module (Phase 21). KMP plugin + `jvm()` target:
    // this is a CONSUMER of the KMP library modules, not a library with dual targets — same
    // shape the phase file's Step 3 prescribes, with the R5 corrections applied.
    kotlin("multiplatform")
    // The CMP plugin, same alias the four ui/* modules use (pinned at 1.9.3 by the version
    // catalog — R10). It supplies the `compose.*` dependency DSL and `compose.desktop.*`.
    alias(libs.plugins.jetbrains.compose)
    // The Compose COMPILER plugin, tracking the Kotlin version (2.2.10) exactly like the
    // kotlin-compose alias in ui/* — required for any @Composable code.
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    // Plain `jvm()`, never `jvm("desktop")` — CONVENTIONS.md R5 (and this module has exactly
    // one target anyway, so there is nothing to disambiguate). The compile task is
    // `:desktop:compileKotlinJvm`.
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Engine + core modules — all jvm()-capable KMP. `:core:engine`'s jvm target
                // is thin (PlatformLock only) but carries the six api() core modules, so this
                // one line brings the whole stack. `:core:calling`/`:core:ptt` are NOT here:
                // plain AGP modules with no JVM variant (ERROR-049), and calling is Phase 22+
                // scope (D11 = B).
                implementation(project(":core:engine"))
                implementation(project(":core:common"))
                implementation(project(":core:security"))
                implementation(project(":core:discovery"))
                implementation(project(":core:network"))
                implementation(project(":core:transfer"))
                implementation(project(":core:messaging"))

                // UI modules — all KMP since Phase 17–20. `:ui:callui` deliberately absent
                // (still `com.android.library`; no phase converts it yet — see README).
                implementation(project(":ui:theme"))
                implementation(project(":ui:platform-shims"))
                implementation(project(":ui:chat"))

                // Desktop native windowing for the CURRENT OS. This is the artifact that
                // supplies `androidx.compose.ui.window.Window`/`application` on a JVM.
                implementation(compose.desktop.currentOs)

                // The `compose.*` coordinates (redirect to the Skiko-backed desktop artifacts
                // for jvm()); same set ui:chat declares.
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)

                // File-backed identity/trust stores + discovery wiring need coroutines.
                implementation(libs.kotlinx.coroutines.core)
                // Okio: FileSourceOpener opens sources over okio's FileSystem (13B-2 seam).
                implementation(libs.okio)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.transfer.flash.desktop.DesktopMainKt"

        // Native distribution packaging (MSI/DEB/DMG) is Phase 24 polish, deliberately NOT
        // wired into any verification gate here — the phase's own Do-NOT list forbids it
        // (packaging tools may not be installed). This block exists only so the entry point is
        // declared where a future phase can extend it.
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Flash"
            packageVersion = "1.0.0"
            description = "Offline LAN peer-to-peer file transfer & messaging"
            vendor = "Flash"
            // No iconFile lines: the resource files do not exist yet and the phase file's own
            // note allows leaving them out.
        }
    }
}
