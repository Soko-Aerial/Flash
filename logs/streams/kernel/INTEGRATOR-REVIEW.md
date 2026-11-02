# Integrator review — Kernel (K)

## Gate follow-up — 2026-11-02

The first expanded gate 1 on `bec221d2` failed one messaging case (ERROR-957) and one server relay case (ERROR-958)
under full load; both pass alone on the unchanged tree. The second, **complete forced gate 1 then PASSED on the same
commit**: `BUILD SUCCESSFUL in 12m 40s`, 259/259 tasks executed, exit 0, **3 597 tests, 0 failures, 0 errors, 2
documented POSIX-only Windows skips**. Per task: `:core:common` 19, `:core:security` 174 + 2 skips,
`:core:persistence` 85, `:core:messaging` 382, `:core:transfer` 141, `:core:swarm` 151, `:core:network` 339,
`:core:discovery` 106, `:core:enterprise` 276, `:core:hub-protocol` 126, `:ui:chat` 730, `:ui:theme` 58,
`:ui:callui` 103, `:server:test` 664, `:desktop:jvmTest` 243. Neither flake reproduced, so ERROR-957/958 remain OPEN
as intermittent with no root cause; no code, assertion, timeout or suite was changed to obtain the pass.
**Gate 2 then PASSED on the same commit**: `BUILD SUCCESSFUL in 10m 47s`, 218/218 tasks, exit 0, **2 110 tests,
0 failures, 0 skips** (`:core:messaging` 688, `:core:security` 265, `:core:enterprise` 165, `:core:common` 144,
`:app:testDebugUnitTest` 90, `:core:persistence` 53, `:ui:chat` 705), so both expanded K gates are green and desktop E
may merge. This does not alter the reviewed kernel dispositions below.

## Merged-tree integration — 2026-11-01

Repaired tip `22a53994` committed and merged into `dev` with `--no-ff` as **`bec221d2`**; canonical records committed in `f9b1fba0`.
Both expanded gates have since passed on `bec221d2` (see above). Desktop E remains unmerged pending its own merge and the
repeated gates. PERF-K01..K05 remain TODO; no device, performance, push or deployment result.
The initial review below preserves its original uncommitted stopping point.

Reviewed 2026-11-01 against actual base `baf77753`, delivered tip `6d9c9227`, code, callers and tests. Review is complete;
repairs are targeted-tested but not yet committed/merged at this entry. No physical-device or performance claim.

## Dispositions

| Finding | Disposition |
|---|---|
| OPT-O20-01 | KEEP: INFO-default level gate and inline lambdas; eager arguments remain eager, no broad caller migration |
| OPT-O20-04 | KEEP, REPAIRED: flat 0..8 fields/direct append; stable header snapshot, ERROR-955 |
| OPT-O20-02 | KEEP, REPAIRED: ordinary writer formatting; synchronized dequeue/flush, ERROR-956 |
| OPT-O20-06 | KEEP AS API ONLY: stable gzip snapshot/source guard; no app caller |
| OPT-O15-01 | KEEP, REPAIRED: immutable copied SPKI cache key, ERROR-953 |
| OPT-O15-02 | PARTIAL: KeyFileVault shares RNG; Android PassphraseKeeper unchanged/outside scope |
| OPT-O15-03 | KEEP PROVIDER SELECTION; REJECT keyed Cipher ThreadLocal, ERROR-954 |
| OPT-O15-04 | PARTIAL: non-keyed security SHA reuse retained; keyed Mac reuse rejected; transfer helper unchanged |
| OPT-O25-03 | KEEP: NFKD/marks/lowercase, ASCII fast path, 512-entry LRU |
| OPT-O27-06 | KEEP POLICY: no tests excluded; expand gates to Android actuals |

Ten audit IDs; four confirmed defects repaired. Provider/factory isolation and gzip overwrite rejection refine those fixes.
Full rationale: ADR-310..314, ERROR-953..956, EXP-120..124 and PERF-K01..K05 in this stream's fragments.

## Verification

Forced repaired run through the machine lock:
`powershell.exe -NoProfile -File tools/flash-gradle.ps1 :core:common:jvmTest :core:common:testAndroidHostTest :core:security:jvmTest :core:security:testAndroidHostTest --continue --no-configuration-cache --no-daemon --rerun-tasks --no-build-cache --console=plain`

BUILD SUCCESSFUL in 3m 50s, 32/32 actionable tasks executed, exit 0. Common JVM 19; common Android host 144; security JVM
174 (two documented Windows/POSIX skips); security Android host 265 in 33 suites. Total 602, zero failures/errors. Only
source KDoc clarification followed this run. Mutable-SPKI and in-flight-flush regressions failed on delivered behavior
and pass repaired; HMAC/AES key-change tests cover correctness, not absence of retained keyed state or zeroization.

## Evidence limits and stopping point

No reproducible paired benchmark delivered; every ns/us/percentage/FPS/allocation/compression headline is withdrawn.
Flat calls avoid Pair/array creation, not all allocations; vararg callers remain. Ordinary queue records are raw/bounded;
explicit flush/crash/export may block. Retention is five ~1,000,000-byte files plus line overshoot. Gzip's standard CRC
trailer is not independent verification, and Android Share logs still creates text/plain. Public cache membership is not
trust. Fresh keyed operations do not promise JCA zeroization. No algorithm, nonce/AAD, key size, pin or wire change.

Next: finish/fold records, commit reviewed repairs, merge --no-ff, force both expanded gates on merged dev. Preserve
ERROR-951/952 history; ERROR-952's keyed-cache workaround is superseded. No push, deployment, phone access or Wave 5 work.
