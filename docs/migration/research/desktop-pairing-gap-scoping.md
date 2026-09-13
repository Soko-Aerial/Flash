# Scoping document — the desktop pairing gap (what the Phase 16/23 hardware day needs first)

**Date:** 2026-09-13
**Author:** Claude Code (glm-5.3-free), autonomous per the session /goal
**Status:** SCOPING + DECISION REQUEST — this document does not implement anything. It exists
because the 2026-09-12 blocker census found that Phase 16's G2/G6 and Phase 23's desktop M2/M7
would **fail even with a phone attached** until this gap closes, and R9 requires that be
recorded before the human books hardware time, not discovered during it.

---

## The gap, precisely

The Android app runs C2/C4 pairing end-to-end through three `:app`-scoped pieces plus one
crypto object:

1. **`PairingCoordinator`** (`app/src/main/java/com/transfer/flash/pairing/PairingCoordinator.kt`,
   317 lines) — drives the protocol, exposes `pairing`/`trustedPeers`/`messages` flows, sends
   `FLASH_PAIR` frames over the session map. **Verified platform-pure**: zero
   `android.*`/`java.*`/`androidx.*` imports (pure Kotlin + coroutines). Its constructor takes
   plain values + lambdas: `localFingerprintHex`, `localDeviceId`, `localName`, `localModel`,
   `ephemeralPublicKey`, `trustStore`, `scope`, `sendToPeer`, `timeSource`.
2. **`PairingFraming`** + **`PairingUiMapper`** (same directory, 141 + 35 lines) — also
   platform-pure (the mapper produces `:ui:chat`'s `NearbyTrustedPeerUi`/`FlashPairingRequestUi`,
   which the desktop shell already imports).
3. The engine facade routing in `DiscoveryEngineHolder` — `FLASH_PAIR` frames are handed to
   `pairingCoordinator.onFrame(...)` on the text lane, and a pairing **hello** is sent on every
   session-up (`DiscoveryEngineHolder.kt:1159`).
4. **`FlashCrypto` construction** — Android uses `KeystoreFlashCrypto(context)`
   (androidMain; identity key non-exportable in AndroidKeyStore). The desktop equivalent is
   `SoftwareFlashCrypto`, which is **`internal` to `:core:security`** — no public desktop path
   exists.

The desktop stack (`DesktopEngine` + the Phase 16 harness) parses only `FLASH_XFER` on the text
lane and wires no pairing. Three concrete consequences on the wire today:

- An Android peer's session-up pairing **hello lands nowhere** on desktop (ignored text frame).
- Tapping Pair on the Android side against a desktop peer never completes a handshake.
- The **SAS code** (G2's core assertion, M2's dialog) can therefore never be compared.

## What is already desktop-ready (measured, not assumed)

- `DefaultFlashPairingProtocol`, `PairingSessionStateMachine`, `NumericComparisonCode`,
  `FlashPairingFrames`, `FlashFingerprint` — all **commonMain** in `:core:security`. The SAS
  derivation (`NumericComparisonCode.derive`) is a pure symmetric function of both fingerprints
  (SHA-256, sorted-concatenation, mod 10^6) with per-platform parity already pinned by tests on
  both targets (Phase 07). Nothing in the pairing *math* needs porting.
- `FlashIdentityStore`/`FlashTrustStore` contracts — desktop implementations shipped in Phase 21
  (`DesktopIdentityStores`, file-backed under `~/.flash/`).
- `:ui:chat`'s `FlashPairingDialog` + `FlashNearbyScreen` pairing surface — 100% common since
  Phase 20, already rendered by `DesktopShell` (with `pairingRequest = null` / `Idle` today).
- `DesktopTrustStore.trustPeer/revokeTrust` — the trust mutations G6/M7 need to prove.

## The one genuine decision: the desktop `FlashCrypto` identity

Everything else is mechanical wiring. The blocker that is NOT mine to decide is **how a desktop
constructs its `FlashCrypto` identity**, because the two existing implementations each fail a
different way:

| Option | What it is | Why it needs a human |
|---|---|---|
| **P1 — publicize a desktop factory over `SoftwareFlashCrypto`** | A tiny `:core:security` jvmMain (or commonMain-factory) object exposing the software implementation for desktop use | `SoftwareFlashCrypto`'s own KDoc says "NOT FOR PRODUCTION IDENTITY STORAGE… an exportable, in-memory key… exists ONLY for host unit tests and an explicitly-chosen emergency fallback… any such use must be flagged loudly and tracked as security debt". Publicizing it as *the* desktop path is exactly that debt, by choice. Under R8 (security posture untouchable) this is a security-posture decision. |
| **P2 — persist a software keypair under `~/.flash/`** | A desktop `FlashCrypto` whose identity key is generated once (JCA `KeyPairGenerator`, same algorithms via the existing `PlatformCrypto` seam) and stored in a keystore file (e.g. passwordless PKCS12 or raw SPKI+encrypted-private) | Better posture than P1 (key survives restart, still exportable at rest). But it is **new security-relevant code** in the R8-protected `core:security` surface: file format, at-rest encryption, and failure modes all need review. A phase file + review, not an afternoon bolt-on. |
| **P3 — hold pairing desktop-scope open until 09B-2's driver decision** | Defer; keep G2/G6 Android↔Android only until the encrypted-desktop-storage work (the same human decision that blocks G7) also answers key storage | Zero new security surface; the hardware day can still run G1/G3/G4/G5 + M1/M3–M6 and record G2-desktop + M2/M7-desktop as **DEFERRED**, honestly. |

**Recommendation (for the human to confirm, not for me to enact): P3 for the first hardware
run** — it lets the gate prove the four transfer directions and discovery on day one, records
the pairing-desktop cells as deferred with a named reason (the same shape as G7's existing
09B-2 deferral), and folds the P1/P2 choice into the 09B-2 security review where encrypted
desktop storage is already being decided. P2 is the right eventual answer for a shipped desktop
pairing experience; P1 is acceptable only for an interim developer-build if the debt is logged.

## The mechanical wiring (any option; sketched for the phase file that follows)

Whichever P is chosen, closing the wire gap itself is small and platform-pure:

1. Lift `PairingCoordinator` + `PairingFraming` + `PairingUiMapper` from `:app` to a shared
   home (`:core:engine` commonMain is the natural one — it already aggregates security +
   messaging + the text-lane routing; `:app` then depends on it, and its `DiscoveryEngineHolder`
   deletes the local copy — R1 applies: one phase, one commit, `:app` behaviour unchanged).
2. Route `FLASH_PAIR` frames on the text lane (both `:app`'s holder and `DesktopEngine`'s
   `handleInboundText`) into the coordinator's `onFrame`, and send the pairing hello on
   session-up in `DesktopEngine`'s session collector (the same line `DiscoveryEngineHolder.kt:1159`
   has).
3. Feed `DesktopEngine.pairing`'s flows into `DesktopShell`'s `nearby` state (the screen's
   `pairingRequest`/`pairingPhase`/`pairingSecondsLeft` params exist and are currently hardwired
   null/Idle — one derived-state block, same as `trustedPeers`).
4. `PairingCoordinator` construction needs `ephemeralPublicKey` (from the chosen crypto) and
   `sendToPeer` (desktop: `WsSession.connection.sendTextAsync`, which exists in jvmMain) — both
   one-liners once P is decided.

Estimated size: one phase-file-sized change (~4 sub-steps, mostly moving 493 platform-pure
lines + two routing lines + one shell block), plus the P decision gating its start.

## Why this is a document, not a phase

- The P1/P2/P3 choice is a security-posture decision under R8's shadow — explicitly the human's.
- Under the session goal ("finish all the phases"), the calling phase is already research-
  gated the same way; this is the same honest shape: **the plan's remaining work is decision-
  gated, and the decision is named.**
- The migration's own precedent is exactly this: D5=C's driver questions (09B-2) are open
  sub-decision items recorded in README/DECISIONS, not improvised.

## What the human should do with this

1. Answer P (P1/P2/P3 — recommendation P3 for the first hardware run).
2. If P3: book the hardware day; run G1, G3, G4, G5 both directions + W→W/A→A; record
   G2-desktop + G6-desktop-TOFU-rejection + Phase 23's M2/M7-desktop as DEFERRED (P) alongside
   G7's existing 09B-2 deferral. If P1/P2: the pairing phase runs first, then the full matrix.
