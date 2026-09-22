# Flash Architecture Overview

This document outlines the multi-module architecture, layer boundaries, dependency contracts, and reactive state paradigms of the Flash ecosystem.

---

## 1. Architectural Philosophy

Flash is designed around four foundational architectural principles:

1. **Abstractions Over Implementations:**  
   Every major subsystem is accessed through a clean Kotlin interface ([`FlashDiscovery`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery), [`FlashNetwork`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network), [`FlashTransferRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer), [`FlashChatRepository`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging), [`FlashCalling`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling), [`FlashPtt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt), [`FlashTrustStore`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security), [`FlashCrypto`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security)). Consumers interact with the interfaces; concrete implementations can be swapped or customized without affecting downstream code.

2. **Transport Agnosticism:**  
   The higher layers (messaging, file transfer, calling) operate over abstract communication channels. They do not know or care whether bytes travel over local Wi-Fi (LAN mDNS + WebSockets), Wi-Fi Direct p2p, or a custom transport.

3. **Reactive State & Immutability:**  
   Persistent state is exposed via Kotlin Coroutines `StateFlow` and event streams via `Flow`. Data models are immutable `data class`es. Fallible operations return [`FlashResult<T>`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common) rather than throwing exceptions.

4. **Resource & Platform Tiering:**  
   Instead of hardcoding high-end smartphone assumptions, Flash explicitly defines resource envelopes via [`FlashPerformanceMode`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt). Devices from 128MB IoT boards to high-end multi-core desktops operate within an appropriate CPU, RAM, and radio budget.

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
  │    :core:messaging      :core:transfer    :core:calling│
  │                        :core:ptt                       │
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
| **Foundation** | [`:core:common`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common) | Base domain models, [`FlashResult<T>`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common), [`FlashPerformanceMode`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/common/src/commonMain/kotlin/com/transfer/flash/core/common/perf/FlashPerformanceMode.kt), time sources, and byte math. No external dependencies. |
| **Foundation** | [`:core:security`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security) | Identity key management, AndroidKeyStore integration, ECDH P-256 ephemeral key exchange, HKDF key derivation, and AES-256-GCM wire framing ([`E2eFrameCodec`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/E2eFrameCodec.kt)). |
| **Foundation** | [`:core:persistence`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/persistence) | Room database, encrypted SQLite via SQLCipher, transactional DAOs for messages, transfers, conversations, and device pairings. |
| **Foundation** | [`:core:discovery`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery) | Zero-configuration local network service discovery (Android NSD / JmDNS) and Wi-Fi Direct peer discovery. |
| **Foundation** | [`:core:network`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network) | High-performance WebSocket server and client with TLS, connection keepalive watchdogs, and network interface roamer monitoring ([`JvmNetworkWatcher`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/resilience/JvmNetworkWatcher.kt)). |
| **Foundation** | [`:ui:theme`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/theme) | Unified design system: typography, palettes, elevation, shapes, and accessibility-aware motion policies. |
| **Foundation** | [`:ui:platform-shims`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/platform-shims) | Clean platform decoupling for audio playback, microphone recording, image decoding, file pickers, and clipboard operations. |
| **Features** | [`:core:transfer`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer) | High-throughput streaming chunked transfer engine ([`MultiStreamDispatcher`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/multistream/MultiStreamDispatcher.kt)), pause/resume mechanics, SHA-256/BLAKE3 integrity verification, and path traversal guards. |
| **Features** | [`:core:messaging`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/messaging) | Chat persistence, delivery receipts (`FLASH_RCPT`), read states (`FLASH_READ`), reaction management, and typing indicators. |
| **Features** | [`:core:calling`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling) | WebRTC mesh audio and video calling, signaling coordinator, ICE renegotiation, and adaptive bitrates. |
| **Features** | [`:core:ptt`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/ptt) | Push-To-Talk voice messaging with floor control, audio streaming, and group voice distribution. |
| **Features** | [`:ui:chat`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/chat) | Rich Jetpack Compose messaging UI: message bubbles, composer, media viewer, adaptive dual-pane layouts, and navigation rail. |
| **Features** | [`:ui:callui`](file:///C:/Users/KaliOxygen/Downloads/Flash/ui/callui) | Voice and video calling screen, camera preview rendering, mute/speaker toggles, and participant grids. |
| **Orchestrator**| [`:core:engine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine) | Unified lifecycle coordinator binding discovery, networking, messaging, calling, and file transfer into a cohesive facade ([`FlashEngine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt)). |

---

## 4. Cross-Cutting Patterns

### 4.1 Strict Visibility & API Discipline
All `:core:*` modules compile with Kotlin's `explicitApi()` mode. Public types must explicitly declare their visibility and return types. Internal mechanics are encapsulated behind `internal` or `@FlashInternalApi`.

### 4.2 Clean Calling Seam
WebRTC dependencies are notoriously heavy. Flash isolates calling behind an optional compile-time seam (`attachCalling` / `detachCalling`) in [`FlashEngine`](file:///C:/Users/KaliOxygen/Downloads/Flash/core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashEngine.kt). If an application does not need voice/video calling, `:core:calling` and `:ui:callui` can be completely omitted from the build without breaking the transfer or messaging stack.
