# Architecture

## Modular Library Architecture (ADR-008)

Flash is architected as a suite of decoupled, standalone Android/Kotlin libraries under `com.transfer.flash:*`, with `:app` serving as the showcase application. This enables third parties and separate apps to use the core networking/transfer engines and custom UI components independently or together.

Detailed plan: [`docs/architecture-modular-libraries-plan.md`](architecture-modular-libraries-plan.md)

### Layered Topology

```text
Host App:
  :app (Showcase / Demo — owns permissions, foreground services, audio routing)

UI Component Libraries (Compose, compileSdk 37):
  :ui:chat      (Chat list, Conversation, Transfers, Nearby, Settings, Shell)
  :ui:callui    (FlashCallScreen — full-screen in-call surface)
  :ui:theme     (FlashTheme, Design Tokens, Motion, Custom Icons, Avatars)

Facade:
  :core:engine     (Flash.create, FlashEngine — wires everything below into one object)

Core Engine Libraries (Headless / Zero-UI, compileSdk 35):
  :core:calling     (WebRTC voice/video, FLASH_CALL signaling over a host-owned channel)
  :core:swarm       (PLANNED, ADR-070: group file swarm, sans-IO engine + FSW1 codec; attached by the host, optional)
  :core:messaging   (Chat repository, Outbox, Receipts, Drafts, Reactions)
  :core:transfer    (SAF File Streaming, Chunking, Reassembly, Checksums)
  :core:network     (Persistent TCP Sessions, RFC 6455 WebSockets, Mesh Routing)
  :core:discovery   (Auto NSD / mDNS, Manual IP Probing, Wi-Fi Direct Abstraction)
  :core:security    (Identity, ECDSA/ECDH Crypto, TOFU Trust Store, Pairing)
  :core:persistence (Room + SQLCipher, DataStore Settings, Retention Policy)
  :core:common      (Shared Models, FlashResult, Framing Protocols, Annotations)
```

Dependency direction is strictly downward, with two deliberate exceptions:

- **`:core:engine` is the only module that knows the wiring.** It `api()`s the seven core modules
  below it, so a consumer adds one artifact and sees every published type.
- **`:core:calling` reaches the facade through a `compileOnly` seam (ADR-033).** `:core:engine`
  compiles against `FlashCalling` and exposes it (`FlashEngine.calls`, `attachCalling`,
  `onInboundCallText`, `onCallSignalingLost`/`Restored`) but does **not** publish the dependency, so
  ~30 MB per ABI of native WebRTC still never reaches an app that does not call: the host builds the
  engine and attaches it, because only an app can supply the signaling channel it already owns, the
  runtime mic/camera grants and the `microphone|camera` foreground service (ADR-025). `:app` and
  `:ui:callui` depend on `:core:calling` directly. `:core:ptt` is the contrasting case — an `api`
  dependency, since it carries no native payload. It is Kotlin Multiplatform since ADR-058 (Android +
  JVM, the audio hardware behind `PttAudioPlatform`), so the Windows desktop app runs the same push-to-talk
  engine, and `:ui:callui` (which shares the session card) `api`s it.
- **`:core:swarm` (planned, ADR-070, PROPOSED 2026-10-04, not built) follows the PTT pattern, not the calling one.** `:core:engine`
  `api()`s it and wires it in one place (`SwarmHostBinding`); nothing runs until the host calls `attachSwarm`. It depends only on
  `:core:common`, `:core:transfer` (model and `Sha256`) and coroutines, and no lower module imports it (a `LayeringTest` will enforce both).
  The lower layers gain two generic seams that never name it: a `caps` list in the WebSocket HELLO (`FlashDevice.features`) and a router
  for binary frames by 4-byte magic in `:core:engine`. Group membership by group id + secret (ADR-073) is **not** a module: it extends the
  group code in `:core:messaging` (package `...messaging.group`, with the `GroupGate` of ADR-075) and `:core:security` (package
  `...security.group`). Plan: `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md` sections 2 and 2.6.
- **Persistence is inverted, not depended on.** `:core:messaging` and `:core:transfer` define
  storage ports; the Room-backed adapters live in `:core:engine` (ADR-024), so neither domain module
  depends on `:core:persistence` and no Room type reaches a public signature.

## Architectural Invariants

1. **Headless Core:** `:core:*` has zero Jetpack Compose or UI dependencies.
2. **Backend-Agnostic UI:** `:ui:chat` depends on repository abstractions (`FlashChatRepository`), not low-level sockets; `:ui:callui` depends on `FlashCalling`/`FlashCallMedia`, never on a concrete session.
3. **Standalone Publishability:** Every library module configures `maven-publish` to generate AARs, POMs, sources, and docs — including `:ui:callui`, which builds without `:app`, `:core:engine` or `:ui:chat`.
4. **Transport Abstraction:** Transfer engines operate over an abstract connection layer regardless of whether the transport is LAN TCP, WebSocket mesh, or Wi-Fi Direct. Call signaling is plain text, so any duplex text transport carries it.
5. **Abstractions at the boundary:** every module's entry point is an interface, and no socket, stream, codec, Room or platform type appears in a public signature. The single sanctioned exception is webrtc-kmp's `VideoTrack`, which a renderer has to be handed directly (ADR-025).
6. **Explicit API:** every `:core:*` module compiles with `explicitApi()` in strict mode (ADR-023). Published surface is enumerated in [`docs/architecture/public-api.md`](architecture/public-api.md).
