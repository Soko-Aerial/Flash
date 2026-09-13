# Pairing gate runbook — desktop ↔ phone

**Purpose:** run the pairing half of the Phase 16 gate (cells **G2** and **G6**) and the
desktop-trust precondition that Phase 23's interop matrix needs. Written 2026-09-13.
**Who runs it:** a human with both endpoints physically present. Nothing here is automatable — the
whole point of a pairing gate is that a person looks at two screens and compares them.

---

## What is being proven

Pairing establishes **trust** between two devices by **numeric comparison**: both sides derive the
same 6-digit code from *both* fingerprints
(`core/security/src/commonMain/.../pairing/NumericComparisonCode.kt:60` — order-irrelevant, so the
two devices must display an identical code), and a human confirms they match. If the codes differ,
the handshake is being intercepted or one side is not who it claims.

That property is why the gate is manual. A test that compares the codes for you proves the code
derivation works, not that a human could have caught a mismatch.

Phase 26 (P2/ADR-035) added the piece that makes this meaningful on desktop: a **persisted** identity
keypair under `~/.flash/identity/id-key.bin`, DPAPI-protected. Before it existed, the desktop minted
a fresh identity every launch and TOFU trust could never survive a restart — which is why the phone
showed **Pair** instead of **Chat** for the desktop, forever.

## Preconditions

1. **Both endpoints on the same L2 network.** One router, or one hotspot with both joined as
   clients. Not "same Wi-Fi name" — same broadcast domain.
2. **Client isolation OFF.** Many guest/ISP routers block peer-to-peer traffic between wireless
   clients. If discovery sees nothing, this is the first suspect, not the last.
3. **Builds are green** (verified 2026-09-13):
   - `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL, APK at
     `app\build\outputs\apk\debug\app-debug.apk`
   - `./gradlew :desktop:compileKotlinJvm` → BUILD SUCCESSFUL
4. **Environment** for the desktop build (every new shell):
   `JAVA_HOME=C:\Users\KaliOxygen\.gradle\jdks\jetbrains_s_r_o_-21-amd64-windows.2` and
   `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix`.
5. **`-Djava.net.preferIPv4Stack=true` is on the `:desktop:run` task** — it is, as of commit
   `69453f0`. Without it JmDNS cannot bind on this host and step L1 fails with a `setsockopt`
   error in the console. If you see that error, the flag has been lost.

## Reset to a clean slate (do this before the first run, and between L6 runs)

| Endpoint | Action |
|---|---|
| Desktop | `rm -rf ~/.flash` — deletes `identity.properties`, `trust.properties`, `identity/id-key.bin`. **The next launch mints a new identity**, so the phone's trust in the old one is dead. |
| Desktop | Optionally `rm -rf ~/FlashReceived` (received files). |
| Phone | Settings → Apps → Flash → **Storage → Clear data** (not just cache — cache does not hold the identity). |
| **Phone** | **Toggle Wi-Fi off and on.** This clears the phone's mDNS cache — see the warning below, it will otherwise show peers that are not running. |
| **Desktop** | **Exit the app cleanly** (close the window, or Ctrl+C and let it finish). **Never** end it with `Terminate batch job (Y/N)? y`, and never kill the JVM. |

Clearing only one side is a legitimate test too — see **L6b**.

> ### ⚠️ A killed process leaves a visible ghost — read this before believing a peer list
>
> A clean shutdown runs `JmdsTransport.stop()`, which calls `unregisterAll()` and sends the mDNS
> **goodbye** (TTL=0), so peers drop the device immediately. A **killed** JVM sends nothing, and
> every peer keeps the record until its **TTL expires — up to ~75 minutes** for SRV/PTR.
>
> Consequence: after an unclean exit, the phone keeps listing a desktop that is not running, and
> `Terminate batch job` in the Gradle console is the usual cause. This cost an entire evening on
> 2026-09-13 — it was twice mistaken for a code defect.
>
> **Before reporting "device X is visible but not running" as a bug:** toggle the phone's Wi-Fi, or
> reboot it, then re-check. And confirm no advertiser is live by finding any stray JVM:
>
> ```powershell
> Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
>   Select-Object ProcessId, CreationDate, CommandLine | Format-List
> ```
>
> The **Kotlin compile daemon and the Gradle daemon are not suspects** — neither binds mDNS.
> A `DesktopInteropHarness` command line is one, though `main` now forces exit so a finished verb
> cannot linger. A **peer that still appears with the list empty** is cache, not a live advertiser.

---

## The ladder

Run these in order. Each rung assumes the one before it passed; when one fails, the diagnosis table
below tells you which layer to look at.

### L0 — both endpoints are alive and self-consistent

1. Launch the desktop app: `./gradlew :desktop:run`. Expect a 1200×800 "Flash" window.
2. On the desktop, open **Nearby**. Expect the local device to be listed/shown with its name.
3. On the phone, launch Flash and open **Nearby**.
4. **Pass:** neither endpoint shows an error state, and each shows its *own* identity (name,
   fingerprint where displayed).
   **Fail:** app crashes at launch → the desktop console names the layer; see the table.

### L1 — discovery (this is the flakiest rung)

1. With both on **Nearby**, wait up to ~30 s.
2. **Pass:** each device lists the other by name.
3. **Fail signals, and what each means:**
   - **Neither sees the other** → mDNS is not crossing the network: client isolation, different
     VLANs, or hotspot AP/client roles. This is a network problem, not a Flash bug. **Try the
     manual-connect fallback** (Phase 31) to confirm the network path works while discovery does not.
   - **Desktop console shows `Invalid argument: setsockopt`** → the `preferIPv4Stack` flag is
     missing (precondition 5).
   - **Desktop sees the phone but the phone does not see the desktop** → historically the asymmetric
     case (see the project's `nsd-hotspot-discovery` note on past incidents). Suspect the desktop's
     advertise path first.

### L2 — pairing, phone → desktop (the primary direction)

1. On the **phone's** Nearby, tap the desktop's row → **Pair**.
2. **Pass (step a):** the phone shows `AwaitingPeerConfirmation` with a **6-digit code**.
3. **Pass (step b):** the desktop shows its pairing dialog (`RequestReceived`) with a **6-digit
   code**, within a second or two.
4. **Pass (step c) — THE SECURITY CHECK:** **the two codes are identical, digit for digit.**
   Read them aloud. `NumericComparisonCode.derive` is order-irrelevant, so a mismatch is a real
   failure, not a formatting difference.
5. **Pass (step d):** accept on the desktop. Both move to `Paired`.
6. **Pass (step e):** the desktop's Nearby row for the phone flips from **Pair** to **Chat**, and the
   phone's row for the desktop does the same.
7. **Fail:** codes differ → stop. That is the one outcome the whole design exists to catch. Do not
   "try again"; record it and treat it as a security finding.

### L3 — pairing, desktop → phone (G3-reverse's pairing half)

Repeat L2 with the sides swapped: **desktop's** Nearby → phone's row → **Pair**.

This is not redundant. The desktop's coordinator and codec were written separately from the app's
(Phase 26-3) and verified only by *reading* the app's framing — four wire-parity bugs were caught
that way before compiling, which is evidence the two implementations differ in ways only a live run
can fully exercise.

### L4 — the decline path

Reset (or use a second pair). Start pairing again, then:

1. **Decline on the responder.** Pass: both sides return to `Idle`/`Declined`, **no trust row is
   created on either side**, and either side can immediately start a fresh attempt.
2. **Let it expire.** Start pairing and touch nothing. `FlashPairingRequestUi.expiresInSeconds`
   defaults to **30**; the screen shows a countdown. Pass: both sides end in `Expired`, and the
   *initiator* can retry (the app's flow passes an `onNeedRetry`).

The expiry path matters more than it looks: a stale request that never expires will block every
later attempt, and the failure looks like "pairing is broken".

### L5 — trust actually gates something

On the phone, open the conversation with the desktop (or attempt a transfer). **Pass:** it is
permitted — trust was the gate, and it is now open. Cross-check with L6.

### L6 — **restart survival — this is what P2 was for**

1. **Kill the desktop app entirely** (close the window / Ctrl+C the `gradle run`).
2. Relaunch: `./gradlew :desktop:run`.
3. **Pass:** the phone still shows the desktop as **Chat** (not **Pair**) — without re-pairing, and
   without the desktop announcing a new fingerprint.
4. **The deeper check:** the desktop's identity fingerprint is **the same as before the restart**.
   If it changed, the identity was not loaded from `~/.flash/identity/id-key.bin` and
   `PersistedFlashCrypto` silently degraded to an in-memory key. **That degradation is logged
   loudly by design** — check the console for it before concluding anything else.
5. **Restart the phone** (reboot, or force-stop and relaunch) and repeat: the desktop must still be
   **Chat**.

**L6b — one-sided reset (the interesting variant):** delete **only** the desktop's `~/.flash`. The
desktop now has a new identity. **Expected:** the phone shows the desktop as **Pair** again (new
fingerprint, no trust), while the desktop may still believe it trusts the phone's old entry. This
asymmetry is expected — trust is per-fingerprint — but confirm neither side ends up in a state it
cannot leave. If the desktop shows a stale trusted entry it cannot revoke, that is a real finding.

### L7 — revoke

On either side, revoke the peer. **Pass:** trust is gone on both, the row returns to **Pair**, and
re-pairing works. Verify on the desktop that `~/.flash/trust.properties` no longer lists the peer.

### L8 — after pairing: the things pairing unblocks

These are Phase 30/33 territory, listed so the gate run produces the full picture:

- **phone → desktop transfer**: works today (Phase 16's G3 passed on real hardware).
- **desktop → phone transfer**: **does not work through the UI yet** — the desktop has no send
  affordance at all (Phase 30). If it fails, that is the known gap, not a pairing failure.
- **calls**: not wired on desktop (Phase 33).
- **chat**: no desktop conversation screen (Phase 29). The desktop will navigate and show the chat
  list instead — also a known gap.

Do not record any of those three as pairing failures.

### L9 — the negative case (worth doing once)

Put the two devices on **different** networks (phone on cellular, desktop on Wi-Fi). **Pass:**
neither discovers the other, and **nothing** is presented for pairing. Flash is LAN-only by design
(ADR-025, empty `iceServers`) — a pairing prompt appearing across networks would mean something is
routing through a relay that should not exist.

---

## Diagnosis table

| Symptom | Layer | First thing to check |
|---|---|---|
| Desktop will not launch | build/env | console output; `JAVA_HOME` + `JAVA_TOOL_OPTIONS` set? |
| Console: `Invalid argument: setsockopt` | JmDNS bind | `preferIPv4Stack` on `:desktop:run` |
| Neither endpoint sees the other | network | client isolation; manual connect to confirm TCP works |
| One-way discovery only | JmDNS advertise | the `nsd-hotspot-discovery` note; suspect the desktop's advertise |
| Pair tapped, nothing happens | session | is a WS session up? `connectManual` needs a reachable host/port |
| Pairing dialog appears on one side only | framing | `FLASH_PAIR` frame not crossing — check the console status lines |
| **Codes differ** | **security** | **stop and record; do not retry** |
| Codes same, accept does nothing | state machine | `PairingSessionStateMachine` transition; check both consoles |
| Paired, then "Pair" again after restart | **P2 / identity** | the loud degradation log; is `~/.flash/identity/id-key.bin` present and readable? |
| `~/.flash/identity/id-key.bin` rewritten every launch | **P2 / DPAPI** | `CryptProtectData`/`CryptUnprotectData` round-trip on this user account |

## Recording results

Append to `docs/migration/logs/migration.md`, following the existing Phase 16 entries: one line per
rung with **PASS/FAIL**, the observed code match or mismatch, and any console output that mattered.
Update the README rows for **16** (G2/G6) and **23** (the desktop cells) in the same commit.

**A rung that was not run is not a rung that passed.** The Phase 21/22 records are explicit that
compile-verified is not gate-verified, and row 16 has been CLOSED on hardware before for exactly
this reason.
