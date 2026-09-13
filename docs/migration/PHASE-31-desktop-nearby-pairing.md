# PHASE-31 — Desktop Nearby & pairing (at parity — this phase is a fallback and a gate)

**Status:** AUTHORED (2026-09-13, planning only). **Depends on Phase 26** (executed) and **Phase 27**.
**Risk:** LOW — the screen is already correct; the new code is a debugging affordance.
**Decisions relied on:** D8 = A, ADR-035 (P2/DPAPI identity).

---

## What this phase is for — and what it is NOT for

**Nearby is already at call-site parity.** Verified against both hosts:

| Callback | Android `MainActivity.kt:1346–1375` | Desktop `DesktopShell.kt:292–326` |
|---|---|---|
| `onPairClick` | resolve endpoint → `connectManual` → `engine.pairing?.beginPair` | endpoint → `net.connectManual` → `engine.pairing.beginPair` |
| `onChatClick` / `onChatTrustedClick` | `openConversation` + navigate | **identical** |
| `onRevokeClick` | `engine.pairing?.revoke(id)` | `engine.trust.revokeTrust(FlashDeviceId(id))` |
| `onAcceptPairing` / `onDeclinePairing` | `pairing?.acceptLocal()` / `declineLocal()` | `pairing.acceptLocal()` / `declineLocal()` |
| Pairing dialog | `FlashPairingDialog` via `FlashNearbyScreen.kt:164–173` | **the same composable, same call site** |

Phase 26-3 wired the desktop coordinator to the **same** commonMain protocol classes the phone uses,
and the desktop codec was byte-verified against the app's (`PairRequest`/`PairConfirm`/`Paired`
field names, `FLASH_PAIR` framing, standard Base64). So there is **no transformation work left on
this screen**, and a phase that claimed otherwise would be inventing it.

What this phase adds is the one thing that makes the pairing gate *debuggable* when mDNS misbehaves,
plus the gate execution itself.

## The real gap: there is no way to type an IP address

Verified by grepping every `connectManual` call site in the repo: **every single one passes the
host and port of an already-discovered endpoint.** Neither host has a manual-entry UI. The only
place a raw host/port can be supplied is the headless harness
(`core/engine/src/jvmTest/.../DesktopInteropHarness.kt:393`, its `connect` subcommand).

That matters because **discovery is the flakiest link in the whole chain** on this hardware:

- Phase 16's very first real run failed with JmDNS `setsockopt` on Windows, and only a JVM flag
  fixed it (`-Djava.net.preferIPv4Stack=true`). Phase 26-adjacent work just hit it again on
  `:desktop:run`, which is why `desktop/build.gradle.kts` now carries that flag.
- The NSD-vs-hotspot asymmetry is a recorded, previously-shipped bug class (see the
  `nsd-hotspot-discovery` note in the project's own memory of past incidents).
- Wi-Fi client isolation on a router, and hotspot AP/client roles, both silently block mDNS while
  leaving direct TCP perfectly functional.

When mDNS fails, the *product* is broken for the user — but for **testing the pairing and transfer
path**, a manual `connect` distinguishes "discovery is broken" from "pairing is broken" in one step
instead of an afternoon. That diagnostic leverage is the entire justification for this phase.

## Design

1. **Manual connect affordance on Nearby, both hosts.** A small "Connect by IP" action (desktop:
   a header button + a text field; Android: a dialog) that calls
   `network.connectManual(host, port)` and then lets the peer appear in the list. The screen already
   accepts the resulting state — no `FlashNearbyScreen` signature change is needed if the affordance
   lives in the shell's chrome.
   - **Port discovery problem:** the desktop binds an ephemeral port (`network.start(0)`,
     `DesktopEngine.kt:263`), so the user must read the port off the *host's* UI. The Nearby screen
     should therefore **display the local identity and listening port** on both hosts, or manual
     connect is unusable. That display is the second half of this phase.
2. **A `--host`/`--port` launch override for `:desktop`** (optional, cheap): lets a test session pin
   the port so it can be scripted and repeated. Worth it for the gate; note it as a test affordance,
   not a product feature.
3. **Run the gate.** This phase's acceptance is not a compile gate — it is the human runbook
   (`PAIRING-GATE-RUNBOOK.md`) executed against real hardware, with its results recorded in
   `logs/migration.md` and the Phase 16/23 rows updated.

## Do NOT

- **Do NOT rewrite the pairing protocol, the codec, or the numeric-comparison display.** Phase 26
  verified all three against the app byte-for-byte. Touching them is how a wire-parity bug returns —
  and four were already caught that way.
- **Do NOT add a manual-connect path that bypasses trust.** A manual connection creates a *session*;
  it must not create *trust*. Pairing (fingerprint + numeric code) remains the only trust gate, on
  both hosts.
- **Do NOT let manual connect become a hidden default.** If a user has to type an IP for the product
  to work, discovery has failed and that is the bug to fix — this is a diagnostic, and its UI should
  read like one.
- **Do NOT weaken `-Djava.net.preferIPv4Stack=true`** while debugging discovery; if IPv6 support is
  wanted, fix the bind in `JmdnsTransport` (as the flag's own comment says).

## Sub-step plan

| # | Sub-step | Content |
|---|---|---|
| 31-1 | Local identity display | Show this device's name, fingerprint and **listening port** on Nearby, both hosts. Prerequisite for 31-2 to be usable. |
| 31-2 | Manual connect | Shell-level affordance → `connectManual(host, port)`. Both hosts. |
| 31-3 | Port override (optional) | `:desktop` launch argument to pin the WS port for repeatable gate runs. |
| 31-4 | **Gate execution (human)** | Run `PAIRING-GATE-RUNBOOK.md` end to end; record every step's observed result. |
| 31-5 | Log + README | Honest entry; update rows 16, 23, 26. |

## Why this is a phase and not a footnote

Because the alternative is what the project has today: row 23 marked **"gate"** with desktop cells
DEFERRED, and no path to a *diagnosis* when a pairing attempt fails on hardware. A test that cannot
tell you which layer broke produces a bug report, not a fix.
