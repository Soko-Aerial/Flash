# Flash Multiplatform Migration — Execution Index

This directory replaces the single-file plan at `FLASH_MULTIPLATFORM_MIGRATION_PLAN.md`.
That file is now a short charter. **All executable work lives here.**

## Read these first, in order

| # | File | Purpose |
|---|---|---|
| 1 | [CONVENTIONS.md](CONVENTIONS.md) | Rules every executing agent MUST follow. Non-negotiable. |
| 2 | [AUDIT.md](AUDIT.md) | Verified ground truth about the repo. Supersedes any claim in the old plan. |
| 3 | [DECISIONS.md](DECISIONS.md) | Open decisions D1–D9. **Only D1 (and D2 if renaming) gate Phase 06**; the rest gate later phases — see the blocking-map table at the top of DECISIONS.md. |

## Phases

Execute in numeric order. Do not skip. Do not reorder. Each phase file is
self-contained and states its own preconditions.

| Phase | File | Blocked by | Risk |
|---|---|---|---|
| 00 | [PHASE-00-baseline.md](PHASE-00-baseline.md) | — | none |
| 01 | [PHASE-01-hygiene.md](PHASE-01-hygiene.md) | 00 | low |
| 02 | [PHASE-02-delete-wslegacy.md](PHASE-02-delete-wslegacy.md) | 00 | low |
| 03 | [PHASE-03-logging.md](PHASE-03-logging.md) | 00 | low |
| 04 | [PHASE-04-time-uuid-locale.md](PHASE-04-time-uuid-locale.md) | 03 | low |
| 05 | [PHASE-05-concurrency.md](PHASE-05-concurrency.md) | 04 | medium |
| 06 | [PHASE-06-kmp-pilot.md](PHASE-06-kmp-pilot.md) | 05 + **D1** (+D2 if renaming) | **highest** |
| 07 | [PHASE-07-security-kmp.md](PHASE-07-security-kmp.md) | 06 | medium |
| 08 | [PHASE-08-discovery-kmp.md](PHASE-08-discovery-kmp.md) | 06 | medium |
| 09 | [PHASE-09-persistence-kmp.md](PHASE-09-persistence-kmp.md) | 06 + **D5** | high |
| 10 | [PHASE-10-network-kmp.md](PHASE-10-network-kmp.md) | 07, 08 | high |
| 11 | [PHASE-11-repositories-kmp.md](PHASE-11-repositories-kmp.md) | 07, 08, 09, 10 | high |
| 12 | [PHASE-12-engine-kmp.md](PHASE-12-engine-kmp.md) | 07,08,09,10,11 | high |
| 13 | [PHASE-13-desktop-fileio.md](PHASE-13-desktop-fileio.md) | 12 | medium |
| 14 | [PHASE-14-desktop-discovery.md](PHASE-14-desktop-discovery.md) | 12 + **D6** | high |
| 15 | [PHASE-15-desktop-transport.md](PHASE-15-desktop-transport.md) | 13,14 | high |
| 16 | [PHASE-16-desktop-headless-interop.md](PHASE-16-desktop-headless-interop.md) | 15 | **gate** |
| 17 | [PHASE-17-ui-resources.md](PHASE-17-ui-resources.md) | 06 | low |
| 18 | [PHASE-18-ui-theme-kmp.md](PHASE-18-ui-theme-kmp.md) | 17 | medium |
| 19 | [PHASE-19-ui-platform-shims.md](PHASE-19-ui-platform-shims.md) | 18 + **D7** | medium |
| 20 | [PHASE-20-ui-chat-kmp.md](PHASE-20-ui-chat-kmp.md) | 19 | high |
| 21 | [PHASE-21-desktop-app-shell.md](PHASE-21-desktop-app-shell.md) | 16,20 | medium |
| 22 | [PHASE-22-adaptive-desktop-screens.md](PHASE-22-adaptive-desktop-screens.md) | 21 + **D8** | medium |
| 23 | [PHASE-23-interop-matrix.md](PHASE-23-interop-matrix.md) | 22 | **gate** |
| 24 | [PHASE-24-publishing.md](PHASE-24-publishing.md) | 23 | medium |

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

## Logging

Every phase appends one entry to `logs/migration.md` using
[TEMPLATE-phase-log.md](TEMPLATE-phase-log.md). No exceptions. A phase with no log
entry is treated as not done.
