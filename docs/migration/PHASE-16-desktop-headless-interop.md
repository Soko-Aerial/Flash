# Phase 16 — GATE: headless desktop ↔ Android interop

**Blocked by:** Phase 15 (desktop transport) — which itself requires 13 (desktop file
I/O) and 14 (desktop discovery). Transitively this gate needs the entire core stack
converted (07–12) and the three desktop edge implementations (13–15) in place.
**Risk: THIS IS A GATE, not a code phase.** It writes almost no production code. Its job
is to *prove a fact* and to **block the whole UI track (17–22) from merging until that
fact is true.** If it fails, the migration is not viable in its current shape and you fix
13–15 (or the seams they implement) before doing anything else.
**Decisions touched:** D5 (persistence) decides whether *resume-across-restart* is testable
on desktop in this gate — under D5 = A (in-memory desktop persistence) it is not, and that
sub-check is explicitly deferred; everything else is required.

## What this gate is actually for

The migration's definition of done is **interop, not "the desktop window opens"**:
Android↔Android, Android↔Windows, Windows↔Windows, one protocol, encryption on. This gate
proves the **cross-platform** half of that — that a Flash transfer initiated on one OS
completes, byte-for-byte and encrypted, against the *other* OS — **using no UI at all**.

Why headless, and why now: the shared Compose UI (phases 17–22) is a large investment. It
is only worth making if the shared protocol actually crosses the platform boundary. If
desktop and Android cannot discover each other and move an encrypted file between them, a
shared UI is decoration on something that does not work. So this gate runs **before any UI
phase merges** (README "Two hard gates"). It is the go/no-go for the entire second half of
the project.

This is also the first time the **cross-platform discovery** path is exercised end to end:
Android's `NsdManager` advertising/browsing must meet desktop's JmDNS (Phase 14, decision
D6) on the wire, using the same service type and the same `TxtCodec`-encoded TXT records.
Per the `nsd-hotspot-discovery` memory, NSD asymmetry over a Wi-Fi hotspot has already cost
this project a debugging cycle once; cross-platform mDNS is exactly where that class of bug
resurfaces. Expect discovery — not crypto — to be the hard part here.

## Preconditions — do not start until all are true

1. **Phases 07–12 complete and logged** (all core modules are KMP; the shared protocol and
   transfer engine compile for `jvm()`). Verify each has a `logs/migration.md` entry.
2. **Phases 13–15 complete and logged**: desktop file I/O (13), desktop discovery (14,
   JmDNS per D6), desktop transport (15) — i.e. every platform seam the engine needs has a
   working `jvmMain` `actual`/implementation. If any desktop `actual` still throws
   `NotImplementedError`/`TODO`, this gate cannot pass; finish that phase first.
3. **The Android app still builds and runs** (`:app:assembleDebug`). This gate must not have
   regressed Android; a green desktop path that broke Android is a failure.
4. **Two real endpoints are available on one network:**
   - A **desktop JVM** (the dev machine) able to run the headless harness below.
   - An **Android endpoint** — a physical device or emulator — reachable from the desktop.
     Prefer a physical device on the **same Wi-Fi**; if you must use a Wi-Fi **hotspot**
     topology, read the `nsd-hotspot-discovery` notes first, because discovery asymmetry
     shows up there specifically.
5. **Encryption is ON** in both endpoints' configuration (this is the default; do not add a
   flag to turn it off — see "Do NOT").

## Build the headless harness (the only code this phase adds)

You need a way to drive the **public API** (`Flash.create(...)`, `engine.transfers.*`) with
no Compose UI, on **both** platforms, so a human (or a script) can run sender on one and
receiver on the other. Add these under test/harness source sets — they are **not** shipped
in the library artifact.

**Desktop harness** — a `jvm()` `main()` (put it in a small `:desktop-harness` or a
`jvmTest` fixture, whichever Phase 13–15 established) that:

1. Builds the engine via the **desktop** factory created in Phase 12 (the jvm equivalent of
   `Flash.create`). No `Context`.
2. Takes argv: `advertise <friendlyName>` | `discover` | `send <peerId> <filePath>` |
   `receive <outDir>`.
3. Prints, line per event: discovered peers (id + name + addresses), pairing/SAS state,
   transfer id, progress ticks (bytesTransferred/total), terminal state, and the SHA-256 of
   the completed file.
4. Keeps encryption on and prints the negotiated fingerprint / SAS code so it can be
   compared to the Android side.

**Android harness** — reuse the existing dev console / stress-test screen in `:app` if it
already exposes send/receive against the public API; otherwise add an **instrumented test**
(`androidTest`) or a tiny headless `Activity`/service entry that performs the same argv-like
actions and logs the same event lines to `logcat`. Do **not** build new production UI for
this — it is a test harness.

> Keep both harnesses thin: they only call the public API and print. All real logic already
> lives in the shared engine. If a harness needs to reach into internals, that is a smell —
> the public API is what the future UI will use too, so it must be sufficient here.

## The scenarios this gate must pass

Run each scenario twice — once in **each direction** — with **encryption on**. "Verify
bytes" means: compute SHA-256 of the source file and of the received file and confirm they
are identical (the harness prints both).

| # | Scenario | Direction(s) | Pass condition |
|---|---|---|---|
| G1 | **Discovery** — one side advertises, the other lists it | Desktop advertises→Android discovers; Android advertises→Desktop discovers | The peer appears with correct friendly name and a reachable address; the TXT record decodes via `TxtCodec` identically on both sides |
| G2 | **Pairing / SAS** — first-contact trust | both directions | The SAS numeric-comparison code is **identical** on both endpoints; trust is established; the fingerprint matches |
| G3 | **Small file** (e.g. 1 KB text) | Desktop→Android and Android→Desktop | Transfer reaches the completed state; received SHA-256 == source SHA-256 |
| G4 | **Large file** (≥ 200 MB, to exercise chunking + backpressure) | both directions | Completes; SHA-256 matches; progress ticks are monotonic and reach 100% |
| G5 | **Progress + cancel** — cancel mid-transfer | both directions | The transfer moves to a cancelled state on **both** ends promptly; no partial file is presented as complete; a subsequent transfer still works |
| G6 | **Encryption is actually on** | both directions | Capture the wire (or assert in-harness) that payload frames are ciphertext, and that G2's fingerprint gated the session; a tampered/again-pinned peer is rejected per TOFU |
| G7 | **Resume across restart** — *conditional on D5* | both directions | **Only if desktop persistence exists (D5 = B/C).** Under D5 = A (in-memory desktop), desktop cannot resume across process restart — record this scenario as **DEFERRED (D5=A)**, and still verify Android→Android resume separately if that is claimed to work. |

**Repeat G1–G3 on the Wi-Fi-hotspot topology** if that is a supported deployment, because
that is where NSD/mDNS asymmetry historically appeared. If discovery works on a normal AP
but not on a hotspot, that is a **Phase 14 defect** (interface binding / multi-homed
`InetAddress` selection), not a reason to weaken this gate — send it back to 14's JmDNS
interface-enumeration work.

## Verification gate — the go/no-go

- [ ] G1 discovery — both directions.
- [ ] G2 SAS/pairing — codes match both directions.
- [ ] G3 small file — bytes identical both directions.
- [ ] G4 large file — bytes identical, progress monotonic, both directions.
- [ ] G5 cancel — clean cancel on both ends, both directions.
- [ ] G6 encryption provably on; TOFU rejects a changed peer.
- [ ] G7 resume — passed **or** explicitly logged DEFERRED (D5 = A).
- [ ] `:app:assembleDebug` still green (Android not regressed).

**If every non-deferred box is checked, the gate is OPEN**: UI phases 17–22 may now merge.
**If any is unchecked, the gate is CLOSED**: do not merge any UI phase; fix the responsible
core/desktop phase and re-run.

## Do NOT

- **Do NOT disable or weaken encryption to make the gate pass** (CONVENTIONS R8; charter
  principle 3). A green transfer with encryption off proves nothing this gate cares about.
- **Do NOT substitute a same-platform transfer** (desktop↔desktop or Android↔Android) for
  the cross-platform scenarios. The whole point is crossing the OS boundary.
- **Do NOT ship the harness in the published library.** It lives in test/harness source
  sets only.
- **Do NOT merge any UI phase (17–22) while this gate is closed.**
- **Do NOT "fix" a discovery failure by hardcoding the peer address.** If discovery fails,
  that is the bug this gate exists to catch — fix Phase 14.

## Rollback

There is little production code to roll back — this phase adds harnesses, not library code.
If a harness itself is broken, revert it. A **failed gate is not rolled back**; it is a
signal to return to the failing upstream phase (usually 14 discovery or 15 transport).

## Log entry (mandatory)

Append one entry to `docs/migration/logs/migration.md`:

- The G1–G7 results (pass / fail / deferred), each direction, with the file sizes used and
  the SHA-256 match confirmations.
- The network topology tested (same AP vs hotspot), because that context matters for the
  next reader.
- The negotiated fingerprint/SAS match evidence for G2/G6.
- Explicit statement of the gate verdict: **OPEN** (UI may merge) or **CLOSED** (with the
  phase to return to).
- If G7 was deferred, name D5 = A as the reason.
