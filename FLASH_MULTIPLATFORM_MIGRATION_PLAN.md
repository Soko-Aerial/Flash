# Flash Multiplatform Migration — Charter

> **This file is a charter, not an execution plan.** The original ~1000-line plan was
> replaced on 2026-08-30 after a source-level audit. **All executable work now lives in
> [`docs/migration/`](docs/migration/README.md).** Start there.
>
> This file survives only to state the goal, the non-negotiable principles, and — because
> the old plan made several factually wrong claims about the code — to record what was
> struck and why, so nobody re-derives the migration from the wrong premises.

## The goal

> **SCOPE CHANGE 2026-09-03.** The human's stated target is **"Linux and all platforms"**. The
> three paragraphs below originally said "Android and Windows desktop (JVM)" and "iOS /
> Kotlin-Native is out of scope". Both are **struck**. The goal as restated here is the current
> one; **D1 = Option B** (strict `commonMain`) is settled and load-bearing, not open.

Evolve Flash from an Android-only Kotlin library into a **Kotlin Multiplatform** library that
runs on **Android, desktop JVM (Linux, Windows, macOS), and Kotlin/Native targets including
iOS**, *without rewriting the working Android implementation*. Flash's protocol and transfer
engine become the shared foundation; platform networking, discovery, filesystem, and lifecycle
stay at the edges.

Note that one `jvm()` target covers all three desktops — Linux does **not** get its own target
and there is no `linuxMain`. Code in `jvmMain` must be OS-neutral (see the CONVENTIONS.md
amendment). It is **iOS/Native**, not Linux, that forces the strict-`commonMain` rewrites.

"Done" is **interop**, not "the desktop app opens":
Android↔Android, Android↔desktop, desktop↔desktop — one protocol, encryption on. Native
targets join the matrix as their `actual`s land; Phase 23 owns the matrix definition.

iOS / Kotlin-Native is **in scope** as of 2026-09-03. That is what `D1 = B` buys and pays for:
`java.*`/`javax.*` are unavailable in shared code, so the JCA crypto layer, the blocking-socket
transport, and the JVM-only concurrency primitives are all rewritten rather than shared
(see [DECISIONS.md](docs/migration/DECISIONS.md) D1, and PHASE-05 for the cost inventory).

## Where the work actually is

| Document | What it is |
|---|---|
| [docs/migration/README.md](docs/migration/README.md) | Execution index: 25 phases (00–24), two hard gates, read order. |
| [docs/migration/CONVENTIONS.md](docs/migration/CONVENTIONS.md) | Mandatory rules R1–R11 for every executing agent. Non-negotiable. |
| [docs/migration/AUDIT.md](docs/migration/AUDIT.md) | Verified ground truth. **Supersedes every factual claim in this charter.** |
| [docs/migration/DECISIONS.md](docs/migration/DECISIONS.md) | Open human decisions D1–D9 and the blocking-map table. |
| `docs/migration/PHASE-00…24-*.md` | One self-contained, step-by-step phase per file. |

Each phase file states its own preconditions, steps, verification, and rollback, and is written
to be executed by an agent with limited context. Do the phases in numeric order.

## Principles that still hold (do not violate)

These carried over from the original plan and remain load-bearing:

1. **Do not rewrite the working Android implementation.** The migration is incremental; Android
   must build and pass its tests after every phase (CONVENTIONS.md R3).
2. **Do not move code to `commonMain` just to make it compile** (R2). Under **D1 = B** there is
   no `jvmAndAndroidMain` tier to fall back to, so the escalation is: leave the code where it
   is, or add an `expect`/`actual` seam with a real `actual` per target. Never delete an API,
   weaken encryption, or stub a function to force a `commonMain` compile.
3. **Do not disable or weaken encryption** to make the desktop port easier (R8, D5).
4. **Do not duplicate the protocol or the transfer engine** for desktop. One shared implementation.
5. **Preserve the public API** (`Flash.create(...)`, `engine.transfers.*`, …) where reasonable.
6. **Adaptive UI by window size, not `if (isWindows)`** — and the adaptive layout already exists
   (AUDIT CORRECTION 9).
7. **Do not publish** KMP artifacts until the full interop matrix passes (Phase 23).

## What the audit STRUCK from the old plan

The old plan was written before anyone read the source. [AUDIT.md](docs/migration/AUDIT.md)
corrected it by direct inspection on 2026-08-28. These specific claims are **false** and must
not be acted on:

- **"Rename modules to `flash-*`" (old §5).** Not required, not recommended — it would churn
  every Gradle file, every import, and the JitPack artifact IDs set up in PR #1. See decision
  **D2**; the default is to keep the existing `core:*` names.
- **"The UI uses custom Compose components rather than generic Material 3" (old §14).** False.
  Material 3 is load-bearing (62 references across 28 UI files). AUDIT CORRECTION 5.
- **The old §16 Android-coupling list is mostly false positives.** `BackHandler`, `LocalDensity`,
  `LocalViewConfiguration`, `LocalHapticFeedback` are already multiplatform; `AndroidView`,
  `hiltViewModel`, `stringResource`, `androidx.navigation`, `MediaStore` are **absent** from
  `ui/`. AUDIT CORRECTIONS 7–9. The genuine UI shims are narrow — `Toast`, one file picker, one
  permission check (decision **D7**) — plus dynamic-color (decision **D4**).
- **"Do not introduce Material 3" (old §23).** Incoherent; M3 is already there. Struck.
- **"The next task is a source-level audit; do not move files yet" (old §27).** The audit is
  done — it is [AUDIT.md](docs/migration/AUDIT.md). Execution has begun.

The old plan also **understated the single hardest fact**: under **AGP 9**, `com.android.library`
is incompatible with the Kotlin-Multiplatform plugin in one module; the replacement
`com.android.kotlin.multiplatform.library` has **no build variants** (no `buildTypes`, no
`singleVariant`, no `BuildConfig`, host tests are opt-in). That — not "extracting models" — is
the real difficulty, and it is why the conversion is piloted on a single module first in
[PHASE-06](docs/migration/PHASE-06-kmp-pilot.md). AUDIT CORRECTION 1.

## Definition of done

Condensed from old §24: Android still builds and works; the Compose UI is shared with Windows
where practical; discovery and file transfer work in all four directions; one protocol
everywhere; encryption stays on; transfer progress, cancellation, and resume are preserved; and
the UI adapts to desktop window sizes without a duplicate UI. The gate for "shared UI is real"
is [PHASE-16](docs/migration/PHASE-16-desktop-headless-interop.md) (headless cross-platform
transfer) before any UI work merges, and [PHASE-23](docs/migration/PHASE-23-interop-matrix.md)
(the full 4-way matrix) before anything is published.

