# Flash Developer Guide & Architecture Manual

Welcome to the comprehensive Flash developer documentation. This guide covers the complete Kotlin Multiplatform architecture, individual module contracts, setup instructions, integration examples, extreme low-resource device deployment, custom platform extensions, and the open peer-to-peer wire protocol.

---

## 1. Documentation Map

```text
docs/developer-guide/
├── README.md                                  # You are here
├── getting-started/
│   ├── beginner-guide.md                      # Quickstart, setup, Maven/Gradle coordinates, Hello World
│   └── architecture-overview.md               # Clean architecture layers, contracts, data flow, reactive state
├── modules/
│   ├── core/
│   │   ├── core-common.md                     # Domain models, FlashResult, FlashPerformanceMode, byte math
│   │   ├── core-persistence.md                # Room encrypted SQLite, DAOs, entities, schema migrations
│   │   ├── core-security.md                   # ECDH P-256 pairing, AES-256-GCM framing (E2eFrameCodec), KeyStore
│   │   ├── core-discovery.md                  # NSD (mDNS/DNS-SD) LAN discovery, Wi-Fi Direct p2p
│   │   ├── core-network.md                    # WebSockets over TLS, keepalives, JvmNetworkWatcher
│   │   ├── core-transfer.md                   # MultiStreamDispatcher, ReceivePipeline, Chunker, resume bit-vectors
│   │   ├── core-messaging.md                  # RealFlashChatRepository, wire framing (FLASH_MSG, RCPT, READ, etc.)
│   │   ├── core-calling.md                    # WebRTC 1:1 and mesh group calls, ICE restart, signaling
│   │   ├── core-ptt.md                        # Push-To-Talk voice messaging engine, half-duplex arbitration
│   │   └── core-engine.md                     # FlashEngine, DesktopEngine, DiscoveryEngineHolder orchestrators
│   └── ui/
│       ├── ui-theme.md                        # FlashTheme, design tokens, colors, typography, shapes, icons, motion
│       ├── ui-platform-shims.md               # Platform shims: audio recording/play, file picking, clipboard, images
│       ├── ui-chat.md                         # Chat screens, message list, bubbles, composer, navigation rail, dual-pane
│       └── ui-callui.md                       # FlashCallScreen, video track rendering, call controls
├── examples/
│   ├── standalone-modules.md                  # Using core modules independently (isolated crypto, transfer, discovery)
│   ├── full-stack-app.md                      # End-to-end integration: Discovery + Pairing + Chat + Chunked Transfer
│   └── headless-daemon.md                     # Running Flash headless as a background service/daemon (no Compose UI)
└── scenarios/
    ├── scenario-1-ultra-low-resource.md       # Building for extreme resource-constrained devices (<512MB RAM, IoT, POS)
    ├── scenario-2-custom-extensions.md        # Custom implementations: Transports (BLE/LoRa), Storage, Crypto, UI
    └── scenario-3-open-protocol-interop.md    # Open wire protocol spec & runnable client samples (Python, Rust, Go)
```

---

## 2. Quick Navigation by Use-Case

* **New to the project?** Start with [getting-started/beginner-guide.md](getting-started/beginner-guide.md).
* **Integrating a specific module?** Check the dedicated guides in [modules/core/](modules/core/) or [modules/ui/](modules/ui/).
* **Building for low-RAM / embedded hardware (<512MB RAM)?** Read [scenarios/scenario-1-ultra-low-resource.md](scenarios/scenario-1-ultra-low-resource.md).
* **Extending Flash with custom transports (e.g. BLE, LoRa) or custom storage?** Read [scenarios/scenario-2-custom-extensions.md](scenarios/scenario-2-custom-extensions.md).
* **Writing a Python, Rust, or Go client to talk to Flash?** Read [scenarios/scenario-3-open-protocol-interop.md](scenarios/scenario-3-open-protocol-interop.md).
