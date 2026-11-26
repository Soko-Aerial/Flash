# Architecture

## Modular Library Architecture (ADR-008)

Flash is architected as a suite of decoupled, standalone Kotlin Multiplatform (Android and JVM) libraries under `com.transfer.flash:*`, with `:app` (Android), `:desktop` (Compose Desktop JVM), and `:server` (standalone Flash Hub JVM daemon) as the host applications. This enables third parties and separate apps to use the core networking/transfer engines and custom UI components independently or together.

Detailed plan: [`docs/architecture-modular-libraries-plan.md`](architecture-modular-libraries-plan.md)

### Layered Topology

```text
Host Applications:
  :app     (Android Showcase — owns permissions, foreground services, audio routing)
  :desktop (Compose Desktop JVM Application Shell — DesktopEngine, Room SQLite, system tray)
  :server  (Standalone Flash Hub JVM Daemon & Admin Console — depends on :core:hub-protocol, :core:security, :core:common; zero client edges)

UI Component Libraries (Compose Multiplatform, Android compileSdk 37 + JVM):
  :ui:chat           (Chat list, Conversation, Transfers, Nearby, Settings, Hub workspace, Shell)
  :ui:callui         (FlashCallScreen — full-screen in-call surface, PttSessionOverlayContent)
  :ui:platform-shims (Platform seams: back handling, clipboard, file picking, permissions, image decode, audio playback, voice capture)
  :ui:theme          (FlashTheme, Design Tokens, Motion, Custom Icons, Avatars)

Facade:
  :core:engine       (Flash.create, FlashDesktop.create, FlashEngine — wires everything below into one object)

Core Engine Libraries (Headless / Zero-UI, Android compileSdk 35 + JVM):
  :core:calling      (WebRTC voice/video, FLASH_CALL signaling over a host-owned channel)
  :core:ptt          (Push-to-talk half-duplex voice floor, capture, playout, and wire codecs)
  :core:radio        (KISS/AX.25 packet radio gateway, hardware links, and radio chat)
  :core:swarm        (Group file swarm, sans-IO engine + FSW1 codec, ADR-070..075; attached by the host)
  :core:messaging    (Chat repository, Outbox, Receipts, Drafts, Reactions, Groups, Hub & Radio bridges)
  :core:transfer     (SAF / JVM File Streaming, Chunking, Reassembly, Checksums)
  :core:network      (RFC 6455 WebSocket sessions over pinned TLS, mesh of paired peers; the TCP probe server is legacy)
  :core:enterprise   (Enterprise hub client: enrolment, directory, relay client, sealed files, trust stores; depends on :core:hub-protocol + :core:security)
  :core:discovery    (NSD / mDNS on Android, JmDNS on desktop, manual IP, remembered routes and DR1-DR5 resilience sources; no Wi-Fi Direct code exists)
  :core:security     (Identity, ECDSA/ECDH Crypto, TOFU Trust Store, Pairing)
  :core:hub-protocol (Pure KMP Hub wire contract: certs, relay frames, REST DTOs; shared by :server, :core:security, and :core:enterprise)
  :core:persistence  (Room + SQLCipher/sqlite-jdbc-crypt, DataStore Settings, Retention Policy)
  :core:common       (Shared Models, FlashResult, Framing Protocols, Performance Modes, Annotations)
```

Dependency direction is strictly downward, with two deliberate exceptions:

- **`:core:engine` provides the library facade.** It exports common, security, discovery,
  network, transfer, messaging, persistence, enterprise, swarm and PTT. The two app hosts
  assemble their own runtime graphs. A standalone JVM SDK consumer registers a factory.
- **`:core:calling` reaches the facade through a `compileOnly` seam (ADR-033).** `:core:engine`
  compiles against `FlashCalling` and exposes it (`FlashEngine.calls`, `attachCalling`,
  `onInboundCallText`, `onCallSignalingLost`/`Restored`) but does **not** publish the dependency, so
  the native WebRTC payload (tens of MB per ABI; measure your own build) still never reaches an app that does not call: the host builds the
  engine and attaches it, because only an app can supply the signaling channel it already owns, the
  runtime mic/camera grants and the `microphone|camera` foreground service (ADR-025). `:app` and
  `:ui:callui` depend on `:core:calling` directly. `:core:ptt` is the contrasting case — an `api`
  dependency, since it carries no native payload. It is Kotlin Multiplatform since ADR-058 (Android +
  JVM, the audio hardware behind `PttAudioPlatform`), so the Windows desktop app runs the same push-to-talk
  engine, and `:ui:callui` (which shares the session card) `api`s it.
- **`:core:swarm` (ADR-070..075)** is exported by `:core:engine` and wired through `SwarmHostBinding`.
  The apps enable it by default. An SDK host attaches it explicitly. It depends on
  `:core:common`, `:core:transfer` (model and `Sha256`) and coroutines, and no lower module imports it (`core/swarm/src/jvmTest/.../LayeringTest.kt` enforces both).
  The lower layers gain two generic seams that never name it: a `caps` list in the WebSocket HELLO (`FlashDevice.features`) and a router
  for binary frames by 4-byte magic in `:core:engine`. Group membership by group id + secret (ADR-073) is **not** a module: it extends the
  group code in `:core:messaging` (package `...messaging.group`, with the `GroupGate` of ADR-075) and `:core:security` (package
  `...security.group`). Plan: `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` sections 2 and 2.6.
- **Storage ports.** `:core:messaging` and `:core:transfer` define storage interfaces. The Room-backed
  chat and transfer adapters live in `:core:engine` (ADR-024). Messaging also consumes persistence for
  shared retention policy and settings types.

## Architectural Invariants

1. **Headless Core:** `:core:*` has zero Jetpack Compose or UI dependencies.
2. **Backend-Agnostic UI:** `:ui:chat` depends on repository abstractions (`FlashChatRepository`), not low-level sockets; `:ui:callui` depends on `FlashCalling`/`FlashCallMedia`, never on a concrete session.
3. **Standalone KMP Publishability (Android + JVM):** Every library module is a Kotlin Multiplatform library configuring `maven-publish` to publish the root Gradle module metadata (`.module`/POM), the Android AAR variant (`-android`), the JVM JAR variant (`-jvm`), sources, and docs — including `:ui:callui`, which builds without `:app`, `:core:engine`, or `:ui:chat`. Variant-aware Gradle resolution lets both Android and Desktop JVM consumers depend on the same root coordinate.
4. **Transport Abstraction:** Transfer engines operate over an abstract connection layer regardless of the connection underneath. Today that covers the WebSocket mesh and the Hub relay, plus an optional host-wired `:core:radio` packet-radio gateway (KISS/AX.25 over Bluetooth/serial); Wi-Fi Direct remains postponed (ADR-056). Call signaling is plain text, so any duplex text transport carries it.
5. **Abstractions at the boundary:** the common engine contracts expose domain types and coroutine flows. Platform factories, persistence APIs and media renderers also expose their platform and dependency types.
6. **Explicit API:** every `:core:*` module compiles with `explicitApi()` in strict mode (ADR-023). Published surface is enumerated in [`docs/architecture/public-api.md`](architecture/public-api.md).
