import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    `maven-publish`
}

// Vendored into the Flash repository per ADR-034 (D12). Provenance: aschulz90/webrtc-kmp
// (a fork of shepeliev/webrtc-kmp), copied 2026-09-13, then trimmed and adapted:
//  - iOS targets + cocoapods + js/wasmJs targets REMOVED — Flash needs Android + JVM only,
//    and every target removed is one that is never built or fixed (Phase 25 S2b).
//  - The old `com.android.library` + KMP pattern was ILLEGAL here: this build compiles under
//    Gradle 9.5 / AGP 9.3.1 / Kotlin 2.2.10 (the consuming build's toolchain), where a KMP
//    module must use `com.android.kotlin.multiplatform.library` — the same shape as every
//    converted `:core:*` module (see core/engine/build.gradle.kts). Source sets renamed to
//    match: androidUnitTest -> androidHostTest, androidInstrumentedTest -> androidDeviceTest.
//  - `signing`/nexus publish removed: the upstream release pipeline is gone. Locally the composite build
//    substitutes the project directly into the consuming build. Since ADR-103 the artifacts are also
//    published to maven local by the JitPack build, under Flash's own coordinates (see below).
//  - webrtc-java bumped 0.8.0 -> 0.17.0 (gradle/libs.versions.toml) — the ONE version
//    movement D12 authorizes (R10 exception, ADR-034).

// ADR-103 (2026-10-09): the fork is PUBLISHED under the project's own coordinates, in the same JitPack
// build as the 15 library modules. Before this, its artifacts carried `com.shepeliev:webrtc-kmp*:0.125.11-flash-1`,
// which exists on neither Maven Central nor JitPack, so the published `core-calling` / `ui-callui` POMs were
// unresolvable for consumers. Two rules keep this working:
//  - group `com.transfer.flash` (the SAME group as every Flash module), so JitPack rewrites it to
//    `com.github.<user>.<repo>` and the version to the tag exactly as it does for the other modules;
//  - version = the library version, read from the single source of truth (`flashLibraryVersion` in the root
//    gradle.properties). A separate version here would be one more number to forget to bump.
// Local development is unchanged: the root settings still `includeBuild`s this directory, and Gradle substitutes
// the project for the catalog entry `com.transfer.flash:webrtc-kmp` (gradle/libs.versions.toml).
group = "com.transfer.flash"

version = run {
    val file = rootProject.layout.projectDirectory.file("../../gradle.properties")
    val text = providers.fileContents(file).asText.orNull
        ?: error("webrtc-kmp fork: ${file.asFile} not found; the fork must be built from inside the Flash repository (ADR-103)")
    Regex("""^\s*flashLibraryVersion\s*=\s*(\S+)\s*$""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        ?: error("webrtc-kmp fork: flashLibraryVersion is missing from ${file.asFile} (ADR-103)")
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("webrtc-kmp (Flash fork)")
            description.set(
                "Fork of shepeliev/webrtc-kmp via aschulz90/webrtc-kmp (Android + JVM only), modified by the Flash project. " +
                    "See MODIFICATIONS.md.",
            )
            url.set("https://github.com/shepeliev/webrtc-kmp")
            licenses {
                license {
                    name.set("The Apache Software License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    distribution.set("repo")
                }
            }
        }
    }
}

val licenseResources = tasks.register<Sync>("licenseResources") {
    from(rootProject.layout.projectDirectory.file("LICENSE")) { rename { "LICENSE-webrtc-kmp.txt" } }
    into(layout.buildDirectory.dir("generated/licenseResources/META-INF"))
}.map { layout.buildDirectory.dir("generated/licenseResources").get() }

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "com.shepeliev.webrtckmp"
        compileSdk = 35
        minSdk = 21

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest { }

        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    sourceSets {
        // Apache-2.0 §4(a): every published binary (jvm jar, android aar) carries the licence text. The file is copied
        // from the fork root into a generated resources dir, so there is exactly one LICENSE in the source tree.
        commonMain {
            resources.srcDir(licenseResources)
        }
        commonMain.dependencies {
            implementation(libs.kotlin.coroutines)
        }

        androidMain.dependencies {
            api(libs.webrtc.android)
            implementation(libs.kotlin.coroutines.android)
            implementation(libs.androidx.coreKtx)
            implementation(libs.androidx.startup)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlin.coroutines.test)
        }

        jvmMain.dependencies {
            api(libs.webrtc.java)
            implementation(libs.java.bouncycastle)
        }
        jvmTest.dependencies {
            // Native libwebrtc binaries for the HOST, pulled per-OS at test time (the fork's
            // original mechanism, kept). Main code only needs the API jar.
            val osName = System.getProperty("os.name")
            val hostOS = when {
                osName == "Mac OS X" -> "macos"
                osName.startsWith("Win") -> "windows"
                osName.startsWith("Linux") -> "linux"
                else -> error("Unsupported OS: $osName")
            }
            val hostArch = when (val arch = System.getProperty("os.arch").lowercase()) {
                "amd64" -> "x86_64"
                else -> arch
            }
            implementation("${libs.webrtc.java.get()}:$hostOS-$hostArch")
        }
    }
}
