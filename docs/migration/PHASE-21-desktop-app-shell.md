# PHASE-21 — Desktop app shell (`:desktop`)

**Blocked by:** PHASE-16 (headless interop gate), PHASE-20 (ui:chat KMP)
**Risk:** HIGH — first desktop executable module; no Hilt, no `Context`, no existing window wiring
**Duration estimate:** 3–5 hours (new module creation + desktop composition root + helper stubs)

---

> ## ⚠️ CORRECTION BLOCK (2026-09-12, written BEFORE execution — §13B-3 pattern)
>
> This phase file was authored 2026-08-31 against a *projected* post-15/16/20 state, not the
> shipped one. Four corrections are load-bearing; the sub-step plan below supersedes Steps 1–8's
> details wherever they conflict. The originals are left in place for the record (R9).
>
> ### C1 — R5-forbidden constructs voided
>
> Lines 43–46 (precondition 2), 897–915 (Step 8 + verification gate) name `jvm("desktop")`,
> `desktopMain`, `compileKotlinDesktop` for `:ui:chat`. **All void.** CONVENTIONS.md R5 forbids
> `jvm("desktop")` outright, and Phase 20 shipped `:ui:chat` on **plain `jvm()`** (see
> `ui/chat/build.gradle.kts:72–81`, which already corrects this phase file in advance). The real
> task names are `:ui:chat:compileKotlinJvm` (desktop) and `:ui:chat:compileDebugKotlin`
> (Android). The `:desktop` module's own build file already used `jvm()`/`jvmMain` correctly —
> only the *references to `:ui:chat`* were wrong. Same correction Phase 15's file carries.
>
> ### C2 — Step 4's symbol census is stale: five of eight references don't exist
>
> Verified against `dev` at `fa42446` (2026-09-12, `grep`-checked, not assumed):
>
> | Step 4 references | Reality |
> |---|---|
> | `JmmsFlashDiscovery()` | **No such class.** The Phase 14 transport is `JmdnsTransport(directory, sweep, …)` in `:core:discovery` **jvmMain** (`core/discovery/src/jvmMain/.../jmdns/JmdnsTransport.kt:127`), wrapped in `CompositeDiscovery` exactly as the Phase 16 harness wires it. |
> | `JvmWsFlashNetwork(…)` | **Exists** (`:core:network` jvmMain, `: FlashNetwork`), ctor `(localDeviceId, localFriendlyName, …)` — matches. |
> | `DesktopFileSourceOpener()` | **No such class.** Phase 13B-2 moved the seam to `FileSourceOpener` (a `fun interface` in `:core:transfer` **commonMain**) returning `okio.Source`. Desktop wires it as a lambda: `FileSourceOpener { uri -> FileSystem.SYSTEM.source(uri.toPath()) }` — exactly what the Phase 16 harness does. |
> | `DesktopDestinationPolicy()` | **No such class** — `RealFlashTransferRepository` has no `destinationPolicy` parameter at all. Destination policy lives in the **host's** `ReceivePipeline.sinkFactory` (per-transfer `OkioRandomAccessSinkHandle` under a canonical root, with the path-containment guard), as Phase 16's `DesktopEndpointFixture` demonstrates. |
> | `createDataChannelChannel(...)` | **No such function.** The desktop stream channel is the harness's `sessionChannel`: a small `StreamChannel` object riding a live `WsSession`'s binary lane. |
> | `DesktopTrustStore(identityDir)` recipe | **Wrong signature.** `FlashTrustStore` (commonMain) is `isTrusted/trustPeer/revokeTrust/getTrustedPeers` over `FlashDeviceId` — the Phase 16 `DesktopTrustStore` in `core/engine/src/jvmTest/.../interop/DesktopIdentityStore.kt` already implements it faithfully (file-backed, never shipped). This phase re-homes that pattern. |
> | `RealFlashChatRepository` ctor per Step 4 | **Doesn't exist on desktop at all.** It is `:core:messaging` **androidMain** (Room DAOs, `java.util.*`); the jvm() target cannot see it. commonMain offers `FlashChatRepository`, `EmptyFlashChatRepository` (the app's own pre-boot binding, ERROR-034), `SampleFlashChatRepository` (previews only — KDoc forbids runtime use). **Desktop binds `EmptyFlashChatRepository`** until 09B-2 lands Room-3 KMP persistence. |
> | `DefaultFlashEngine` + `FlashSettingsDataStore` | **Both androidMain-only** (`core/engine`/`core/persistence`). Neither is on any jvm() classpath. The desktop "engine" is therefore **not a `FlashEngine`** — it is a `DesktopEngine` facade class local to `:desktop` exposing exactly what the shell reads (the same surface `AppEngine` exposes, minus Android-only members). |
>
> ### C3 — Precondition 1 vs the goal directive: BUILD-ONLY disposition
>
> Precondition 1 demands "PHASE-16 … has **passed**". The gate is **CLOSED pending hardware**
> (README row 16; `adb devices` empty at `fa42446`; G1–G6 need a physical Android endpoint).
> The gate's Do-NOT ("no UI phase (17–22) may merge while closed") and this phase's own Do-NOT
> ("Do NOT attempt to run the desktop app — desktop **compilation** is the gate") together
> define the only honest disposition: this phase may be **built and compile-verified on a
> branch-local basis**, with the interop verdict remaining CLOSED and the commit recorded as
> **not mergeable to a release line** until a human runs G1–G6. Building ahead of the gate does
> not weaken it: nothing here claims a gate scenario passed, and `:app` (the Android release
> surface) is untouched. The migration log entry must state this explicitly.
>
> ### C4 — `:desktop` consumes only jvm()-capable modules; `:ui:callui` and `:core:calling`/`:core:ptt` are NOT on the list
>
> `:core:calling`, `:core:ptt`, `:ui:callui`, `:app` are plain AGP modules with no JVM variant
> (ERROR-049 precedent in `core/engine/build.gradle.kts:147`). Step 3's dependency list omitted
> `:ui:callui` — correctly, but for the wrong reason (it said "PHASE-19 adds it"; Phase 19 shipped
> `:ui:platform-shims` instead). `:desktop` depends on: `core:common`, `core:security`,
> `core:discovery`, `core:network`, `core:transfer`, `core:messaging`, `core:engine` (all
> jvm()-capable KMP), `:ui:theme`, `:ui:platform-shims`, `:ui:chat`, plus `compose.desktop.currentOs`.
> `:core:engine`'s jvm target carries only `PlatformLock.jvm.kt` — it is included for future
> completion and pulls the six api() core modules transitively.
>
> ### REVISED SUB-STEP PLAN (executed in this order)
>
> | # | Sub-step | Content |
> |---|---|---|
> | 21-1 | Module skeleton | `desktop/build.gradle.kts` (KMP `jvm()` + `org.jetbrains.compose` plugin via existing alias, no `nativeDistributions` — packaging is Phase 24 polish), `include(":desktop")` in settings.gradle.kts, empty `jvmMain` tree. Gate: `:desktop:compileKotlinJvm` on an empty module. |
> | 21-2 | Desktop engine facade | `DesktopEngine.kt` — no-Hilt class exposing `ready/startError/chats/transfers/network/discovery/localDeviceId/localFriendlyName/settings-less model` surface. Assembles the **proven** Phase 16 harness composition (JmDNS discovery + `JvmWsFlashNetwork` + `RealFlashTransferRepository` + `ReceivePipeline` with the #5 accept gate and RESUME ordering + file-backed identity/trust stores re-homed from jvmTest) as a long-lived desktop composition. Chats bind `EmptyFlashChatRepository` (C2). |
> | 21-3 | Shell + entry point | `DesktopShell.kt` (Option B: thin shell over the four shared `:ui:chat` tab screens, `FlashBottomNav` + `rememberFlashNavigationState`, inline domain→UI mappers per the phase file's own Option B spec) and `DesktopMain.kt` (`application { Window { FlashTheme { DesktopShell(engine) } } }`). |
> | 21-4 | Helpers | `DesktopHelpers.kt` — 6 helper stubs per Step 5 (`java.awt.Desktop`, `URLConnection.guessContentTypeFromName`, `Downloads/Flash/` copy). |
> | 21-5 | Verification | R3 gate: `:desktop:compileKotlinJvm`, `:ui:chat:compileKotlinJvm`, `:ui:chat:compileDebugKotlin`, `:app:assembleDebug`, plus R6 grep scans on `desktop/src` (no `android.*`/`androidx.*` outside `androidx.compose.*` CMP namespace). |
> | 21-6 | Log + README | Append the honest entry (gate CLOSED, build-only) and update README rows 21. |
>
> Steps 1–8 below remain the *reference* for file layout, the six helpers, the Option A/B analysis
> (Option B confirmed), and the Do-NOT list — corrected by C1–C4 above.

---

## What this phase is for

Create a **`desktop` application module** that produces a runnable desktop JVM window. This is the
first time the entire Flash stack — engine, discovery, transport, and chat UI — runs outside
Android. The module:

1. Declares a `main()` entry point that assembles a **desktop composition root** (the desktop
   equivalent of `AppEngine` — deferred from PHASE-12 and PHASE-15).
2. Opens a `Window` hosting the shared chat UI — either the lifted `FlashApp` (Option A) or a
   thin `DesktopShell` re-composing the shared `ui:chat` screens (Option B, recommended; see
   Step 6 for the ownership analysis).
3. Provides **desktop stubs** for the 6 Android-only helpers in `MainActivity.kt`.
4. Is **not** a KMP module — it is a pure JVM application (`jvm()` target) that consumes the
   KMP library modules. The `:app` module stays Android-only; this phase creates a parallel
   desktop entry point.

**What this phase does NOT do:**
- Does NOT change `:app` or `MainActivity.kt` (they stay Android-only).
- Does NOT write the wire protocol or any engine internals — it only **assembles** the engine
  pieces that PHASE-12 (desktop factory), PHASE-14 (desktop discovery), and PHASE-15 (desktop
  transport) already created. If those factories are incomplete, this phase finishes the
  assembly surface (see §"Desktop composition root").
- Does NOT implement native distribution packaging (`.dmg`/`.msi`/`.deb`) — that is a future
  polish step.
- Does NOT implement the `saveImageToGallery` helper with real desktop functionality (it stubs
  it to copy to `Downloads/`).
- Does NOT run the headless interop gate (PHASE-16) — that is a separate verification phase.

---

## Preconditions

1. [ ] PHASE-16 is committed and the headless interop gate has passed (Android↔desktop transfer
      verified at the protocol level).
2. [ ] PHASE-20 is committed and `ui:chat` compiles for both `compileKotlinDesktop` and
      `compileDebugKotlin`.
3. [ ] `ui:theme` (PHASE-18) and `ui:platform-shims` (PHASE-19) are committed and compile as
      KMP modules with `desktopMain` source sets.
4. [ ] All `core:*` modules compile for `jvm()` target (PHASE-07–12).
5. [ ] The desktop engine composition root (`Flash.create()` for JVM) is ready — it must exist
      as a `jvmMain` factory in `:core:engine` (PHASE-12). If it does not exist yet, this phase
      creates it as part of the desktop composition root.
6. [ ] Working tree is clean for `app/`, `core/`, `ui/`, `settings.gradle.kts`,
      `gradle/libs.versions.toml`.
7. [ ] `./gradlew :app:assembleDebug --no-configuration-cache` succeeds (baseline pre-migration).

---

## Verified starting state

### The `app` module (Android shell — stays unchanged)

The Android app shell lives in `app/` and is the **only** existing executable module:

```
app/
├── build.gradle.kts                 # android.application + Hilt + KSP
└── src/main/java/com/transfer/flash/
    ├── MainActivity.kt              # 951 lines — FlashApp, FlashShell, 6 helpers
    ├── TransfersUiMapper.kt         # domain → UI mapping (JVM-testable)
    ├── di/
    │   ├── AppEngine.kt             # Hilt @Singleton facade over DiscoveryEngineHolder
    │   ├── FlashApplication.kt      # @HiltAndroidApp (7 lines)
    │   └── FlashAppModule.kt        # Hilt module providing @AppScope
    ├── debug/
    │   ├── DiscoveryEngineHolder.kt # 59 KB — process-wide engine singleton
    │   └── FlashDevConsoleScreen.kt # debug debug-only composable
    ├── identity/
    │   └── AppIdentity.kt           # device-id + friendly-name from prefs
    ├── net/
    │   └── AutoConnectGate.kt       # network-connectivity gating
    └── stress/
        └── FlashStressTestUi.kt     # stress-test screen
```

### 6 Android-only helpers (must be stubbed for desktop)

Every helper lives in `MainActivity.kt` as a private function. Desktop equivalents go in a new
`DesktopHelpers.kt` file in the `:desktop` module.

| Helper | Lines | Android APIs used | Desktop approach |
|---|---|---|---|
| `shareTransferredFile` | 755–793 | `FileProvider`, `Intent.ACTION_SEND`, `Toast` | `java.awt.Desktop`, or copy to Desktop + open |
| `guessMimeType` | 796–799 | `android.webkit.MimeTypeMap` | `java.net.URLConnection.guessContentTypeFromName` |
| `resolveShareableUri` | 806–824 | `FileProvider`, `Uri` | `java.io.File.toURI()` |
| `shareImageUri` | 827–850 | `Intent.ACTION_SEND`, `FileProvider`, `Toast` | `java.awt.Desktop` |
| `saveImageToGallery` | 857–911 | `MediaStore`, `ContentValues`, `Environment` | Copy to `Downloads/Flash/` |
| `openAttachment` | 919–949 | `Intent.ACTION_VIEW`, `FileProvider`, `Toast` | `java.awt.Desktop.getDesktop().open()` |

### Current `settings.gradle.kts` (includes)

No desktop module exists yet. Current includes:

```text
include(":app")
include(":core:common")  ...  (7 core modules)
include(":ui:theme")
include(":ui:chat")
include(":sample:consumer")
include(":sample:consumer-granular")
```

No `:ui:platform-shims` (PHASE-19 adds it), no `:desktop`.

### Desktop engine composition root status

PHASE-12 (§"What this phase does NOT do") explicitly defers the desktop `Flash.create()` factory
to Phase 21. PHASE-15 (§"What this phase does NOT do") repeats the deferral:

> "Does NOT implement the full engine composition root (`Flash.create()`) — that is deferred to
> Phase 21 (desktop app shell)."

The Android composition root is `object Flash` in `core/engine/src/main/java/` (androidMain).
It calls `FlashEngine.create(context, config)` which opens a Room database, creates a
`KeystorePassphraseProvider`, and assembles the full stack. Desktop has none of these.

The `jvmMain` expectation in `:core:engine` (created by PHASE-12) should provide a `fun create(config: FlashConfig): FlashEngine` that uses an in-memory database (D5 = A) and a file-based
passphrase provider (no Android Keystore). PHASE-12's phase file specifies the exact API shape.
If PHASE-12's desktop factory is incomplete, this phase completes it.

---

## The change

### Step 1 — Create the `:desktop` module directory structure

```text
desktop/
├── build.gradle.kts
└── src/
    └── jvmMain/
        └── kotlin/
            └── com/
                └── transfer/
                    └── flash/
                        └── desktop/
                            ├── DesktopMain.kt          # main() entry point
                            ├── DesktopEngine.kt         # no-Hilt AppEngine equivalent
                            └── DesktopHelpers.kt        # stubs for 6 Android helpers
```

```powershell
$module = "desktop"
New-Item -Path "$module/src/jvmMain/kotlin/com/transfer/flash/desktop" -ItemType Directory -Force
```

### Step 2 — Add `:desktop` to `settings.gradle.kts`

After the `:sample:consumer-granular` line, add:

```kotlin
// Desktop application shell (Phase 21 / PHASE-21-desktop-app-shell.md)
include(":desktop")
```

### Step 3 — Create `desktop/build.gradle.kts`

```kotlin
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Engine + all core modules
                implementation(project(":core:engine"))
                implementation(project(":core:common"))
                implementation(project(":core:persistence"))
                implementation(project(":core:security"))
                implementation(project(":core:discovery"))
                implementation(project(":core:network"))
                implementation(project(":core:transfer"))
                implementation(project(":core:messaging"))

                // UI modules (KMP with desktopMain target)
                implementation(project(":ui:theme"))
                implementation(project(":ui:platform-shims"))
                implementation(project(":ui:chat"))

                // Compose Desktop native windowing
                implementation(compose.desktop.currentOs)

                // Compose Multiplatform (via org.jetbrains.compose plugin)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.transfer.flash.desktop.DesktopMainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Flash"
            packageVersion = "1.0.0"
            description = "Offline LAN peer-to-peer file transfer & messaging"
            vendor = "Flash"

            linux {
                iconFile.set(project.file("src/jvmMain/resources/flash-icon.png"))
            }
            windows {
                iconFile.set(project.file("src/jvmMain/resources/flash-icon.ico"))
            }
        }
    }
}
```

> **Key points:**
> - Uses `kotlin("multiplatform")` + `org.jetbrains.compose` — the standard CMP desktop app pattern
> - `jvm()` target with `jvmMain` source set (not `jvm("desktop")`/`desktopMain` — this is a
>   consumer module, not a library with dual Android/desktop targets)
> - `compose.desktop.currentOs` provides the native windowing toolkit for the current OS
> - `compose.desktop.application { mainClass }` configures the executable entry point
> - `nativeDistributions` is declarative — the packaging tools are not invoked unless
>   `./gradlew :desktop:packageMsi` / `:desktop:packageDeb` is run. This phase only verifies
>   `compileKotlinJvm`.
> - Icon files are referenced but not created in this phase — they are optional for compilation.
>   The `iconFile.set()` lines can be commented out if the resource files don't exist yet.

### Step 4 — Create `DesktopEngine.kt` (desktop composition root)

The desktop composition root **cannot** call `DiscoveryEngineHolder` — that is an Android singleton
in `:app` which `:desktop` cannot depend on (it's an `android.application` module). Instead, it
manually assembles the desktop pieces from PHASE-12 (engine), PHASE-13 (desktop file I/O),
PHASE-14 (desktop discovery via JmDNS), and PHASE-15 (desktop WebSocket transport) into a
`DefaultFlashEngine`:

```kotlin
package com.transfer.flash.desktop

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.engine.DefaultFlashEngine
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.core.messaging.FlashChatRepository
import com.transfer.flash.core.messaging.RealFlashChatRepository
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.bridge.DiscoveryRouteBinder
import com.transfer.flash.core.network.ws.JvmWsFlashNetwork
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.RealFlashTransferRepository
import com.transfer.flash.core.transfer.desktop.DesktopFileSourceOpener
import com.transfer.flash.core.transfer.desktop.DesktopDestinationPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Desktop composition root — no-Hilt, no-Context equivalent of
 * [com.transfer.flash.di.AppEngine].
 *
 * Manually assembles the desktop engine stack from the pieces created by:
 * - PHASE-12  (core:engine KMP, DefaultFlashEngine)
 * - PHASE-13  (desktop file I/O: DesktopFileSourceOpener, DesktopDestinationPolicy)
 * - PHASE-14  (desktop discovery: JmmsFlashDiscovery)
 * - PHASE-15  (desktop transport: JvmWsFlashNetwork)
 *
 * Exposes the same facade surface as `AppEngine` so the desktop shell can bind to it.
 * All accessors are null until [ready] flips true.
 */
class DesktopEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _startError = MutableStateFlow<Throwable?>(null)
    val startError: StateFlow<Throwable?> = _startError.asStateFlow()

    private var _engine: FlashEngine? = null

    // --- Public accessors (mirror AppEngine's surface) ---

    val chats: FlashChatRepository? get() = _engine?.chats
    val transfers: FlashTransferRepository? get() = _engine?.transfers
    val network: FlashNetwork? get() = _engine?.network
    val discovery: FlashDiscovery? get() = _engine?.discovery
    val pairing: Nothing? get() = null  // No pairing coordinator on desktop yet

    private val identityDir: File by lazy {
        val dir = File(System.getProperty("user.home", "."), ".flash")
        dir.mkdirs()
        dir
    }

    val localDeviceId: String
        get() = runCatching {
            val file = File(identityDir, "device_id")
            if (file.exists()) file.readText().trim()
            else {
                val id = java.util.UUID.randomUUID().toString()
                file.writeText(id)
                id
            }
        }.getOrDefault(java.util.UUID.randomUUID().toString())

    val localFriendlyName: String
        get() = System.getProperty("user.name", "Desktop")

    val appVersionName: String = "1.0.0"

    val settingsStore: FlashSettingsDataStore by lazy {
        FlashSettingsDataStore(
            produceFile = { File(identityDir, "flash_settings.preferences_pb") },
            scope = scope,
        )
    }

    /**
     * Assembles and starts the desktop engine stack.
     * Idempotent — later calls no-op once [ready] is set.
     */
    fun start() {
        scope.launch {
            if (_ready.value) return@launch
            val result = runCatching { assembleDesktopEngine() }
            result
                .onSuccess { engine ->
                    _engine = engine
                    _startError.value = null
                    _ready.value = true
                }
                .onFailure { _startError.value = it }
        }
    }

    /**
     * Desktop equivalent of [DiscoveryEngineHolder.ensureStarted] — but it uses
     * desktop-only implementations (JmDNS, JvmWsFlashNetwork, DesktopFileSourceOpener,
     * DesktopDestinationPolicy) instead of Android NSD, WsFlashNetwork, and Room.
     */
    private fun assembleDesktopEngine(): FlashEngine {
        val identity = FlashAdvertisedIdentity(
            deviceId = FlashDeviceId(localDeviceId),
            friendlyName = localFriendlyName,
            deviceModel = "Desktop",
            protocolVersion = 2,
        )

        // --- Discovery (PHASE-14) ---
        val jmdnsTransport = com.transfer.flash.core.discovery.jmdns.JmmsFlashDiscovery()
        val compositeDiscovery = CompositeDiscovery(transports = listOf(jmdnsTransport))

        // --- Network (PHASE-15) ---
        val networkImpl = JvmWsFlashNetwork(
            localDeviceId = identity.deviceId.value,
            localFriendlyName = identity.friendlyName,
        )

        // Wire discovery endpoints to the network layer
        DiscoveryRouteBinder.observe(scope, compositeDiscovery.discoveredEndpoints, networkImpl)

        // Start the WebSocket network server
        val netStartResult = networkImpl.start(0)
        val serverPort = (netStartResult as? FlashResult.Success)?.value ?: 0
        check(serverPort > 0) { "Network server failed to start: ${(netStartResult as? FlashResult.Failure)?.error}" }

        compositeDiscovery.setMode(FlashDiscoveryMode.STANDARD)
        val startResult = compositeDiscovery.startAll(serverPort, identity)
        require(startResult.isSuccess) { "Discovery startAll failed: ${(startResult as? FlashResult.Failure)?.error}" }

        // --- Transfer (PHASE-13) ---
        val transferImpl = RealFlashTransferRepository(
            streamChannelFactory = { channelId, peerDeviceId ->
                com.transfer.flash.core.network.datachannel.createDataChannelChannel(
                    network = networkImpl,
                    channelId = channelId,
                    peerDeviceId = peerDeviceId,
                    scope = scope,
                )
            },
            fileSourceOpener = DesktopFileSourceOpener(),
            destinationPolicy = DesktopDestinationPolicy(),
            transferStore = null, // D5 = A: no desktop persistence yet
            scope = scope,
        )

        // --- Chat (in-memory only, no Room on desktop) ---
        val chatImpl = RealFlashChatRepository(
            network = networkImpl,
            trustStore = com.transfer.flash.core.security.trust.DesktopTrustStore(identityDir),
            scope = scope,
        )

        // Assemble into DefaultFlashEngine
        return DefaultFlashEngine(
            chats = chatImpl,
            transfers = transferImpl,
            discovery = compositeDiscovery,
            network = networkImpl,
            trustStore = com.transfer.flash.core.security.trust.DesktopTrustStore(identityDir),
            settings = settingsStore,
            onClose = {
                scope.launch {
                    compositeDiscovery.stopAll()
                    networkImpl.stop()
                }
            },
        )
    }
}
```

> **⚠️ Interface alignment note:**
> The code above assumes the following symbols exist (created by their respective phases).
> **Verify each one against the actual post-PHASE-12/13/14/15 code before implementing:**
> - `JmmsFlashDiscovery(…)` — PHASE-14, package `com.transfer.flash.core.discovery.jmdns`
> - `JvmWsFlashNetwork(…)` — PHASE-15, package `com.transfer.flash.core.network.ws`
> - `DesktopFileSourceOpener()` — PHASE-13, package `com.transfer.flash.core.transfer.desktop`
> - `DesktopDestinationPolicy()` — PHASE-13, package `com.transfer.flash.core.transfer.desktop`
> - `DesktopTrustStore(…)` — a desktop `FlashTrustStore` implementation; if PHASE-07/12 did not
>   create one, this phase must create it (simple file-based store, see inline recipe below)
> - `createDataChannelChannel(network, channelId, peerDeviceId, scope)` — a desktop data-channel
>   adapter; if PHASE-15 did not create one, inline the connection logic here
>
> If any symbol is missing, **this phase creates it as a minimal desktop stub** — the earlier
> phases deferred the assembly surface, and this phase is where that surface is completed.

#### DesktopTrustStore recipe (create if missing)

```kotlin
package com.transfer.flash.core.security.trust

import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.security.trust.TrustedPeer
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Properties

/**
 * Desktop [FlashTrustStore] backed by a plain properties file.
 * Replaces Android's SharedPreferences-backed trust store.
 */
public class DesktopTrustStore(private val dir: File) : FlashTrustStore {
    private val file = File(dir, "trusted_peers.properties")

    private fun load(): Properties = Properties().apply {
        if (file.exists()) FileReader(file).use { load(it) }
    }

    private fun save(props: Properties) {
        FileWriter(file).use { props.store(it, null) }
    }

    override fun trustedPeers(): List<TrustedPeer> = load().map { (key, value) ->
        TrustedPeer(deviceId = key.toString(), fingerprint = value.toString())
    }

    override fun isTrusted(deviceId: String): Boolean =
        load().containsKey(deviceId)

    override fun trust(deviceId: String, fingerprint: String) {
        val props = load()
        props[deviceId] = fingerprint
        save(props)
    }

    override fun revoke(deviceId: String) {
        val props = load()
        props.remove(deviceId)
        save(props)
    }
}
```

### Step 5 — Create `DesktopHelpers.kt` (6 Android helper stubs)

```kotlin
package com.transfer.flash.desktop

import com.transfer.flash.ui.transfers.FlashTransferItemUi
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Desktop stubs for the 6 Android-only helpers in [com.transfer.flash.MainActivity].
 *
 * These are minimal implementations that let the desktop window compile and function
 * for the core transfer/chat flow. File sharing and gallery-saving are secondary features
 * that can be polished later.
 */

/**
 * Stub for [com.transfer.flash.MainActivity.shareTransferredFile].
 * On desktop, copies the file to the user's Desktop directory and opens it.
 * In the future, this could show a file-chooser save dialog instead.
 */
fun shareTransferredFile(item: FlashTransferItemUi) {
    val path = item.localPath ?: return
    val file = File(path)
    if (!file.exists()) return

    // Fallback: open the file location in the file manager
    if (Desktop.isDesktopSupported()) {
        runCatching {
            Desktop.getDesktop().open(file.parentFile)
        }
    }
}

/**
 * Desktop MIME type guesser — replaces [android.webkit.MimeTypeMap].
 */
fun guessMimeType(fileName: String): String {
    return URLConnection.guessContentTypeFromName(fileName) ?: "*/*"
}

/**
 * Desktop URI resolver — replaces FileProvider-based URI resolution.
 * Returns a simple `file://` URI for absolute paths.
 */
fun resolveShareableUri(ref: String?): URI? {
    if (ref.isNullOrBlank()) return null
    return try {
        if (ref.startsWith("content://") || ref.startsWith("file://")) {
            URI(ref)
        } else {
            File(ref).toURI()
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Desktop share — opens the file with the system default application.
 */
fun shareImageUri(ref: String?, mimeType: String) {
    val uri = resolveShareableUri(ref) ?: return
    val file = File(uri)
    if (!file.exists() || !Desktop.isDesktopSupported()) return
    runCatching { Desktop.getDesktop().open(file) }
}

/**
 * Desktop "save to gallery" — copies the file to `Downloads/Flash/` directory.
 * This is the closest desktop equivalent to Android's MediaStore.
 */
fun saveImageToGallery(ref: String?, mimeType: String) {
    val uri = resolveShareableUri(ref) ?: return
    val source = File(uri)
    if (!source.exists()) return

    val downloadsDir = File(System.getProperty("user.home"), "Downloads/Flash")
    downloadsDir.mkdirs()

    val ext = mimeType.substringAfter("/", "jpg").let { type ->
        when (type) {
            "jpeg" -> "jpg"
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
    }
    val target = File(downloadsDir, "flash_${System.currentTimeMillis()}.$ext")
    runCatching {
        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Desktop attachment opener — opens the file with the system default application.
 * Replaces Android's `Intent.ACTION_VIEW` + `FileProvider`.
 */
fun openAttachment(path: String?, mimeType: String) {
    if (path.isNullOrBlank()) return
    val file = File(path)
    if (!file.exists() || !Desktop.isDesktopSupported()) return
    runCatching { Desktop.getDesktop().open(file) }
}
```

### Step 6 — Resolve the `FlashApp` ownership problem first

`FlashApp` is `public @Composable fun FlashApp(engine: AppEngine, showDevConsoleEntry: Boolean = false)`
declared in [MainActivity.kt](/C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/MainActivity.kt:160)
(package `com.transfer.flash`). It takes the **concrete** Hilt class `AppEngine`
(`com.transfer.flash.di.AppEngine`).

The problem: `FlashApp` lives in the `:app` module, which is an `android.application` module
(Hilt + KSP + `com.android.application`). The `:desktop` module **cannot depend on `:app`** —
an Android application module cannot be a dependency of a JVM application. So `DesktopMain.kt`
cannot directly call `MainActivity.kt`'s `FlashApp`.

There are three ways forward; the phase must pick exactly one before writing `DesktopMain.kt`:

**Option A — Lift `FlashApp` + `FlashShell` into `ui:chat` commonMain and interface the engine
(RECOMMENDED for correctness, but touches `:app` and `ui:chat`).**
Move the `FlashApp` composable (and the `FlashShell` it calls, lines 160–706) from
`MainActivity.kt` into a shared location — `ui:chat` `commonMain` is the natural home since it
already hosts the tab screens. Introduce a `FlashAppEngine` interface exposing exactly what
`FlashApp`/`FlashShell` read: `ready`, `startError`, `chats`, `transfers`, `network`,
`discovery`, `pairing`, `settingsStore`, `localDeviceId`, `localFriendlyName`,
`appVersionName`, and `start()`. `AppEngine` adds `: FlashAppEngine`; `DesktopEngine`
implements it. This gives both platforms the *same* shell logic (tab nav, splash, settings
persistence, dev-console overlay).

> ⚠️ This is **new shared-UI work**, not a pure mechanical move: `FlashShell` currently
> references `SampleFlashChatRepository`, `TransfersUiMapper`, `FlashDevConsoleScreen`, and the
> Android-only helpers — all `:app`-scoped. Moving it to `ui:chat` requires the helpers to move
> behind the PHASE-19 shims (or into `:desktop` via callbacks) and `TransfersUiMapper` to move
> into `ui:chat`. That is a larger refactor and should be its own decision/checkpoint.

**Option B — Keep `:app` untouched; give `:desktop` its own thin shell that reuses the shared
tab screens.**
`DesktopMain.kt` writes a small `DesktopShell` composable in `:desktop` that composes the
already-shared screens from `ui:chat` (`FlashChatListScreen`, `FlashTransfersScreen`,
`FlashNearbyScreen`, `FlashSettingsScreen`) against `DesktopEngine`'s flows, plus a desktop
bottom/side navigation bar. The engine-accessor surface (`FlashAppEngine` interface, Option A's
interface) can live in `ui:chat` commonMain or `core:engine` without moving the shell itself.
This is **less shared code** than Option A but **zero risk to working Android code** and is the
smallest change that satisfies "the desktop window opens and runs the chat UI".

**Option C — Make `DesktopEngine` a thin adapter that reuses `FlashApp` via a shared engine
interface but keeps `FlashApp` in `:app`.**
Impossible as stated — `:desktop` cannot depend on `:app`. Discarded.

**Recommendation: Option B for this phase.** It is honest about what exists (D8 Option A: desktop
ships the existing chat UI), keeps `:app` untouched, and still delivers a runnable desktop shell.
Option A (lifting the whole shell) is the better long-term target and should be its own follow-up
phase once the desktop shell runs, because it touches working Android code that this phase must
not regress.

> If Option B is chosen, `DesktopMain.kt` composes the tab screens directly (see Step 7). If
> Option A is chosen, Step 7 composes the lifted `FlashApp`. The rest of this phase file
> documents the **Option B** path (the recommended one) and notes where Option A would differ.

### Step 7 — Create `DesktopMain.kt` (entry point, Option B)

```kotlin
package com.transfer.flash.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.theme.FlashMaterialTheme
import com.transfer.flash.ui.theme.FlashTheme

/**
 * Desktop entry point for Flash (PHASE-21, Option B).
 *
 * Assembles the desktop composition root ([DesktopEngine]) and opens a window hosting a
 * simple tab shell built from the shared `ui:chat` screens. Android's `FlashShell` (with its
 * 4-tab bottom nav, splash, and dev-console overlay) stays in `:app`; the desktop shell is a
 * thin re-composition of the same shared screens so there is no `:app` dependency.
 */
fun main() = application {
    val engine = remember { DesktopEngine() }
    engine.start()

    Window(
        onCloseRequest = ::exitApplication,
        title = "Flash",
        state = rememberWindowState(width = 1200.dp, height = 800.dp),
    ) {
        FlashMaterialTheme(darkTheme = false, dynamicColor = false) {
            FlashTheme {
                DesktopShell(engine = engine)
            }
        }
    }
}
```

`DesktopShell` (same file, or `DesktopShell.kt`) is a small composable that binds the four
shared screens to `DesktopEngine` flows. It deliberately mirrors the data-shaping done in
`FlashShell` (lines 252–706 of `MainActivity.kt`).

Key differences from `FlashShell`:

- **Chats:** falls back to `SampleFlashChatRepository` (in `core:messaging`, available to
  desktop) when `engine.chats` is null — same pattern as `FlashShell`.
- **Transfers:** uses `TransfersUiState.fromItems` (in `ui:chat`) with an **inline mapping**
  of `FlashTransfer` → `FlashTransferItemUi`. The `TransfersUiState.fromDomain` extension
  lives in `:app` (`TransfersUiMapper.kt`) and is **not available to `:desktop`**.
- **Pairing:** `DesktopEngine.pairing` is `null` (no pairing coordinator on desktop yet),
  so the Nearby screen gets no pairing dialog.
- **No `toUiTransport` mapping:** `FlashTransportType.toUiTransport()` is an `:app`-scoped
  extension. The desktop shell needs its own local equivalent (a small `when` that maps
  the domain `FlashTransportType` to the UI `FlashNetworkTransport`), mirroring
  `MainActivity.kt:741-747`.

```kotlin
// DesktopShell.kt — package com.transfer.flash.desktop
// Option B: thin shell composing the four shared screens directly.

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashTransportType
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.messaging.SampleFlashChatRepository
import com.transfer.flash.core.messaging.model.FlashNetworkTransport
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection as DomainDirection
import com.transfer.flash.core.transfer.model.FlashTransferState as DomainState
import com.transfer.flash.ui.chat.FlashChatListScreen
import com.transfer.flash.ui.chat.FlashPairingPhase
import com.transfer.flash.ui.nearby.FlashNearbyScreen
import com.transfer.flash.ui.nearby.NearbyIdentityUi
import com.transfer.flash.ui.nearby.NearbyPeerUi
import com.transfer.flash.ui.nearby.NearbyUiState
import com.transfer.flash.ui.settings.FlashSettingsModel
import com.transfer.flash.ui.settings.FlashSettingsScreen
import com.transfer.flash.ui.transfers.FlashTransferDirection
import com.transfer.flash.ui.transfers.FlashTransferItemUi
import com.transfer.flash.ui.transfers.FlashTransferState
import com.transfer.flash.ui.transfers.FlashTransfersScreen
import com.transfer.flash.ui.transfers.TransfersUiState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Thin desktop shell — Option B (no FlashApp dependency).
 *
 * Mirrors FlashShell's data-shaping using the same shared screens but without
 * any :app dependency. Transfers use an inline domain→UI mapper since
 * TransfersUiState.fromDomain lives in :app.
 */
@Composable
fun DesktopShell(engine: DesktopEngine) {
    val ready by engine.ready.collectAsState()
    val sampleChatRepository = remember { SampleFlashChatRepository() }
    val chatRepository = (if (ready) engine.chats else null) ?: sampleChatRepository
    val chatListState by chatRepository.chatListState.collectAsState()

    // Transfers: inline domain→UI mapping (no :app TransfersUiMapper dependency)
    val fallbackTransfers = remember { MutableStateFlow(emptyList<FlashTransfer>()) }
    val domainTransfers by (engine.transfers?.activeTransfers ?: fallbackTransfers).collectAsState()
    val transfersUi = remember(domainTransfers) {
        TransfersUiState.fromItems(domainTransfers.map { it.toDesktopTransferItemUi() })
    }

    // Nearby: identity from engine, no pairing coordinator
    val fallbackEndpoints = remember { MutableStateFlow(emptyList<FlashDiscoveredEndpoint>()) }
    val fallbackDiscoveryState = remember { MutableStateFlow(FlashDiscoveryState()) }
    val discoveredEndpoints by (engine.discovery?.discoveredEndpoints ?: fallbackEndpoints).collectAsState()
    val discoveryState by (engine.discovery?.state ?: fallbackDiscoveryState).collectAsState()
    val nearby = remember(discoveredEndpoints, discoveryState, ready) {
        NearbyUiState(
            identity = NearbyIdentityUi(
                displayName = if (ready) engine.localFriendlyName else "Flash device",
                deviceIdShort = (if (ready) engine.localDeviceId else "").take(8).ifBlank { "00000000" },
                port = discoveryState.advertisedPort,
            ),
            isScanning = discoveryState.isDiscovering,
            peers = discoveredEndpoints.map { ep ->
                NearbyPeerUi(
                    id = ep.deviceId.value,
                    name = ep.friendlyName,
                    transport = ep.transportType.toDesktopTransport(),
                    isTrusted = false,
                )
            },
            pairingRequest = null,
            pairingPhase = FlashPairingPhase.Idle,
            pairingSecondsLeft = 0,
        )
    }

    // Settings: default model from engine
    val settingsModel = remember(ready, engine.localFriendlyName, engine.localDeviceId) {
        FlashSettingsModel(
            displayName = engine.localFriendlyName,
            deviceIdShort = engine.localDeviceId.take(8).ifBlank { "00000000" },
            appVersion = engine.appVersionName,
        )
    }

    // Simple tab shell (no AnimatedScreen — desktop has no back stack)
    Scaffold(
        bottomBar = {
            // Desktop tab bar — could use FlashBottomNav or a simple row of buttons
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // Show a single tab for now; a full tab shell is a follow-up
            FlashChatListScreen(
                state = chatListState,
                onConversationClick = { /* navigate to conversation */ },
                onSearchClick = {},
                bottomInset = 0.dp,
            )
        }
    }
}

/**
 * Desktop local equivalent of `:app`'s `FlashTransportType.toUiTransport()`
 * (MainActivity.kt:741-747). Maps the domain transport to the UI badge type.
 */
private fun FlashTransportType.toDesktopTransport(): FlashNetworkTransport = when (this) {
    FlashTransportType.WIFI_DIRECT -> FlashNetworkTransport.WifiDirect
    FlashTransportType.RELAY, FlashTransportType.MESH -> FlashNetworkTransport.Relay
    FlashTransportType.UNKNOWN -> FlashNetworkTransport.Unknown
    FlashTransportType.LAN, FlashTransportType.WEBSOCKET -> FlashNetworkTransport.Lan
}

/**
 * Desktop inline mapper: FlashTransfer → FlashTransferItemUi.
 * Replaces :app's TransfersUiMapper.toUiItem() which desktop cannot import.
 */
private fun FlashTransfer.toDesktopTransferItemUi(): FlashTransferItemUi =
    FlashTransferItemUi(
        id = id.value,
        fileName = fileName,
        direction = when (direction) {
            DomainDirection.Sending -> FlashTransferDirection.Send
            DomainDirection.Receiving -> FlashTransferDirection.Receive
        },
        peerName = peerName,
        bytesTotal = bytesTotal,
        bytesDone = bytesDone,
        state = when (state) {
            DomainState.Offered -> FlashTransferState.Offered
            DomainState.Queued -> FlashTransferState.Queued
            DomainState.Transferring, DomainState.Verifying -> FlashTransferState.Active
            DomainState.Paused -> FlashTransferState.Paused
            DomainState.Completed -> FlashTransferState.Completed
            DomainState.Failed, DomainState.Cancelled -> FlashTransferState.Failed
        },
        speedBytesPerSec = speedBytesPerSec,
        etaSeconds = etaSeconds.takeIf { it > 0L },
        errorMessage = errorMessage ?: if (state == DomainState.Cancelled) "Cancelled" else null,
        verified = state == DomainState.Completed,
        localPath = localPath ?: sourceUri,
    )
```

> **If Option A is chosen instead**, `DesktopMain.kt` calls the lifted `FlashApp(engine = ...)`
> from `ui:chat` and skips `DesktopShell`. The entry point is otherwise identical.

### Step 8 — Build and verify

```powershell
# 1. Desktop compilation
./gradlew :desktop:compileKotlinJvm --no-configuration-cache

# 2. Verify Android still builds
./gradlew :app:assembleDebug --no-configuration-cache

# 3. Verify ui:chat still compiles for both targets
./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache
./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache
```

> **Note:** `compileKotlinJvm` is used for the `:desktop` module (it uses `jvm()` target).
> The `:ui:chat` module (which uses `jvm("desktop")`) still uses `compileKotlinDesktop`.

---

## Verification gate

| Check | Command | Expected result |
|---|---|---|
| Desktop module exists | `Test-Path desktop/build.gradle.kts` | `True` |
| Desktop source files exist | `Get-ChildItem desktop/src/jvmMain -Recurse -Filter *.kt \| Measure-Object` | 3 files |
| Entry point class matches | `Select-String "mainClass" desktop/build.gradle.kts` | `DesktopMainKt` |
| Desktop compiles | `./gradlew :desktop:compileKotlinJvm --no-configuration-cache` | `BUILD SUCCESSFUL` |
| Android still compiles | `./gradlew :app:assembleDebug --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:chat desktop compiles | `./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache` | `BUILD SUCCESSFUL` |
| ui:chat Android compiles | `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` | `BUILD SUCCESSFUL` |
| `:desktop` in settings | `Select-String ':desktop' settings.gradle.kts` | Match found |
| No `android.*` imports | `Select-String "^import android\." desktop/src -Recurse` | 0 matches |
| No `androidx.*` imports | `Select-String "^import androidx\." desktop/src -Recurse` | 0 matches (or only `androidx.compose.*`) |
| DesktopEngine exists | `Test-Path desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt` | `True` |
| DesktopHelpers exists | `Test-Path desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt` | `True` |
| DesktopMain exists | `Test-Path desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt` | `True` |

---

## Do NOT

- **Do NOT** change the `:app` module or `MainActivity.kt` unless the `FlashAppEngine` interface
  extraction is required — and even then, make the smallest possible change (add `: FlashAppEngine`
  marker, change parameter type, no logic changes).
- **Do NOT** attempt to run the desktop app in this phase — desktop compilation is the gate.
  Runtime verification belongs in PHASE-16 (headless interop) or manual testing.
- **Do NOT** add native distribution packaging (`packageMsi`/`packageDeb`) to the verification
  gate — those tasks require additional tooling (WiX Toolset for MSI, `dpkg-deb` for DEB) that
  may not be installed on the dev machine.
- **Do NOT** implement `saveImageToGallery` with a full cross-platform image picker — the
  `Downloads/Flash/` copy is sufficient for the initial desktop shell.
- **Do NOT** version-bump any existing dependency in `libs.versions.toml` (R10).
- **Do NOT** add a `gradle/libs.versions.toml` plugin alias for the desktop app — it uses
  `kotlin("multiplatform")` and `id("org.jetbrains.compose")` directly (the `org-jetbrains-compose`
  alias was already added by PHASE-20).
- **Do NOT** create `desktopTest` or `commonTest` source sets — this module has no tests yet.
- **Do NOT** add `:app` dependencies to `:desktop` — the desktop module depends on the same
  library modules, not on the Android app shell.

---

## Rollback

If the desktop module cannot compile:

1. **Remove `:desktop` from `settings.gradle.kts`:** `git checkout dev -- settings.gradle.kts`
2. **Delete the desktop module:** `Remove-Item -Recurse -Force desktop`
3. **Verify pre-migration build:** `./gradlew :app:assembleDebug --no-configuration-cache`

If the `FlashAppEngine` interface extraction broke the Android build:

1. **Revert `AppEngine.kt`:** `git checkout dev -- app/src/main/java/com/transfer/flash/di/AppEngine.kt`
2. **Revert `MainActivity.kt`:** `git checkout dev -- app/src/main/java/com/transfer/flash/MainActivity.kt`
3. **Delete the interface file:** `Remove-Item -Force desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/FlashAppEngine.kt`
4. **Verify Android build:** `./gradlew :app:assembleDebug --no-configuration-cache`

---

## Log entry for `logs/migration.md`

```markdown
## 2026-09-0X — PHASE-21: Desktop app shell (`:desktop`)

- **Agent/model:** Copilot (autonomous)
- **Commit:** ecb0c63
- **Decisions relied on:** D5=A (in-memory desktop persistence — no resume-across-restart);
  D7 (platform shims — proceeded with recommendation); D8=_pending_ (Phase 22 gated;
  this phase does not depend on D8)

### Change
Created a new `:desktop` application module using `kotlin("multiplatform")` +
`org.jetbrains.compose` + `compose.desktop.currentOs`. Added `DesktopEngine` (no-Hilt
equivalent of `AppEngine`), `DesktopHelpers.kt` (6 Android-only helper stubs), and
`DesktopMain.kt` (entry point with `application { Window { DesktopShell(engine) } }`,
Option B — a thin `:desktop` shell composing the shared `ui:chat` screens, with inline
domain→UI mappers replacing the `:app`-scoped `TransfersUiMapper`/`toUiTransport`).
Updated `settings.gradle.kts` to include `:desktop`.

### Files changed
- **Add:** `desktop/build.gradle.kts`
- **Add:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopMain.kt`
- **Add:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- **Add:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopHelpers.kt`
- **Modify:** `settings.gradle.kts` — added `include(":desktop")`

### Verification
- `./gradlew :desktop:compileKotlinJvm --no-configuration-cache` — PASS
- `./gradlew :app:assembleDebug --no-configuration-cache` — PASS
- `./gradlew :ui:chat:compileKotlinDesktop --no-configuration-cache` — PASS
- `./gradlew :ui:chat:compileDebugKotlin --no-configuration-cache` — PASS
- No `android.*` or `androidx.*` imports in `desktop/src/` — PASS
- 3 Kotlin source files in `desktop/src/jvmMain/` — PASS

### Deviations from the phase file
<To be filled in during execution.>

### Known issues
- `DesktopEngine.assembleDesktopEngine()` references desktop-only implementations
  (JmmsFlashDiscovery, JvmWsFlashNetwork, etc.) from PHASE-12/13/14/15. If any symbol
  is missing, this phase creates a minimal stub (see Step 4 inline recipes).
- `DesktopShell` uses inline domain→UI mappers that duplicate the `:app`-scoped
  `TransfersUiMapper.fromDomain` and `FlashTransportType.toUiTransport()`. These are
  intentionally local to avoid an `:app` dependency. A future unification phase could
  lift these mappers to `ui:chat` commonMain.
- `DesktopHelpers` stubs are minimal — `shareTransferredFile` and `shareImageUri` only
  open the parent directory / file in the system desktop manager, not a share chooser.
- `saveImageToGallery` copies to `Downloads/Flash/` — no MediaStore integration.
- No notification/foreground service support on desktop (not applicable).

### Next step
PHASE-22 — adaptive desktop screens (arrange existing composables for wide windows)
```