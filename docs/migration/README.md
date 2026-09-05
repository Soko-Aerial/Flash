# Flash Multiplatform Migration — Execution Index

This directory replaces the single-file plan at `FLASH_MULTIPLATFORM_MIGRATION_PLAN.md`.
That file is now a short charter. **All executable work lives here.**

## Read these first, in order

| # | File | Purpose |
|---|---|---|
| 1 | [CONVENTIONS.md](CONVENTIONS.md) | Rules every executing agent MUST follow. Non-negotiable. |
| 2 | [AUDIT.md](AUDIT.md) | Verified ground truth about the repo. Supersedes any claim in the old plan. |
| 3 | [DECISIONS.md](DECISIONS.md) | Decisions D1–**D11**. **All eleven are answered** — D1–D9 on 2026-08-31, then **D10 = Option A** and the new **D11 = Option B** on 2026-09-05, together with an explicit **R8 authorisation** for 13B-3's `ChunkFrame` rewrite (byte-identical output required). Ignore any older framing about D1 gating Phase 06 or D10 being `_pending_`. **No phase is blocked on a decision any more**; what remains blocked is blocked on a predecessor phase. Two narrower human inputs are still open and are *not* decisions: D5=C's three implementation sub-answers (09B-2) and the settings-tier ABI option (09B-3). |

## Phases

Execute in numeric order. Do not skip. Do not reorder. Each phase file is
self-contained and states its own preconditions.

The **Status** column below was rebuilt on 2026-09-05 by reading every `## Phase` entry in
`logs/migration.md` and confirming each cited commit exists with `git log -1 <sha>`. It used to be
a "Blocked by" column that recorded *original* preconditions, which meant a done phase whose
precondition happened to be a decision still read as blocked — Phase 14 was misreported that way.
Status is authoritative; each phase file's own preconditions section holds the dependency detail.

| Phase | File | Status (verified 2026-09-05) | Risk |
|---|---|---|---|
| 00 | [PHASE-00-baseline.md](PHASE-00-baseline.md) | **DONE** (`8506036`) | none |
| 01 | [PHASE-01-hygiene.md](PHASE-01-hygiene.md) | **DONE** (`c0c94e2`) | low |
| 02 | [PHASE-02-delete-wslegacy.md](PHASE-02-delete-wslegacy.md) | **DONE** (`275c704`) | low |
| 03 | [PHASE-03-logging.md](PHASE-03-logging.md) | **DONE** (`da4fba6`) | low |
| 04 | [PHASE-04-time-uuid-locale.md](PHASE-04-time-uuid-locale.md) | **DONE** (`254c474`, `e85b3d5`) | low |
| 05 | [PHASE-05-concurrency.md](PHASE-05-concurrency.md) | **DONE** (`2339cb8`) | medium |
| 06 | [PHASE-06-kmp-pilot.md](PHASE-06-kmp-pilot.md) | **DONE** (`83232f4`) — ran under D1=B and D2=A | was **highest** |
| 07 | [PHASE-07-security-kmp.md](PHASE-07-security-kmp.md) | **DONE** (`fe5f9be`) | medium |
| 08 | [PHASE-08-discovery-kmp.md](PHASE-08-discovery-kmp.md) | **DONE** (`b879017`) | medium |
| 09 | ~~[PHASE-09-persistence-kmp.md](PHASE-09-persistence-kmp.md)~~ | **SUPERSEDED** by 09B — its log entry is docs-only, no code | — |
| 09B | [PHASE-09B-persistence-room-kmp.md](PHASE-09B-persistence-room-kmp.md) | **09B-1 DONE** (`328c553`, `24435bd`, `8b5fa5a`) — db tier only. **09B-2 BLOCKED** on D5=C's three unanswered sub-decisions (which encrypted desktop driver; is a commercial licence acceptable; SQLCipher file-format parity). **09B-3 BLOCKED** on the settings-tier ABI option (a) or (b). | 09B-1 done, 09B-2/3 high |
| 10 | [PHASE-10-network-kmp.md](PHASE-10-network-kmp.md) | **DONE** (`428154d`) | high |
| 11 | [PHASE-11-repositories-kmp.md](PHASE-11-repositories-kmp.md) | **DONE** (`f96797e`) — `:core:transfer` + `:core:messaging` | high |
| 12 | [PHASE-12-engine-kmp.md](PHASE-12-engine-kmp.md) | **DONE** (`4ac401b`) | high |
| 13 | ~~[PHASE-13-desktop-fileio.md](PHASE-13-desktop-fileio.md)~~ | **SUPERSEDED** by 13B — its log entry is docs-only, no code | — |
| 13B | [PHASE-13B-desktop-fileio.md](PHASE-13B-desktop-fileio.md) | **13B-1 DONE** (`fafd450`). **13B-2 DONE** (`732e7b5`, 2026-09-05) — D10 = Option A enacted as **Okio 3.4.0**; the four `java.io` seams are now `commonMain`, and `RandomAccessSinkHandle`'s supertype changed `Closeable` → `AutoCloseable` (Phase 24 release note). **13B-3a DONE** (`5e4e9a5`, 2026-09-05) — `Sha256`/`IncrementalSha256` are `commonMain` over Okio's `HashingSink`; no module edge, no new dependency, no ABI break. It went first because `ChunkFrame` calls four `Sha256` members, so the phase file's framing-then-hashing order was impossible; §13B-3 now carries the correction. **13B-3b NEXT** — the R8-authorised `ChunkFrame` rewrite, **with byte-identical output as the hard acceptance criterion**; it does not ship if any byte differs. Then 13B-3c resume, 13B-3d concurrency, 13B-3e pipelines. Note §13B-3's "big-endian" claim is **wrong** — the wire format is little-endian. | 13B-1 low, 13B-2/3a medium, 13B-3b–e high |
| 14 | [PHASE-14-desktop-discovery.md](PHASE-14-desktop-discovery.md) | **DONE** (`75d86ef`) under D6=A (JmDNS). Was previously listed here as "12 + **D6**", which read as blocked; D6 was answered 2026-08-31 and the phase shipped. **Caveat: D6's mandated throwaway spike was never run**, and no real multicast was ever exercised — only a human with two machines on one LAN can discharge that. | high |
| 15 | [PHASE-15-desktop-transport.md](PHASE-15-desktop-transport.md) | **WAITING ON 13B-3b–e** — 13B-2 (`732e7b5`) and 13B-3a (`5e4e9a5`) are done, so file I/O and hashing are both common; the remaining pins are framing, resume, atomics and the pipelines. No longer blocked on a decision (D10 = A answered 2026-09-05). 14 is satisfied. | high |
| 16 | [PHASE-16-desktop-headless-interop.md](PHASE-16-desktop-headless-interop.md) | **WAITING ON 15** — no longer blocked on a decision | **gate** |
| 17 | [PHASE-17-ui-resources.md](PHASE-17-ui-resources.md) | **DONE** (`a8d9d0d`, `23267ed`) | low |
| 18 | [PHASE-18-ui-theme-kmp.md](PHASE-18-ui-theme-kmp.md) | **DONE** (`96e8799`) — read its STATUS box before reusing any of it; 14 of its steps were wrong | medium |
| 19 | [PHASE-19-ui-platform-shims.md](PHASE-19-ui-platform-shims.md) | **DONE** (`94a60a4`) — read its STATUS box before reusing any of it; 12 of its statements were wrong, D7b was overridden on evidence (no FileKit), and there are 7 shims not 8 | medium |
| 20 | [PHASE-20-ui-chat-kmp.md](PHASE-20-ui-chat-kmp.md) | **DONE** (`c5abd5d`) — read its STATUS box before reusing any of it; Steps 1 and 2 must not be executed (Step 2's "CMP requires `jvm("desktop")`" is false and would break R5 across the UI track), Step 5's build file is unbuildable, and the module ended up **100% common** | high |
| 21 | [PHASE-21-desktop-app-shell.md](PHASE-21-desktop-app-shell.md) | **WAITING ON 16** — no longer blocked on a decision. 20 is satisfied. **Its 2026-08-31 log entry claims a `:desktop` module that has never existed** — see the CORRECTION appended to it in `logs/migration.md`. | medium |
| 22 | [PHASE-22-adaptive-desktop-screens.md](PHASE-22-adaptive-desktop-screens.md) | **WAITING ON 21.** D8=A was answered 2026-08-31, so no decision gates it. **Its 2026-08-31 log entry is also false** — same CORRECTION. | medium |
| 23 | [PHASE-23-interop-matrix.md](PHASE-23-interop-matrix.md) | **WAITING ON 22** | **gate** |
| 24 | [PHASE-24-publishing.md](PHASE-24-publishing.md) | **WAITING ON 23.** Also owes `sample/consumer-desktop` per D9=A. | medium |
| TBD | *(no file yet)* — calling stack | **AUTHORISED, NOT WRITTEN.** **D11 = Option B** (2026-09-05): `:core:calling` + `:ui:callui` **are** in desktop scope, and the phase **must open with a WebRTC-for-desktop-JVM research step and report before proposing any conversion**. Must not be inserted ahead of 13B-2/15/16 — the Phase 16 interop gate outranks calling. | high |

### Two log entries near the top of `logs/migration.md` are false — do not trust them

`logs/migration.md` opens with `## PHASE-21` and `## PHASE-22` entries dated 2026-08-31, both citing
commit `ecb0c63` and both reporting PASS builds. `ecb0c63` is a **docs-only** commit (33 files, all
under `docs/migration/` and `logs/`), `desktop/` has never existed on any branch
(`git log --all -- desktop` is empty), and `settings.gradle.kts` has no `:desktop` include. One of the
cited PASS tasks, `:ui:chat:compileKotlinDesktop`, cannot exist at all under CONVENTIONS R5. A
**CORRECTION block was appended to each entry** on 2026-08-31 (`0250a51`); the false text is preserved
above it because the log is append-only (R9). If you read that log top-down, read the corrections too.

## Ordering correction (2026-08-30) — read before touching phases 07–12

The core-conversion phases were **reordered** on 2026-08-30 after the module
`build.gradle.kts` dependency edges were read directly (not inferred). The earlier
draft had `07 = messaging+transfer`, `09 = network` before `10 = discovery`, and
`11 = persistence` — all of which violate the build graph:

- `core:messaging` → `implementation(core:security, core:network, core:persistence)`
- `core:transfer`  → `implementation(core:security, core:network, core:discovery)`
- `core:network`   → `implementation(core:security, core:discovery)`
- `core:persistence`, `core:security`, `core:discovery` → only `api(core:common)`
- `core:engine`    → `api(` all seven `)`

A module cannot have its shared code (`commonMain`/`jvmAndAndroidMain`/`jvmMain`)
compile for the `jvm()` target until every module it depends on is already KMP — so
conversion **must** run bottom-up in dependency order. The corrected order is a
topological sort: leaves that need only `core:common` first (security, discovery,
persistence), then `network`, then the two repositories (transfer + messaging), then
`engine` last. `core:transfer` — which the old plan never gave a phase file — is
converted in Phase 11 alongside `core:messaging` (both sit at the same level and do
**not** depend on each other). Do not "restore" the old numeric order; it is wrong.

## Two hard gates

- **Phase 16** — headless desktop↔Android transfer must work before *any* UI work is
  merged. If the protocol cannot cross platforms, shared UI is worthless.
- **Phase 23** — the full 4-way interop matrix. Nothing is published before this passes.

Phases 17–20 (UI) are deliberately parallel-capable with 07–16 (core + desktop),
because they
touch disjoint modules. Phase 17 only needs Phase 06. If you have one agent, do them
in numeric order anyway.

## Modules with no phase file (corrected 2026-09-05) — read before planning any further UI phase

The two modules the plan never covers are **`:core:calling` and `:ui:callui`** — the calling stack.
An earlier version of this section named `:ui:callui` and `:sample:consumer-granular`; the second half
of that was **wrong** and is corrected here.

**Their scope is now decided: `D11 = Option B` (2026-09-05) — they ARE in desktop scope, and the phase
that handles them must open with a WebRTC-for-desktop-JVM research step and report its findings before
proposing any conversion.** The phase file does not exist yet, and it must not be inserted ahead of
13B-3b–e/15/16: D10 = A has unblocked the critical path (13B-2 landed as `732e7b5`, 13B-3a as
`5e4e9a5`) and the Phase 16 interop gate outranks calling.

- **`:core:calling`** — grepping every file in `docs/migration/` for `core:calling` returns hits in
  `CONVENTIONS.md` and this README only. **No phase file mentions it at all.** It is still
  `com.android.library`, and it is the WebRTC module, so it is the substantive half of the problem —
  and the reason D11 mandates research before conversion.
- **`:ui:callui`** — depends on `:ui:theme` (`ui/callui/build.gradle.kts:62`) and names
  `FlashIconSpec` (`FlashCallScreen.kt:486`), so it sits inside the blast radius of Phase
  17 (done), 18 (done) and 19 (done), yet no phase converts it or even compiles it as a gate. The
  verification runs for 09B-1, 17, 18 and 19 added `:ui:callui:compileDebugKotlin` by hand for
  exactly that reason — and as of Phase 18 it is compiling against a `:ui:theme` that is now
  multiplatform, so the gap is widening rather than holding still. D11's answer also directs that the
  compile gate be wired into the documented R3 command line as a cheap side-effect, so the gap is
  measured rather than assumed, whatever the research concludes.

**`:sample:consumer-granular` is not an open question.** `D9 = Option A` (answered 2026-08-31) names
it explicitly: keep `sample/consumer` **and** `sample/consumer-granular` Android-only as-is through
Phase 23, then add a pure-JVM `sample/consumer-desktop` in Phase 24 to validate the desktop artifact.
`PHASE-24-publishing.md:83` carries the same instruction. Any backlog that still lists this module as
"no plan — needs a human scope decision" (including the one in Phase 20's log entry) is repeating this
README's error, not reporting a real gap.

`:app` also has no conversion phase, and that is **by design** — it is the Android application, and
Phase 21 gives the desktop its own `:desktop` module rather than making `:app` multiplatform.

Do not treat the calling stack's absence as "already handled". Phase 17's log entry records it as an
open item; it is repeated here so the next planning pass sees it without reading 7,900 lines of log.

## Module conversion state (after Phase 20, 2026-09-05)

**KMP (12):** `:core:common` (06), `:core:security` (07), `:core:discovery` (08 + 14),
`:core:network` (10), `:core:transfer` + `:core:messaging` (11), `:core:engine` (12),
`:core:persistence` (09B-1, db tier only), `:ui:theme` (17 shell + 18 proper),
`:ui:platform-shims` — created by Phase 19 and the first module in this repo that was **born
KMP**, never having had the `com.android.library` plugin — and **`:ui:chat`** (20).

**Still `com.android.library` / `com.android.application` (5):** `:core:calling`, `:ui:callui`,
`:app`, `:sample:consumer`, `:sample:consumer-granular`. Of these, three are **deliberate**: `:app`
stays the Android application (Phase 21 gives desktop its own module) and both samples stay Android-only
through Phase 23 per D9=A. The two that are **unplanned** are `:core:calling` and `:ui:callui`.

`:ui:chat` is the only module that is **100% common**: all 45 production files in `commonMain`, all 31
test files in `commonTest`, and no `androidMain`, `jvmMain` or platform-specific source of any kind.
That is Phase 19's doing — the seven shims it extracted were the only reason the module ever touched
`android.*`.

**Every unblocked phase in this plan is complete, and as of 2026-09-05 the plan is no longer
decision-blocked at all.** D10 = Option A and D11 = Option B were answered, and the R8 exception for
13B-3's `ChunkFrame` rewrite was granted with byte-identical output as the hard acceptance criterion.
**The critical path is open again: ~~13B-2~~ (done, `732e7b5`) → ~~13B-3a~~ (done, `5e4e9a5`) →
13B-3b (the R8 `ChunkFrame` rewrite) → 13B-3c/d/e → 15 → 16 (gate) → 21 → 22 → 23 (gate) → 24.**

What is still outstanding is narrower than a decision:

- **09B-2** — D5=C's three implementation sub-answers: which encrypted desktop driver, is a commercial
  licence acceptable, SQLCipher file-format parity.
- **09B-3** — the settings-tier ABI option (a) or (b).
- **D6's throwaway spike** — mandated by D6=A and never run; Phase 14 shipped without it and no real
  multicast has ever been exercised. Needs a human with two machines on one LAN.
- **Five desktop library decisions** with no owner: AAC decode (the one gap a user would notice),
  video-frame extraction, EXIF rotation, reduce-motion detection, sound output.
- **A Kotlin/Native target**, recommended by CONVENTIONS R6.1 since Phase 07 and still unwritten. It
  would turn R6 from a four-times-defective grep into a compiler error, and it is a **precondition** of
  it that the eight allowlisted `.format(` calls be fixed with rounding tests first. D10 = A is what
  makes this reachable for `:core:transfer` at all.
- **Phase 24's release notes** owe six ABI breaks and three new artifacts.

Phase 20's log entry also lists `:sample:consumer-granular` as an open scope question — **that item is
void; D9=A answered it**, and `:core:calling`/`:ui:callui` are now settled by D11.

## Logging

Every phase appends one entry to `logs/migration.md` using
[TEMPLATE-phase-log.md](TEMPLATE-phase-log.md). No exceptions. A phase with no log
entry is treated as not done. **The converse is not true** — an entry is not proof of work: the
`PHASE-21` and `PHASE-22` entries at the top of that file are fabricated and carry appended
corrections. Verify a cited commit with `git log -1 <sha> --stat` before trusting an entry.
