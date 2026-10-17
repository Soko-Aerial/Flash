# Current Handoff — Stream R-chat

## Current branch
`agent/R-chat` (worktree `E:\FlashWork\R-chat`)

## Last verified build
`4abbdc22` (Tests & engine pass; documentation pending commit)

## Current phase
Phase R3 Complete — Companion Mode Chat Over Hardware Radio (Tasks 1 through 6 finished).

## Working features
- Domain-separated HKDF radio session keys derived from existing `FlashTrustStore` pairing (`RadioPairingAdapter`).
- Directional key separation (send vs recv) and rotating station tags/labels.
- Persistent counter & replay stores (`PersistentRadioStore`, `DesktopFileRadioStatePersistence`, `AndroidPrefsRadioStatePersistence`) with high-water mark ahead-of-use reservation and restart jump.
- Radio outbox port (`RadioOutboxPort`) and adapter (`RadioChatBridge`) in `:core:messaging`.
- Multi-lane priority dispatch in `RadioChatEngine` (EMERGENCY > TEXT > ACK > LOCATION).
- Delivery state progression: `'sent by radio, waiting'` upon driver transmit; `'delivered'` ONLY upon cryptographic ACK frame receipt.
- Inbound packet ingestion: AEAD authentication/decryption, sliding replay window, deduplication cache, segment reassembly with 10-minute timeout, and automatic ACK generation.
- Segmented text up to 3,008 bytes; refusal of larger text.
- Rejection of photos, files, and voice notes with `"Photos and files cannot be sent by radio"`.
- Security Profiles on air: Profile M (ciphertext only, authorization gated) and Profile A (signed clear text with callsign).
- Full 2-station simulation test suite (`RADCHAT-U01..U12`), `RadioChatBridgeTest`, `DesktopRadioOutboxAdapterTest`, and `PersistentRadioStoreTest` all passing.
- 5 mutation checks executed and verified.
- Owner hardware runbook `logs/streams/R-chat/runbook-R3.md` covering BT-R05, BT-R08, and BT-R09.

## In progress
- None (Stream R-chat assignment fully completed).

## Broken
- None.

## Last change
Completed Tasks 1-6: wrote owner runbook `runbook-R3.md`, updated stream ADRs, errors, experiments, test backlog, and comprehensive progress log.

## Last test
- `:core:radio:jvmTest` (12/12 passed)
- `:core:messaging:jvmTest` (7/7 passed)
- `:desktop:jvmTest` (`DesktopRadioOutboxAdapterTest` passed)
- `:app:compileDebugKotlin` (`BUILD SUCCESSFUL`)

## Known blockers
- None.

## Recommended next task
1. Owner executes physical hardware verification using `logs/streams/R-chat/runbook-R3.md` with two Vero Telecom VR-N76 / BTECH UV-Pro handheld transceivers.
2. Stream R-ui / UI owner exposes the `callsign` field in Radio Gateway settings.

## Files most relevant to next task
- `logs/streams/R-chat/runbook-R3.md`
- `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/chat/RadioChatEngine.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/radio/RadioChatBridge.kt`
