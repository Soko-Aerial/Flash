# Flash Architecture Overview

This document outlines the multi-module architecture, layer boundaries, dependency contracts, and reactive state paradigms of the Flash ecosystem.

---

## 1. Architectural Philosophy

Flash is designed around four foundational architectural principles:

1. **Abstractions Over Implementations:**  
   Every major subsystem is accessed through a clean Kotlin interface ([`FlashDiscovery`](../../../core/discovery), [`FlashNetwork`](../../../core/network), [`FlashTransferRepository`](../../../core/transfer), [`FlashChatRepository`](../../../core/messaging), [`FlashCalling`](../../../core/calling), [`FlashPtt`](../../../core/ptt), [`FlashTrustStore`](../../../core/security), [`FlashCrypto`](../../../core/security)). Consumers interact with the interfaces; concrete implementations can be swapped or customized without affecting downstream code.

2. **Transport Agnosticism:**  
   The higher layers (messaging, file transfer, calling) operate over abstract communication channels. They do not know or care how bytes travel. Today every byte rides a WebSocket mesh over TLS on the LAN (mDNS discovery) or a hotspot; Wi-Fi Direct is a design goal with **no code yet** (ADR-056), and a serial/Bluetooth radio link has its groundwork in `:core:network` (ADR-101, not wired to the apps).

3. **Reactive State & Immutability:**  
   Persistent state is exposed via Kotlin Coroutines `StateFlow` and event streams via `Flow`. Data models are immutable `data class`es. Fallible operations return [`FlashResult<T>`](../../../core/common) rather than throwing exceptions.

4. **Resource & Platform Tiering:**  
   Instead of hardcoding high-end smartphone assumptions, Flash explicitly defines resource envelopes via [`FlashPerformanceMode`](../../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt). Devices from 128MB IoT boards to high-end multi-core desktops operate within an appropriate CPU, RAM, and radio budget.

---

## 2. Multi-Module Hierarchy & Dependency Graph

Flash enforces strict, acyclic boundaries between modules:

```text
  ┌────────────────────────────────────────────────────────┐
  │                 Host Applications                      │
  │        :app (Android)       :desktop (Compose Desktop) │
  └───────────────────────────┬────────────────────────────┘
                              │
  ┌───────────────────────────▼────────────────────────────┐
  │              Orchestration & UI Layer                  │
  │     :core:engine                :ui:chat, :ui:callui   │
  └─────────────┬─────────────────────────────┬────────────┘
                │                             │
  ┌─────────────▼─────────────────────────────▼────────────┐
  │                 Feature Modules                        │
  │ :core:messaging  :core:transfer  :core:calling         │
  │ :core:ptt        :core:swarm                           │
  └─────────────┬─────────────────────────────┬────────────┘
                │                             │
  ┌─────────────▼─────────────────────────────▼────────────┐
  │               Foundation & Infrastructure              │
  │  :core:network     :core:discovery    :core:security   │
  │  :core:persistence :ui:platform-shims :ui:theme        │
  └───────────────────────────┬────────────────────────────┘
                              │
  ┌───────────────────────────▼────────────────────────────┐
  │                     :core:common                       │
  │   (Base models, FlashResult, FlashPerformanceMode)     │
  └────────────────────────────────────────────────────────┘
```

---

## 3. Module Responsibilities Summary

| Tier | Module | Primary Responsibilities |
|---|---|---|
| **Foundation** | [`:core:common`](../../../core/common) | Base domain models, [`FlashResult<T>`](../../../core/common), [`FlashPerformanceMode`](../../../core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt), time sources, and byte math. No external dependencies. |
| **Foundation** | [`:core:security`](../../../core/security) | Identity key management, AndroidKeyStore integration, ECDH P-256 ephemeral key exchange, HKDF key derivation, and AES-256-GCM wire framing ([`E2eFrameCodec`](../../../core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt)). |
| **Foundation** | [`:core:persistence`](../../../core/persistence) | Room database (SQLCipher on Android, an encrypted SQLite JDBC build on desktop), transactional DAOs for messages, transfers, conversations, and device pairings. |
| **Foundation** | [`:core:discovery`](../../../core/discovery) | Zero-configuration local network service discovery (Android NSD, JmDNS on desktop) plus the resilience sources (remembered routes, subnet sweep, address hints). There is no Wi-Fi Direct code yet. |
| **Foundation** | [`:core:network`](../../../core/network) | High-performance WebSocket server and client with TLS, connection keepalive watchdogs, and network interface roamer monitoring ([`JvmNetworkWatcher`](../../../core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcher.kt)). |
| **Foundation** | [`:ui:theme`](../../../ui/theme) | Unified design system: typography, palettes, elevation, shapes, and accessibility-aware motion policies. |
| **Foundation** | [`:ui:platform-shims`](../../../ui/platform-shims) | Clean platform decoupling for audio playback, microphone recording, image decoding, file pickers, and clipboard operations. |
| **Features** | [`:core:transfer`](../../../core/transfer) | High-throughput streaming chunked transfer engine ([`MultiStreamDispatcher`](../../../core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt)), pause/resume mechanics, per-chunk and whole-file SHA-256 integrity verification (BLAKE3 was deferred, ADR-010), and path traversal guards. |
| **Features** | [`:core:messaging`](../../../core/messaging) | Chat and group persistence, group history sync, delivery receipts (`FLASH_RCPT`), read states (`FLASH_READ`), reaction management, and typing indicators. |
| **Features** | [`:core:calling`](../../../core/calling) | WebRTC mesh audio and video calling, signaling coordinator, ICE renegotiation, and adaptive bitrates. |
| **Features** | [`:core:swarm`](../../../core/swarm) | Group file transfer, torrent-style: a pure sans-IO piece engine plus a driver; members serve pieces to each other (`FlashSwarm`, `FSW1` frames). Off by default in the Android app; not device-verified. |
| **Features** | [`:core:ptt`](../../../core/ptt) | Push-To-Talk voice messaging with floor control, audio streaming, and group voice distribution. |
| **Features** | [`:ui:chat`](../../../ui/chat) | Rich Jetpack Compose messaging UI: message bubbles, composer, media viewer, adaptive dual-pane layouts, and navigation rail. |
| **Features** | [`:ui:callui`](../../../ui/callui) | Voice and video calling screen, camera preview rendering, mute/speaker toggles, and participant grids. |
| **Orchestrator**| [`:core:engine`](../../../core/engine) | Unified lifecycle coordinator binding discovery, networking, messaging, calling, and file transfer into a cohesive facade ([`FlashEngine`](../../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt)). |

---

## 4. Cross-Cutting Patterns

### 4.1 Strict Visibility & API Discipline
All `:core:*` modules compile with Kotlin's `explicitApi()` mode (the `:ui:*` modules too). Public types must explicitly declare their visibility and return types. Internal mechanics are encapsulated behind `internal` or `@FlashInternalApi`.

### 4.2 Clean Calling Seam
WebRTC dependencies are notoriously heavy. Flash isolates calling behind an optional compile-time seam (`attachCalling` / `detachCalling`) in [`FlashEngine`](../../../core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt). If an application does not need voice/video calling, `:core:calling` and `:ui:callui` can be completely omitted from the build without breaking the transfer or messaging stack.

### 4.3 Who depends on whom (checked against the `build.gradle.kts` files, 2026-10-09)

- `:core:common` depends on nothing. Every other `:core:*` module `api`s it.
- `:core:network` uses `:core:discovery` and `:core:security`; `:core:transfer` uses `:core:network`; `:core:messaging` uses `:core:persistence`, `:core:transfer`, `:core:security` and `:core:network`; `:core:swarm` `api`s `:core:transfer`; `:core:ptt` `api`s `:core:messaging`.
- `:core:calling` depends only on `:core:common` (plus the vendored `webrtc-kmp` fork, ADR-103). `:core:engine` sees it as `compileOnly`, so an engine consumer does not pull WebRTC in.
- `:core:engine` `api`s everything above except calling.
- UI: `:ui:chat` uses messaging, security, transfer, `:ui:theme` and `:ui:platform-shims`; `:ui:callui` `api`s `:core:calling` and `:core:ptt`.
- `:desktop` and `:app` are applications, not libraries. Both **hand-wire** calling (`CallCoordinator`), push-to-talk and the swarm in their own engine classes (`DesktopEngine`, `DiscoveryEngineHolder`) rather than going through `Flash.create`; the `attachCalling` seam is for third-party hosts.
- `:sample:consumer*` are three sample consumers (umbrella, granular, desktop) that prove the published artifacts resolve.
