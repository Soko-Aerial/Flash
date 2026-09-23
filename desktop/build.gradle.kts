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
    alias(libs.plugins.aboutlibraries)
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
                // one line brings the whole stack. `:core:ptt` is NOT here: a plain AGP module
                // with no JVM variant (ERROR-049). `:core:calling` converted to KMP in Phase 25
                // and joins below for Phase 33a (D11 = B).
                implementation(project(":core:engine"))
                implementation(project(":core:common"))
                implementation(project(":core:security"))
                implementation(project(":core:discovery"))
                implementation(project(":core:network"))
                implementation(project(":core:transfer"))
                implementation(project(":core:messaging"))
                // Phase 2 slice 4: the chat repository's DAOs + the encrypted database open
                // seam (`openEncryptedFlashDatabase`) live in `:core:persistence`. The cipher
                // driver itself stays `implementation`-scoped there — this module never names it.
                implementation(project(":core:persistence"))

                // UI modules — all KMP since Phase 17–20. `:ui:callui` joined for
                // Phase 33a (the call overlay is shared commonMain; desktop renders it
                // directly rather than porting a screen).
                implementation(project(":ui:theme"))
                implementation(project(":ui:platform-shims"))
                implementation(project(":ui:chat"))
                implementation(project(":ui:callui"))
                // Phase 33a: the shared call engine. webrtc-kmp arrives transitively via
                // `:core:calling`'s api() edge (ADR-025 re-export), so no direct webrtc dep here.
                implementation(project(":core:calling"))

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
                // Phase 33a: the NATIVE libwebrtc for THIS host at product runtime.
                // `webrtc-java`'s main jar (arriving transitively via `:core:calling`) is the
                // Java API only; the native library is a per-OS/arch classified artifact that
                // `:core:calling` declares test-only (its `jvmTest` block — which is why the
                // media smoke test passed while `:desktop:run` died in `NativeLoader` with an
                // NPE inside `Files.copy`: the classes were there, the natives were not).
                // `runtimeOnly`, not `implementation`: no API comes from it, only the `.dll`
                // the loader extracts. OS/arch mapping mirrors calling's block exactly so the
                // two can never disagree about which artifact this host needs.
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
                runtimeOnly("dev.onvoid.webrtc:webrtc-java:0.17.0:$hostOS-$hostArch")
            }
        }

        // The module's FIRST test source set, and it exists for one job nothing else can do:
        // boot the REAL `DesktopEngine` headlessly. `:desktop:run` needs a window, a human and a
        // phone, so a bring-up step that throws after discovery is already advertising is
        // invisible there — which is exactly how a `require(startAll.isSuccess)` in `assemble()`
        // ran for a whole session: the roster showed a peer, `active sessions` stayed empty, and
        // there was nothing on the console to read. `jvmTest` closes that hole.
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.transfer.flash.desktop.DesktopMainKt"

        // Windows/JDK multicast fix, same one Phase 16 needed on `:core:engine`'s
        // `interopHarness` task. `DesktopEngine.start()` starts `CompositeDiscovery`, whose
        // JmDNS transport binds a socket per interface; on this host the JDK's IPv6-preferred
        // stack makes that bind fail with `Invalid argument: setsockopt` on BOTH the interface
        // and the fallback path, so discovery never advertises and the desktop is invisible to
        // phones. `run` forks a new JVM, so the flag has to be declared here — it is not
        // inherited from the Gradle daemon's JAVA_TOOL_OPTIONS.
        //
        // Trade-off, stated plainly: this disables IPv6 for the app's JVM. Flash is LAN/hotspot
        // only and reachability is by host candidates, so v4-only is acceptable today; a future
        // phase that wants v6 peers must replace this with a per-transport bind fix in
        // `JmdnsTransport` rather than dropping the flag globally.
        jvmArgs += listOf("-Djava.net.preferIPv4Stack=true")

        // Heap ceiling. Without one the JVM takes the default of a quarter of physical RAM, which
        // on this 20 GB host is ~5 GB — and on 2026-09-14 the app actually reached 5.19 GB committed
        // / 2.93 GB live while merely discovering peers (a leaked-coroutine bug in
        // `DesktopEngine`'s session collector, since fixed).
        //
        // 1 GB rather than something tighter: a Compose/Skiko app wants several hundred MB of
        // headroom, and capping too low turns a bounded-heap problem into a GC-thrash one, which
        // shows up as high CPU — the very symptom this exists to prevent. The ceiling is a seatbelt,
        // not the fix. If the heap pins at this value again, something is leaking and
        // `jcmd <pid> GC.heap_info` will show it.
        jvmArgs += listOf("-Xmx1g", "-XX:+HeapDumpOnOutOfMemoryError")

        // Skiko vsync and framerate tuning to prevent GPU spin on integrated graphics (e.g. Intel UHD 620)
        jvmArgs += listOf(
            "-Dskiko.vsync.enabled=true",
            "-Dskiko.fps=60"
        )

        // Native distribution packaging (MSI/DEB/DMG) is Phase 24 polish, deliberately NOT
        // wired into any verification gate here — the phase's own Do-NOT list forbids it
        // (packaging tools may not be installed). This block exists only so the entry point is
        // declared where a future phase can extend it.
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb)
            packageName = "Flash"
            packageVersion = "2.0.0"
            description = "Offline LAN peer-to-peer file transfer & messaging"
            vendor = "Flash"
            modules(
                "java.sql",
                "java.naming",
                "jdk.unsupported",
                "java.management",
                "java.instrument",
                "jdk.crypto.cryptoki",
                "jdk.crypto.mscapi",
            )
            windows {
                menuGroup = "Flash"
                upgradeUuid = "6d9b4b0e-3c58-45b7-8df1-e3e9d8f8e021"
                perUserInstall = true
                shortcut = true
                iconFile.set(project.file("src/jvmMain/resources/icons/flash.ico"))
            }
            linux {
                iconFile.set(project.file("src/jvmMain/resources/icons/flash.png"))
            }
        }
    }
}

// The SAME IPv4 flag as `run` above, for the same reason — a test JVM does not inherit the
// application block's jvmArgs. Without it the boot test would measure an environment the product
// never runs in (JmDNS failing its per-interface bind with `Invalid argument: setsockopt`), and a
// test that passes only because the transports under test are broken proves nothing.
//
// `showStandardStreams` is load-bearing for this suite rather than a convenience: `DesktopEngine`
// reports through `FlashLog` (console), and the lines that matter during a bring-up failure —
// the partial-`startAll` warning, the auto-dial attempts, the roster — exist nowhere else.
tasks.named<Test>("jvmTest") {
    jvmArgs("-Djava.net.preferIPv4Stack=true")
    testLogging { showStandardStreams = true }
}

// Audit C1 (ADR-043): THIRD_PARTY_NOTICES.txt from the jvm runtime classpath. It is a classpath
// resource (for Settings → About → Open-source licences) AND a file next to the installed app.
aboutLibraries {
    // offlineMode: see app/build.gradle.kts. The texts are checked in under config/aboutlibraries/.
    offlineMode = true
    collect {
        configPath = rootProject.file("config/aboutlibraries")
        // BOMs carry no code; listing them as "libraries" would only pad the notices.
        includePlatform = false
    }
    export { prettyPrint = true }
    exports {
        create("jvm") { outputFile = layout.buildDirectory.file("generated/aboutLibraries/jvm/aboutlibraries.json") }
    }
}

val thirdPartyNotices = tasks.register<ThirdPartyNoticesTask>("generateThirdPartyNotices") {
    platformName.set("Windows desktop")
    libraryDefinitions.set(layout.buildDirectory.file("generated/aboutLibraries/jvm/aboutlibraries.json"))
    dependsOn("exportLibraryDefinitionsJvm")
    outputDir.set(layout.buildDirectory.dir("generated/thirdPartyNotices"))
}
kotlin.sourceSets.named("jvmMain") { resources.srcDir(thirdPartyNotices) }

// The installers also carry the file visibly: jpackage copies appResourcesRootDir/common/ into the
// installed app's resources folder. (The bundled Java runtime ships its own notices in runtime/legal/.)
val installerNotices = tasks.register<Sync>("syncInstallerThirdPartyNotices") {
    from(thirdPartyNotices)
    into(layout.buildDirectory.dir("generated/installerResources/common"))
}
compose.desktop.application.nativeDistributions.appResourcesRootDir.set(layout.buildDirectory.dir("generated/installerResources"))
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(installerNotices) }
