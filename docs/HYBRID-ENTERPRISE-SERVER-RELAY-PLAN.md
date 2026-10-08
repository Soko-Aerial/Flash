# Hybrid Enterprise Server Relay & Local-First Sync Plan

**Status:** PROPOSED, DESIGN ONLY. **Fact-checked and corrected 2026-10-07. Merged with FO-09 into
[`docs/ENTERPRISE-HYBRID-PLAN.md`](ENTERPRISE-HYBRID-PLAN.md), which is now the authoritative plan.** This file is kept for
history (AGENTS.md section 27); where the two disagree, the merged plan wins.

## 0. Review corrections (2026-10-07)

| # | First-draft claim | Finding | Source |
|---|---|---|---|
| H1 | mDNS type `_flash._tcp` | Real type is **`_flash-transfer._tcp`** (`JmdnsTransport.DEFAULT_SERVICE_TYPE`, `NsdTransport`). | code |
| H2 | Local sockets on `45821` / `45822` | **45822** = WebSocket mesh (`WsTransferServer.PREFERRED_PORT`). **45821** = the TCP LAN probe (`LanProbeServer.DEFAULT_PORT`, Android). Not interchangeable. | code |
| H3 | `FLASH_HELLO` = "mDNS / NSD hello frames" | `FLASH_HELLO` is the **LAN-probe greeting** (`LanProbeMessages.kt:40`), not a discovery frame. | code |
| H4 | Relay client in `WsFlashNetwork.kt`; setting in `FlashSettingsDataStore` | Both are **androidMain only**. Desktop uses `JvmWsFlashNetwork` and `DesktopSettingsStore`. A relay client written there is written twice unless lifted into common code first. | glob/grep |
| H5 | `FlashTransportType.RELAY` as a new idea | It **exists**, together with `MESH`, and `SessionHardeningPolicy` already ranks both as class 3 "post-v1 paths". Adding Bluetooth/radio needs new enum values. | code |
| H6 | "Pre-vouched, no pairing prompt" is a feature | It **contradicts ADR-044 decision 4**: vouched trust never grants direct 1:1 chat, files or 1:1 calls, those still need real pairing, and AGENTS.md section 19 requires user approval for a new peer. FO-09 lists auto-trust as an *open question needing an ADR*. This must be an owner decision (merged plan D-E1). | docs |
| H7 | The server signs `GroupMemberEntity` certificates | Makes the online server the **trust root**: a server compromise forges membership. FO-09 R2 (an *admin* key signing device certs, like `MemberCert`) is safer; the server should relay ciphertext, not issue trust. | design review |
| H8 | Mailbox stores "E2EE blobs" | E2E keys come from **pairing-time** ephemeral ECDH (`docs/security.md` section 4; rekey deferred to "the mesh/relay workstream D5"). Colleagues who never paired have no shared key, and groups have no per-sender keys (AGENTS.md section 29). So E2EE through a mailbox is **not designed yet**. | docs |
| H9 | A foreground service of type `dataSync`/`connectedDevice` keeps the socket alive | On Android 15 (target 35) `dataSync` gets **6 h per 24 h** then `onTimeout()` ([Android docs](https://developer.android.com/develop/background-work/services/fg-service-timeout)); the swarm work already handles this timeout. `connectedDevice` needs an actual connected device. Transsion phones freeze the app regardless (EXP-002). Treat "always connected" as best-effort. | verified + docs |
| H10 | Calls keep working remotely | Remote calls need STUN/TURN and a rendezvous server; ADR-025 says its LAN-only ICE assumption breaks then. Out of scope for the relay unless added deliberately. | ADR-025 |
| H11 | nginx `proxy_pass 127.0.0.1:8080` while the container listens on `8443` and the hub terminates `wss` | Inconsistent. Decide where TLS terminates (proxy or hub) and use one port. | doc |
| H12 | `FLASH_AUTH_TOKEN="corporate-secret-token-2026"` | One static shared token for all users is not authentication. The relay must verify **possession of the device identity key** (signed challenge) plus enrolment, not a bearer string. | design review |
| H13 | Ktor 3.0.0, Exposed 0.56.0, sqlite-jdbc 3.47.0.0, Hikari 5.1.0, logback 1.5.8 hard-coded in the module | Pins are stale for late 2026 and bypass the version catalog (`gradle/libs.versions.toml`). Each needs a reason and a licence note (AGENTS.md section 3). | doc |
| H14 | Thresholds "< 50 MB" / "> 100 MB" | No behaviour for 50-100 MB. Define one boundary, configurable. | doc |
| H15 | Overlap with existing plans | **FO-09 (organisation realms, 2026-10-07)** already records the enterprise realm idea, staged R0-R3, and **FO-06** option 1 already proposes a relay through the hotspot phone. The merged plan unifies them. | docs |

---

## 1. Executive Summary & Architectural Philosophy

This plan outlines the architecture for extending Flash from a pure local-area network (LAN/Wi-Fi Direct) application into a **hybrid local-first, server-assisted enterprise communications platform**.

### Core Philosophy: Local-First Primary, Server-Assisted Fallback
- **Local LAN is King:** Flash's foundational promise—zero-cloud dependency, high-throughput (80–100 MB/s) transfers, direct peer-to-peer WebSockets, and complete offline operational autonomy—remains untouched.
- **Enterprise Server as an Accelerating Hub:** The company server does *not* replace local P2P networking. It functions as an **always-available Directory, Blind Relay, and Offline Store-and-Forward Mailbox** for devices that are physically outside the local network.
- **Office Network Survivability:** If the company internet connection fails or cloud services go down, local communication within the office/facility continues completely uninterrupted without any degradation.
- **Zero-Friction Physical Handoff (Pre-Vouched Trust):** Pairing and group membership established via the server automatically seed the local cryptographic trust stores. When an employee transitions from remote (cellular/home) into the physical office, their device immediately begins communicating over high-speed local LAN sockets **without requiring manual local pairing or QR code scanning**.

---

## 2. Layered Topology & Dual-Plane Operation

Flash operates on two concurrent transport planes:

```text
                               ┌────────────────────────────────────────────────┐
                               │             Company Server (Hub)               │
                               │  - Enterprise Directory & Authentication       │
                               │  - Store-and-Forward Mailbox (E2EE Blobs)      │
                               │  - Member Roster & Signed Trust Certificates   │
                               └───────────────────────┬────────────────────────┘
                                                       │
                                   WAN / 5G / Internet │ (TLS WebSocket: WSS)
                                                       │
                        ┌──────────────────────────────┴──────────────────────────────┐
                        ▼                                                             ▼
          ┌───────────────────────────┐                                 ┌───────────────────────────┐
          │  Remote Worker (Home/5G)  │                                 │     Company Gateway       │
          └───────────────────────────┘                                 └─────────────┬─────────────┘
                                                                                      │ Office LAN (Wi-Fi/Ethernet)
                                                                                      │
                                                                 ┌────────────────────┴────────────────────┐
                                                                 ▼                                         ▼
                                                   ┌───────────────────────────┐             ┌───────────────────────────┐
                                                   │    Office Employee A      │<───Local───>│    Office Employee B      │
                                                   │ (Direct Local WSS Sockets)│    Mesh     │ (Direct Local WSS Sockets)│
                                                   └───────────────────────────┘             └───────────────────────────┘
```

### Transport Priority Engine
1. **Plane 1 — Local Direct (Priority 1):**
   - Active when peers are discovered via mDNS (`_flash-transfer._tcp`, H1), manual IP probing, or Wi-Fi Direct (not built, FO-03).
   - Sockets connect directly over local IP:port (WebSocket mesh on `45822`; `45821` is the TCP probe, H2).
   - Full wire speed (up to 100 MB/s), zero server bandwidth consumption.
2. **Plane 2 — Server Relay (Priority 2 / Fallback):**
   - Active whenever the configured Company Server URL is reachable over the internet.
   - Handled via `FlashTransportType.RELAY` over a persistent outbound TLS WebSocket (`wss://company.hub.domain/ws`).
   - Carries chat messages, signaling frames, and metadata when peers are not on the same physical network.

---

## 3. Server-Mediated Trust & Seamless Local Handoff

Today, Flash uses Trust-On-First-Use (TOFU) with ephemeral ECDH and 6-digit numeric comparison codes or QR codes to trust peers (`FlashTrustStore`) and join groups (`GroupMemberEntity`, `GroupProof`).

### The Pre-Vouched Trust Workflow
1. **Enrollment via Server URL:**
   - In Settings, the user enters the company server URL (`https://flash.company.com`) and enterprise authentication credentials (e.g. employee token, corporate SSO/OIDC, or admin-issued invite key).
   - The device presents its public identity key (`FlashCrypto.identityPublicKeyEncoded`, ECDSA P-256).
   - The server verifies the credentials and registers the device.
2. **Directory & Key Distribution:**
   - The server distributes the organization directory containing colleagues' `deviceId`, `friendlyName`, and verified public signing keys.
   - ~~The server signs and delivers `GroupMemberEntity` certificates~~ *[superseded, H7: an admin key signs device and membership certs; the server only relays them]*.
   - Flash persists these records directly into the local `FlashTrustStore` and Room database.
3. **Seamless Physical Arrival:**
   - The remote employee travels to the office and connects to the office Wi-Fi.
   - Flash discovers local peers via mDNS / NSD (the `FLASH_HELLO` greeting belongs to the LAN probe, H3).
   - **Open decision (H6):** whether enrolment replaces the pairing prompt is an owner decision; ADR-044 and AGENTS.md section 19 currently require it.
   - When checking the incoming peer's identity key, Flash verifies that the peer is already trusted by the enterprise certificate stored in `FlashTrustStore`.
   - **Result:** The devices establish a direct, authenticated TLS session instantly. No user prompts, no pairing codes, and no setup required.

---

## 4. Synchronization, Deduplication & Offline Survivability

### Message Transmission & Fan-out Rule
When a message is dispatched:
1. It is written to the local database as `OUTBOX`.
2. **If Local Peer is Present:** The frame is sent directly over the local WebSocket (`WsConnection`).
3. **If Server is Connected:** The frame is simultaneously uploaded to the company server over the relay WebSocket.
4. **If Offline (No Server, No Local Peer):** The message remains in the durable outbox (`RealFlashChatRepository`). The outbox drain loop retries with exponential backoff until a transport (local or server) becomes available.

### Idempotency & Deduplication
- Every message in Flash has a globally unique UUID (`messageId`) and transit `frameId`.
- If an office colleague receives a message directly over the local LAN, and 150ms later the server attempts to deliver the same message from the internet queue:
  - Flash checks the local message database: `SELECT 1 FROM messages WHERE id = :messageId`.
  - The duplicate server frame is silently ACKed and discarded.
  - The UI updates smoothly with zero duplicate bubbles or flicker.

### Complete Offline Office Survivability
If the company's ISP or internet connection drops:
- The server connection status flips to `DISCONNECTED` with a non-intrusive warning banner.
- All intra-office communications continue without interruption.
- File transfers, 1:1 voice calls, group chat, and PTT remain 100% functional locally.
- When internet service is restored, clients automatically reconnect to the server, flush queued outbox messages, and pull messages delivered from outside while the link was down.

---

## 5. File Transfer Policies Across the Internet

LAN transfers routinely exceed 80 MB/s with files in the tens of gigabytes. Internet transfers through a company server require bandwidth management.

### Two-Tiered Transfer Policy
1. **Lightweight Assets (< 50 MB):**
   - Voice notes, photos, documents, and code snippets.
   - Automatically synced through the company server mailbox.
   - Stored on the server with a configurable retention policy (e.g. automatically purged after 7 days).
2. **Heavyweight Media / Large Archives (> 100 MB):**
   - Message bubble displays file metadata and thumbnail with status: `"Available on Office LAN / Direct P2P"`.
   - The recipient can either:
     - Wait until both devices are on the same local network (automatic zero-cost sync).
     - Tap `"Request Cloud Relay"`, which routes the chunked transfer through the server's streaming chunk router if corporate quota permits.

---

## 6. Mobile Backgrounding & Notification Architecture

On modern Android (API 34/35+), the operating system terminates idle background WebSockets over cellular data to preserve battery (Doze mode).

### Recommended Implementation Strategy
1. **Phase 1 (Enterprise Foreground Service):**
   - Provide a persistent, low-priority ongoing notification: *"Connected to Company Server"*.
   - Uses Android's `FOREGROUND_SERVICE_TYPE_DATA_SYNC` or `CONNECTED_DEVICE`.
   - Keeps the outbound TLS WebSocket alive while remote. **Best-effort only (H9):** `dataSync` is capped at 6 h per 24 h on Android 15 and some handsets freeze the app.
2. **Phase 2 (Push Notification Gateway — Optional / Additive):**
   - Server integrates a lightweight push gateway (UnifiedPush or corporate Firebase Cloud Messaging project).
   - When a remote device is disconnected, the server sends a high-priority "wake-up tickle" push notification containing only the sync ping.
   - The app wakes, syncs pending mailbox frames, posts a local Android notification, and returns to sleep.

---

## 7. Project Packaging, Module Isolation & Gradle Architecture

### Strict Invariant: The Server Must NEVER Package with the Client Apps
The Android APK (`:app`) and Desktop application (`:desktop`) must remain lean client runtimes. They must not include server dependencies (such as Ktor Netty engines, server routing daemons, or server administrative APIs).

### Dependency Flow & Isolation Proof

In Gradle, dependency edges are strictly one-way directed graphs. A module's bytecode is **only** bundled into an artifact if that artifact's module graph reaches it:

```text
                        ┌───────────────────────────────┐
                        │            :server            │  <── Server daemon (Ktor + Netty + DB)
                        └───────────────┬───────────────┘
                                        │ implementation(project(":core:common"))
                                        │ implementation(project(":core:security"))
                                        ▼
                        ┌───────────────────────────────┐
                        │            :core:*            │  <── Headless Models, Framing, Crypto
                        └───────────────▲───────────────┘
                                        │
                    ┌───────────────────┴───────────────────┐
                    │                                       │
      ┌─────────────┴─────────────┐           ┌─────────────┴─────────────┐
      │           :app            │           │         :desktop          │
      │  (Android Showcase APK)   │           │     (Desktop JVM App)     │
      └───────────────────────────┘           └───────────────────────────┘
```

- `:app` depends on `:core:*`, `:ui:*`, and Android platform libraries. It has **no dependency edge to `:server`**.
- `:desktop` depends on `:core:*`, `:ui:*`, and desktop JVM libraries. It has **no dependency edge to `:server`**.
- **Result:** Running `./gradlew :app:assembleRelease` or `./gradlew :desktop:packageDistributionForCurrentOS` compiles client artifacts with **exactly 0 bytes** of server code, Netty engines, or server SQL drivers.

### Standalone Monorepo Module Layout
Keeping `:server` within this repository allows the server to reuse Flash's core wire models (`FLASH_DATA`, `FLASH_ACK`, `ChunkFrame`) and ECDSA cryptographic verifiers (`FlashCrypto`, `GroupProof`) directly, avoiding duplicate code or private artifact repositories.

```text
Flash/
├── settings.gradle.kts                # Registers: include(":server")
├── core/                              # Shared Kotlin Multiplatform Headless Core
│   ├── common/                        # Framing, models, versioning
│   ├── security/                      # Identity, ECDSA keys, certs
│   ├── network/                       # Transports, sessions, health
│   └── messaging/                     # Outbox, deduplication, sync
│
├── app/                               # Android Client (Zero server code)
├── desktop/                           # Desktop Client (Zero server code)
│
└── server/                            # Standalone Enterprise Hub Module
    ├── build.gradle.kts               # Ktor Server + JVM Application plugin
    ├── Dockerfile                     # Production Linux container image
    └── src/main/kotlin/
        └── com/transfer/flash/server/
            ├── Main.kt                # Application entrypoint & CLI flags
            ├── config/                # Server port, TLS, and DB configuration
            ├── auth/                  # Corporate token & device auth
            ├── directory/             # Roster registry & certificate issuer
            ├── mailbox/               # SQLite / PostgreSQL offline queue
            └── relay/                 # WebSocket connection pool & frame router
```

### Complete `server/build.gradle.kts` Specification

> H13: the versions below are the first draft's hard-coded pins. Move them into `gradle/libs.versions.toml` and re-check each
> (Ktor, Exposed, sqlite-jdbc, HikariCP, logback) at implementation time; each dependency needs a reason and licence note.

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.transfer.flash.server.MainKt")
}

dependencies {
    // 1. Shared core logic from monorepo (headless, zero-UI)
    implementation(project(":core:common"))
    implementation(project(":core:security"))

    // 2. Ktor server engine & plugins
    implementation("io.ktor:ktor-server-core-jvm:3.0.0")
    implementation("io.ktor:ktor-server-netty-jvm:3.0.0")
    implementation("io.ktor:ktor-server-websockets-jvm:3.0.0")
    implementation("io.ktor:ktor-server-auth-jvm:3.0.0")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:3.0.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:3.0.0")

    // 3. Persistence for store-and-forward mailbox
    implementation("org.jetbrains.exposed:exposed-core:0.56.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.56.0")
    implementation("org.xerial:sqlite-jdbc:3.47.0.0")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // 4. Logging & Utilities
    implementation("ch.qos.logback:logback-classic:1.5.8")
    implementation(libs.kotlinx.coroutines.core)

    // 5. Test suite
    testImplementation(libs.kotlin.test)
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.0.0")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
```

---

## 8. Client App Extensions & Settings UI

To support connecting to the enterprise hub, the client applications require a dedicated management surface and a background relay transport.

### 1. Settings Screen: "Company Hub" Section
In the expandable settings cards (`FlashSettingsScreen.kt` and `DesktopSettingsStore.kt`), add an **Enterprise Server** category card:
- **Server Address:** Input field for `https://...` or `wss://...` (e.g., `https://flash.mycompany.corp`).
- **Access / Enrollment Token:** Secure input field for employee invite code, corporate bearer token, or API key.
- **Connection Status Badge:**
  - `CONNECTED` (Green pulse ring): Active authenticated WebSocket connection to hub.
  - `OFFLINE / CONNECTING` (Amber dot): Internet reachable but hub reconnecting, or network down.
  - `UNCONFIGURED` (Neutral grey): Operating purely in standalone LAN mode.
- **Actions:**
  - `"Test Connection"`: Executes a TLS handshake and returns ping latency.
  - `"Sync Directory"`: Manually pulls latest company roster and group certificates.
  - `"Disconnect Hub"`: Reverts app to pure local LAN operation.

### 2. Client Relay Transport Hook
In `:core:network` (H4: `WsFlashNetwork.kt` is androidMain and the desktop uses `JvmWsFlashNetwork`; lift the relay client into commonMain behind a port first):
- When `serverUrl` is configured in the settings store (`FlashSettingsDataStore` on Android, `DesktopSettingsStore` on desktop), the network launches a supervised coroutine maintaining an outbound TLS WebSocket session to `wss://<host>:<port>/flash-relay`.
- The connection authenticates via HTTP header `Authorization: Bearer <token>` during WebSocket upgrade.
- The connection is registered as a `FlashSession` with `FlashTransportType.RELAY`.
- Inbound frames received over this relay are routed through the existing message and control pipeline (`MagicFrameRouter`, `FlashTextFraming`), where duplicate check and outbox ACK take place.

---

## 9. Deployment & Production Operations

The company hub is designed for friction-free enterprise self-hosting.

### Container Deployment (`server/Dockerfile`)

```dockerfile
FROM gradle:8.10-jdk21 AS build
WORKDIR /home/gradle/src
COPY --chown=gradle:gradle . .
RUN ./gradlew :server:installDist --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/src/server/build/install/server/ /app/

# Environment defaults
ENV FLASH_PORT=8443
ENV FLASH_DATA_DIR=/data
EXPOSE 8443

VOLUME ["/data"]
ENTRYPOINT ["/app/bin/server"]
```

### Self-Hosted Run Command
```bash
docker run -d \
  --name flash-hub \
  --restart unless-stopped \
  -p 8443:8443 \
  -v /var/lib/flash-hub/data:/data \
  -e FLASH_ENROLMENT_ADMIN_PUBKEY="<base64 admin public key>" \   # was a static shared token, see H12
  -e FLASH_TLS_MODE="proxy|self"            \   # see H11: pick ONE place that terminates TLS
  flash-company-server:latest
```

### Reverse Proxy Configuration (Nginx / Caddy)
For standard corporate deployment with public SSL termination (e.g. Let's Encrypt):
```nginx
server {
    server_name flash.company.com;

    location /flash-relay {
        proxy_pass http://127.0.0.1:8443;   # H11: was 8080 while the container listens on 8443
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "Upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }
}
```

---

## 10. Phased Implementation Roadmap

### Phase 1: Standalone Server Module Skeleton & Protocol Contract
- Add `:server` to `settings.gradle.kts` and configure `server/build.gradle.kts`.
- Implement Ktor server application with `/health` and `/flash-relay` WebSocket endpoints.
- Define the client-hub authentication handshake (`FLASH_HUB_HELLO token=<auth> deviceId=<id>`).
- Implement basic in-memory frame forwarding: routing frames between connected devices by `targetDeviceId`.
- Verify unit tests: two test clients connect over WebSocket and exchange frames through the server.

### Phase 2: Client Settings UI & Relay Transport
- Add `serverUrl`, `serverAuthToken`, and `serverEnabled` fields to `FlashSettingsDataStore`.
- Build the "Enterprise Hub" settings card in `:ui:chat` (`FlashSettingsScreen.kt`) and Desktop settings.
- Implement the client-side relay connection loop in `:core:network` (`WsFlashNetwork.kt`).
- Verify that a client connected to the hub shows the green `CONNECTED` status badge.

### Phase 3: Server-Mediated Trust & Directory Sync
- Define directory synchronization payload: hub returns active company devices with their public keys.
- Client automatically imports verified roster keys into `FlashTrustStore`.
- Verify the "Pre-Vouched Local Handoff": two devices enrolled via the hub meet on a local Wi-Fi network and establish an authenticated LAN session without manual pairing prompts.

### Phase 4: Store-and-Forward Mailbox & Offline Catch-up
- Add SQLite/PostgreSQL persistence to `:server` (`MailboxQueue.kt`).
- Implement message queueing: when sending to an offline colleague, the hub persists the encrypted envelope with timestamp.
- Implement delta catch-up: when an offline device reconnects, the hub flushes all pending frames.
- Verify end-to-end deduplication: a message received over LAN first is not duplicated when delivered later by the hub.
