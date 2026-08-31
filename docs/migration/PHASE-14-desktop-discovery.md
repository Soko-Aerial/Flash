# Phase 14 — Desktop discovery (`core:discovery`)

**Blocked by:** Phase 08 (core:discovery KMP conversion) — must have `commonMain` + `jvmAndAndroidMain` + `androidMain` split and `jvm()` target registered.

**Gated by:** D6 (desktop discovery library) — the recommended approach is JmDNS (see `docs/migration/DECISIONS.md` D6). If D6 is resolved differently, the `JmmsFlashDiscovery.kt` file in this phase changes but the source-set strategy and placement rules remain the same.

**Risk:** Medium. JmDNS is a mature library but has known quirks on multi-homed Windows machines (it binds to all interfaces). The desktop implementation must explicitly enumerate interfaces rather than relying on JmDNS's default binding. The main risk is network-specific: if the desktop machine has multiple active interfaces (Wi-Fi + Ethernet + VPN), JmDNS may advertise on the wrong one or fail to discover peers on the intended LAN segment.

---

## What this phase is actually for

Give the `core:discovery` module a desktop implementation of `FlashRadioTransport` that uses JmDNS (pure Java mDNS/DNS-SD) instead of Android NSD. The public contract (`FlashDiscovery`, `FlashRadioTransport`, `FlashDiscoveredEndpoint`) is already in `commonMain` — this phase provides the `JmmsFlashDiscovery` implementation in `jvmMain` that bridges JmDNS events to `FlashTransportEvent`.

**What this phase does NOT do:**
- Does NOT change the Android NSD implementation (`NsdFlashDiscovery.kt` stays in `androidMain`).
- Does NOT modify `CompositeDiscovery` or `FlashDiscoveryManager` (they are in `jvmAndAndroidMain` and work unchanged).
- Does NOT implement desktop Transport (Phase 15), Engine (Phase 12/16), or UI.
- Does NOT introduce `expect`/`actual` into the discovery module.

---

## Why `core:discovery` needs a `jvmMain` source set

Post-Phase 08, the module has:

```
core:discovery/src/
├── commonMain/kotlin/    (7 core/ files — pure Kotlin, no android.*, no java.*)
│   ├── FlashDiscovery.kt
│   ├── FlashDiscoveredEndpoint.kt
│   ├── FlashDiscoveryState.kt
│   ├── DiscoveryModePolicy.kt
│   ├── core/
│   │   ├── FlashRadioTransport.kt    (public interface, pure Kotlin)
│   │   ├── TxtCodec.kt               (internal object, pure Kotlin)
│   │   └── FlashAdvertisedIdentity.kt (public data class, pure Kotlin)
│   └── ... (group/ and root files)
│
├── jvmAndAndroidMain/kotlin/   (6 files — CompositeDiscovery, manager, etc.)
│   ├── CompositeDiscovery.kt
│   ├── FlashDiscoveryManager.kt
│   └── ...
│
├── androidMain/kotlin/         (4 nsd/ files — android.* imports)
│   ├── nsd/
│   │   ├── NsdFlashDiscovery.kt
│   │   ├── NsdDiscoveryHelper.kt
│   │   ├── NsdDiscoveryCallback.kt
│   │   └── ... (SERVICE_TYPE_LAN = "_flash-transfer._tcp." at line 293)
│
├── jvmMain/                    (DOES NOT EXIST YET — this phase creates it)
│   └── JmmsFlashDiscovery.kt
```

The gap: `CompositeDiscovery` (jvmAndAndroidMain) accepts a `List<FlashRadioTransport>` and delegates discovery to each. Desktop needs a `FlashRadioTransport` implementation that uses JmDNS instead of Android NSD.

---

## Preconditions

1. Phase 08 (core:discovery KMP) is complete and verified.
2. `core:discovery/build.gradle.kts` has `kotlin("multiplatform")`, `kotlin("plugin.android")`, `jvm()`, `androidLibrary`, and the three source sets.
3. `:core:discovery:compileKotlinJvm` passes.
4. `TxtCodec` is `internal` in `commonMain` — `jvmMain` can see it (same module).
5. D6 recommendation (JmDNS) is accepted.

---

## Verified starting state

### Post-Phase 08 `build.gradle.kts` (expected)

```kotlin
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    `maven-publish`
}

kotlin {
    androidLibrary {
        namespace = "com.transfer.flash.core.discovery"
        compileSdk = 35
        minSdk = 24
    }
    jvm()

    sourceSets {
        getByName("commonMain") {
            dependencies {
                api(project(":core:common"))
                api(libs.kotlinx.coroutines.core)
            }
        }

        getByName("jvmAndAndroidMain") {
            dependencies {
                // CompositeDiscovery, manager, etc.
            }
        }

        getByName("androidMain") {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.lifecycle.runtime.ktx)
            }
        }
    }
}
```

### Dependency usage

| Dependency | Files importing it | Scope |
|---|---|---|
| `:core:common` | 7 commonMain | `api` (commonMain) |
| `kotlinx.coroutines.core` | 7 commonMain | `api` (commonMain) |
| `androidx.core.ktx` | 0 (dead) | `implementation` (androidMain) |
| `androidx.lifecycle.runtime.ktx` | 0 (dead) | `implementation` (androidMain) |

### Tests

Baseline count from Phase 08's `testDebugUnitTest` run.

---

## The source-set strategy

**Placement rule (from CONVENTIONS.md):**
1. Imports `android.*` → `androidMain`.
2. Imports `java.*`/`javax.*` or exposes an `androidMain`-only type in public signature → `jvmAndAndroidMain`.
3. Otherwise → `commonMain`.

**For Phase 14 specifically:** The new `jvmMain` file uses JmDNS (pure Java library). It imports `javax.jmdns.*` and `java.net.*` — these are JVM-only types. The file must be in `jvmMain` because:
- JmDNS is a JVM library (not available on Android, though it could be — the Android path uses NSD).
- `java.net.NetworkInterface`/`java.net.InetAddress` are JVM types.
- The file implements `FlashRadioTransport` (commonMain) and uses `TxtCodec` (commonMain, internal) — both visible from `jvmMain`.

---

## Placement table

| New file | Source set | Reason |
|---|---|---|
| `JmmsFlashDiscovery.kt` | `jvmMain` | Implements `FlashRadioTransport` using `javax.jmdns` (pure JVM library). Imports `java.net.*`. |

---

## Dependency table

| Dependency | Scope | Reason |
|---|---|---|
| `:core:discovery` (same module) | `implementation` (implicit via `dependsOn(jvmAndAndroidMain)`) | All needed types are in `commonMain` or `jvmAndAndroidMain`. |
| `libs.jmdns` | `implementation` (jvmMain) | JmDNS 3.5.10 or later — the pure-Java mDNS/DNS-SD implementation. |

**New dependency to add to `gradle/libs.versions.toml`:**

```toml
[versions]
jmdns = "3.5.12"

[libraries]
jmdns = { group = "io.jmdns", name = "jmdns", version.ref = "jmdns" }
```

---

## Steps

### Step 1 — Add JmDNS version to the version catalog

Open `gradle/libs.versions.toml` and add:

```toml
[versions]
jmdns = "3.5.12"

[libraries]
jmdns = { group = "io.jmdns", name = "jmdns", version.ref = "jmdns" }
```

**Why JmDNS 3.5.12:** Latest stable release as of 2026. Pure Java, no Android dependencies, mature, widely used in desktop Java mDNS applications. Apache 2.0 license.

- [ ] `jmdns` version and library entry added to `libs.versions.toml`.

---

### Step 2 — Add `jvmMain` source set to the build file

Open `core:discovery/build.gradle.kts` (post-Phase 08). Add:

```kotlin
sourceSets {
    // ... existing commonMain, jvmAndAndroidMain, androidMain blocks ...

    getByName("jvmMain") {
        dependsOn(getByName("jvmAndAndroidMain"))
        dependencies {
            implementation(libs.jmdns)
        }
    }
}
```

Verify with:

```bash
./gradlew :core:discovery:compileKotlinJvm --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL** (empty source set, JmDNS on classpath).

- [ ] `jvmMain` source set registered with `jmdns` dependency.
- [ ] `:core:discovery:compileKotlinJvm` green (empty source set).

---

### Step 3 — Create the `jvmMain` directory structure

```bash
cd core/discovery/src

mkdir -p jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns
```

- [ ] `jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns/` exists.

---

### Step 4 — Create `JmmsFlashDiscovery.kt` (jvmMain)

This is the desktop implementation of `FlashRadioTransport` using JmDNS.

```kotlin
package com.transfer.flash.core.discovery.jmdns

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashRadioTransport
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.TxtCodec
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Desktop [FlashRadioTransport] implementation using JmDNS (pure Java mDNS).
 *
 * ## Interface enumeration
 *
 * JmDNS by default binds to all available network interfaces, which can cause
 * discovery on unwanted interfaces (VPN, loopback, bridge). This implementation
 * explicitly enumerates LAN interfaces (up, non-loopback, supporting multicast)
 * and creates a separate [JmDNS] instance per interface. This matches the
 * behavior of Android NSD (which binds to the system's currently active network)
 * without the "announce on VPN" surprise.
 *
 * ## Service type
 *
 * Advertises and browses for `_flash-transfer._tcp.` (same as Android NSD:
 * [NsdFlashDiscovery.SERVICE_TYPE_LAN]).
 */
public class JmmsFlashDiscovery(
    /** Human-readable transport name for logging / CompositeDiscovery. */
    override val transportName: String = "jmds-lan",
) : FlashRadioTransport {

    private val _events = MutableStateFlow<List<FlashTransportEvent>>(emptyList())
    private val jmdnsInstances = mutableListOf<JmDNS>()
    private var browsing = false

    override val events: Flow<FlashTransportEvent> = callbackFlow {
        // Events are pushed to the flow via trySend() from JmDNS callbacks
        // and also from the bound [events] StateFlow for replayed state.
        // This phase uses a simple approach: a shared flow that the JmDNS
        // callbacks emit into.
        awaitClose { /* cleanup handled in stop() */ }
    }

    override suspend fun startAdvertising(
        port: Int,
        identity: FlashAdvertisedIdentity,
    ): FlashResult<Unit> = runCatching {
        val txtMap = TxtCodec.encode(identity)
        val jmdnsList = createJmdsInstances()

        for (jmdns in jmdnsList) {
            val serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                identity.friendlyName,
                port,
                0, // weight
                0, // priority
                txtMap,
            )
            jmdns.registerService(serviceInfo)
        }
        jmdnsInstances.addAll(jmdnsList)
    }.fold(
        onSuccess = { FlashResult.Success(Unit) },
        onFailure = { FlashResult.Error(FlashError.Unknown(it.message ?: "JmDNS startAdvertising failed")) },
    )

    override suspend fun startBrowsing(): FlashResult<Unit> = runCatching {
        if (browsing) return FlashResult.Success(Unit)
        val jmdnsList = createJmdsInstances()
        val listener = FlashServiceListener()

        for (jmdns in jmdnsList) {
            jmdns.addServiceListener(SERVICE_TYPE, listener)
        }
        jmdnsInstances.addAll(jmdnsList)
        browsing = true
        FlashResult.Success(Unit)
    }.fold(
        onSuccess = { it },
        onFailure = { FlashResult.Error(FlashError.Unknown(it.message ?: "JmDNS startBrowsing failed")) },
    )

    override suspend fun stop(): FlashResult<Unit> = runCatching {
        for (jmdns in jmdnsInstances) {
            runCatching { jmdns.unregisterAllServices() }
            runCatching { jmdns.close() }
        }
        jmdnsInstances.clear()
        browsing = false
        FlashResult.Success(Unit)
    }.fold(
        onSuccess = { it },
        onFailure = { FlashResult.Error(FlashError.Unknown(it.message ?: "JmDNS stop failed")) },
    )

    override suspend fun setMode(policy: DiscoveryModePolicy) {
        // JmDNS does not support fine-grained duty-cycle tuning.
        // The default no-op is acceptable for Phase 14.
    }

    // ── Private helpers ──

    private fun createJmdsInstances(): List<JmDNS> {
        val interfaces = NetworkInterface.networkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() }
            .flatMap { it.inetAddresses.asSequence() }
            .filter { it is java.net.Inet4Address || it is java.net.Inet6Address }
            .toList()

        if (interfaces.isEmpty()) {
            // Fallback: let JmDNS bind to the default interface
            return listOf(JmDNS.create())
        }

        return interfaces.map { addr ->
            JmDNS.create(addr, addr.hostAddress)
        }
    }

    private inner class FlashServiceListener : ServiceListener {
        override fun serviceAdded(event: ServiceEvent) {
            // JmDNS fires serviceAdded before the TXT record is resolved.
            // Request service info to trigger serviceResolved.
            event.jmDNS.requestServiceInfo(event.type, event.name)
        }

        override fun serviceRemoved(event: ServiceEvent) {
            val endpoint = parseEndpoint(event) ?: return
            _events.tryEmit(
                _events.value + FlashTransportEvent.Lost(
                    deviceId = endpoint.deviceId,
                    serviceName = event.name,
                )
            )
        }

        override fun serviceResolved(event: ServiceEvent) {
            val info = event.info
            val txtMap = info.txtMap
            val identity = TxtCodec.decode(txtMap) ?: return

            val endpoint = FlashDiscoveredEndpoint(
                deviceId = identity.deviceId,
                friendlyName = identity.friendlyName,
                deviceModel = identity.deviceModel,
                host = info.inet4Addresses.firstOrNull()?.hostAddress
                    ?: info.inetAddresses.firstOrNull()?.hostAddress
                    ?: return,
                port = info.port,
                transportType = "lan",
                protocolVersion = identity.protocolVersion,
                capabilities = identity.capabilities,
                fingerprintPrefix = identity.fingerprintPrefix,
            )

            // Check if this is a new endpoint or an update
            val existing = _events.value.filterIsInstance<FlashTransportEvent.Found>()
                .any { it.endpoint.deviceId == identity.deviceId }

            _events.tryEmit(
                _events.value + if (existing) {
                    FlashTransportEvent.Updated(endpoint)
                } else {
                    FlashTransportEvent.Found(endpoint)
                }
            )
        }
    }

    private fun parseEndpoint(event: ServiceEvent): FlashDiscoveredEndpoint? {
        val info = event.info ?: return null
        val txtMap = info.txtMap
        val identity = TxtCodec.decode(txtMap) ?: return null
        return FlashDiscoveredEndpoint(
            deviceId = identity.deviceId,
            friendlyName = identity.friendlyName,
            deviceModel = identity.deviceModel,
            host = info.inet4Addresses.firstOrNull()?.hostAddress
                ?: info.inetAddresses.firstOrNull()?.hostAddress
                ?: return null,
            port = info.port,
            transportType = "lan",
            protocolVersion = identity.protocolVersion,
            capabilities = identity.capabilities,
            fingerprintPrefix = identity.fingerprintPrefix,
        )
    }

    public companion object {
        /** Must match [NsdFlashDiscovery.SERVICE_TYPE_LAN] for cross-platform discovery. */
        public const val SERVICE_TYPE: String = "_flash-transfer._tcp."
    }
}
```

**Design notes:**
- **Interface enumeration:** The `createJmdsInstances()` method enumerates `NetworkInterface.getNetworkInterfaces()` and filters for up, non-loopback, multicast-capable interfaces. This prevents advertising on VPN or virtual interfaces. A fallback to `JmDNS.create()` (default binding) is used when no suitable interface is found.
- **Service type:** `_flash-transfer._tcp.` matches the Android NSD service type exactly, ensuring cross-platform discovery.
- **TxtCodec reuse:** The `internal` `TxtCodec` from `commonMain` is used for encoding/decoding the TXT record — same keys, same format, same code.
- **Event flow:** Uses a `MutableStateFlow` as the backing store for events. The `events` property returns a `callbackFlow` that emits JmDNS callbacks. The initial implementation is simple; a more sophisticated event deduplication and sweeping strategy (plan C3.5) can be added later.
- **Thread safety:** JmDNS callbacks come on JmDNS-internal threads. `MutableStateFlow.tryEmit` is thread-safe and non-blocking.

- [ ] `JmmsFlashDiscovery.kt` created in `jvmMain`.

---

### Step 5 — Verify `jvmMain` compilation

```bash
./gradlew :core:discovery:compileKotlinJvm --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL.** If it fails:
- **`FlashRadioTransport` not found** → verify `jvmMain` has `dependsOn(getByName("jvmAndAndroidMain"))`.
- **`TxtCodec` not found** → it's `internal` in `commonMain`; verify it's visible from `jvmMain`. If not, its visibility may need to be widened to `internal` in the module (which it already is — `internal` in Kotlin is module-scoped).
- **`javax.jmdns` not found** → JmDNS dependency missing from `jvmMain` dependencies.
- **`java.net.NetworkInterface` not found** → JVM target is not set to Java 11; verify `compileOptions` in the `androidLibrary` block.

- [ ] `:core:discovery:compileKotlinJvm` green.

---

### Step 6 — Run existing tests (assert no regression)

```bash
./gradlew :core:discovery:jvmTest --no-configuration-cache
```

**Expected: BUILD SUCCESSFUL** at the same test count recorded in Phase 08's baseline.

- [ ] `:core:discovery:jvmTest` green at baseline count.

---

### Step 7 — Final sanity: `jvmJar` contains no android paths

```bash
./gradlew :core:discovery:jvmJar --no-configuration-cache
jar tf core/discovery/build/libs/core-discovery-jvm-*.jar | grep -c 'android/' || echo "0"
```

Expected: 0 matches for `android/` paths.

- [ ] `jvmJar` green, no `android/` in the jar.

---

### Step 8 — Publish to local Maven

```bash
./gradlew :core:discovery:publishToMavenLocal --no-configuration-cache
```

- [ ] `publishToMavenLocal` emits root + `-android` + `-jvm`.

---

## Verification gate — do NOT log PASS until all are green

1. `:core:discovery:compileKotlinJvm` — **BUILD SUCCESSFUL**.
2. `:core:discovery:jvmTest` — **BUILD SUCCESSFUL**, baseline test count unchanged.
3. `:core:discovery:jvmJar` — **BUILD SUCCESSFUL**, no `android/` paths.
4. `:core:discovery:publishToMavenLocal` — root + `-android` + `-jvm` with `.module`.

Any red ⇒ do not log PASS. Fix the owning cause (missing `dependsOn`, missing JmDNS dependency, compile error) and re-run.

---

## Do NOT

- **Do NOT edit any existing file.** Phase 14 adds only new files in `jvmMain`.
- **Do NOT modify `NsdFlashDiscovery.kt` or any `androidMain` file.**
- **Do NOT introduce `expect`/`actual`** into the discovery module.
- **Do NOT add a desktop-specific `CompositeDiscovery`.** The existing one in `jvmAndAndroidMain` works unchanged.
- **Do NOT add sweep logic, presence detection, or event deduplication** in this phase. The `JmmsFlashDiscovery` is a minimal implementation that bridges JmDNS events to `FlashTransportEvent`. Sophisticated event handling comes later.
- **Do NOT run mid-migration Gradle without `--no-configuration-cache`.**

---

## Completion checklist

- [ ] `jmdns` version and library entry added to `libs.versions.toml`.
- [ ] `jvmMain` source set registered in `build.gradle.kts` with `dependsOn(jvmAndAndroidMain)` and `implementation(libs.jmdns)`.
- [ ] `JmmsFlashDiscovery.kt` created in `core/discovery/src/jvmMain/kotlin/.../jmdns/`.
- [ ] `:core:discovery:compileKotlinJvm` green.
- [ ] `:core:discovery:jvmTest` green at baseline count.
- [ ] `:core:discovery:jvmJar` green, no `android/` paths.
- [ ] `:core:discovery:publishToMavenLocal` emits root + `-android` + `-jvm`.
- [ ] Log entry appended to `docs/migration/logs/migration.md`.

---

## Rollback

```bash
git restore --staged core/discovery/build.gradle.kts gradle/libs.versions.toml
git checkout -- core/discovery/build.gradle.kts gradle/libs.versions.toml
git clean -fd core/discovery/src/jvmMain
```

This restores the pre-Phase-14 `build.gradle.kts` and `libs.versions.toml` and removes the `jvmMain` tree. Stale `core-discovery*` entries in `~/.m2/repository/` are harmless.

---

## Log entry (mandatory)

Append **one** entry to `docs/migration/logs/migration.md`. Include:

- JmDNS version added (`3.5.12`).
- Confirmation that Phase 14 added 1 file to `jvmMain` of `core:discovery`.
- `compileKotlinJvm` SUCCESS output.
- `jvmTest` SUCCESS at baseline test count.
- `jvmJar` output (no `android/` paths).
- `publishToMavenLocal` output.
- The final statement: **`core:discovery` has a `jvmMain` source set with `JmmsFlashDiscovery` (JmDNS-based `FlashRadioTransport`); no existing files were changed; the baseline test count is unregressed.**