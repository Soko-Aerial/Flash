# Phase 23 — GATE: the full 4-way interop matrix

**Blocked by:** Phase 22 (adaptive desktop screens) — i.e. the entire UI track is built and
the desktop app shell (21) runs the shared Compose UI. Transitively this needs everything:
07–16 (core + desktop + the headless gate) and 17–22 (UI).
**Risk: THIS IS THE FINAL GATE.** Nothing is published (Phase 24) until this passes. Like
Phase 16 it adds almost no production code; it *proves the product-level fact* that Flash is
one app speaking one protocol across two operating systems, driven through the **real UI**,
not a harness.
**Decisions touched:** D8 (do the §15 desktop screens exist) determines *what* the desktop
UI looks like — under D8 = A the desktop runs the existing chat UI adaptively laid out — but
the transfer-correctness matrix below is required regardless of D8.

## What this gate is actually for

Phase 16 proved the protocol crosses platforms **headlessly**. This gate proves the *whole
shipped experience* works in **all four directions**, through the **actual Compose UI on
both platforms**, with encryption on:

```
            RECEIVER
            Android        Windows
SENDER  ┌─────────────┬─────────────┐
Android │  A → A       │  A → W       │
        ├─────────────┼─────────────┤
Windows │  W → A       │  W → W       │
        └─────────────┴─────────────┘
```

The difference from Phase 16 is **the UI is now in the loop**: discovery lists render in
the shared composables, pairing/SAS is confirmed by tapping the real dialog, transfers are
started from the real picker (D7 FileKit / shim) and observed on the real progress UI,
cancellation uses the real control. A protocol that works headless but whose UI never
surfaces a peer, or never lets a user confirm an SAS code, fails here — and better here than
in a published release.

"Done" for the migration (charter Definition of Done) is exactly this matrix passing with
encryption on, progress/cancel/resume preserved, and Android behaviour unregressed. This
gate **is** that definition, executed.

## Preconditions

1. **Phase 16 is OPEN** (headless cross-platform already proven). This gate does not replace
   16; it extends it through the UI. If 16 is closed, stop — fix that first.
2. **Phases 17–22 complete and logged**: UI resources (17), theme (18), platform shims (19,
   D7), shared chat UI (20), desktop app shell (21), adaptive desktop screens (22, D8).
3. **Both apps run as real apps:**
   - Android: `:app:assembleDebug` installs and launches on a device/emulator.
   - Desktop: the Phase 21 desktop shell launches a window running the shared Compose UI.
4. **Two of each platform are available** for the same-platform cells:
   - A → A needs two Android endpoints (two devices, or device + emulator).
   - W → W needs two desktop endpoints (two machines, or two processes on distinct
     interfaces/ports — but prefer two machines to exercise real discovery).
   - A ↔ W needs one of each.
5. **Encryption on** everywhere (default; do not add an off switch).

## The matrix — every cell, through the real UI

For **each of the four direction cells** (A→A, A→W, W→A, W→W), perform this scripted run
**in the UI** and record pass/fail:

| Step | Action (in the UI) | Pass condition |
|---|---|---|
| M1 | Open both apps; sender browses for peers | Receiver appears in the sender's device/peer list with correct friendly name |
| M2 | Initiate pairing; compare the SAS code shown on both screens; confirm | Codes are identical; both UIs advance to a paired/trusted state |
| M3 | Pick a **small** file via the real picker and send | Receiver's UI shows an incoming offer; on accept, transfer completes; received bytes' SHA-256 == source |
| M4 | Send a **large** file (≥ 200 MB) | Progress UI advances monotonically to 100% on both ends; SHA-256 matches |
| M5 | Start a large send, then **cancel** from the UI mid-transfer | Both UIs show cancelled promptly; no partial file presented as complete; app remains usable |
| M6 | **Resume** (conditional on D5) | If desktop persistence exists, a transfer interrupted by app restart resumes; under D5 = A, mark desktop-involving resume **DEFERRED (D5=A)** but still verify A→A resume |
| M7 | Reject/changed-peer (TOFU) | A peer whose key changed is flagged/blocked by the UI, not silently trusted |

That is **4 cells × M1–M7**. A cell passes only when all its non-deferred steps pass.

## Regression checks (Android must be unchanged for existing users)

- [ ] A → A behaves as it did **before** the migration (same discovery, same transfer, same
      screens). This is the "we did not rewrite the working Android app" proof.
- [ ] Android dynamic color (Monet), if D4 = A, still applies on Android 12+.
- [ ] Toast-vs-Snackbar (D7a): whichever was chosen is what ships on Android, and it was
      signed off.

## Verification gate — publish go/no-go

- [ ] Cell A → A — all steps.
- [ ] Cell A → W — all steps.
- [ ] Cell W → A — all steps.
- [ ] Cell W → W — all steps.
- [ ] Encryption provably on in every cell; TOFU (M7) enforced in every cell.
- [ ] Resume (M6) passed or logged DEFERRED (D5 = A), consistently.
- [ ] Android regression checks pass.

**All boxes checked ⇒ the gate is OPEN and Phase 24 (publishing) may proceed.**
**Any box unchecked ⇒ CLOSED; nothing publishes.** Route the failure to the owning phase
(UI issue → 17–22; protocol/transport issue → 10–15; discovery → 14).

## Do NOT

- **Do NOT publish anything (Phase 24) while this gate is closed** (charter principle 7).
- **Do NOT weaken encryption** to pass a cell (R8).
- **Do NOT declare the matrix passed on 3 of 4 cells.** All four directions are the product.
- **Do NOT accept a same-platform result as a stand-in** for a cross-platform cell.
- **Do NOT count a headless (Phase 16) pass as satisfying a UI cell** — the UI must be in
  the loop here.

## Rollback

No production rollback — this is acceptance testing. A failed matrix is a signal, not a
change to revert. Fix the owning phase and re-run the affected cells.

## Log entry (mandatory)

Append to `docs/migration/logs/migration.md`:

- A filled copy of the 4×7 matrix (pass/fail/deferred per cell per step), with file sizes
  and SHA-256 match confirmations.
- The hardware/topology used for each cell (which devices, which network).
- The Android regression result.
- The verdict: **OPEN → Phase 24 may publish**, or **CLOSED → return to phase N**.
- Any DEFERRED (D5 = A) resume cells, named explicitly.
