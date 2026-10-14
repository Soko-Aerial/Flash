# V-verifier explain-back (contract section 2, adapted to a read-only verifier)

**Date:** 2026-10-14. **Checkout:** `dev` at `f949182c` (ancestor check: `b717ea50` is contained).

## What this assignment must achieve
Three independent verdicts, with evidence I produced myself and not copied from the implementer's report:
1. Run the protocol section 6 integration gate unchanged and say, per task, whether it was *executed* (not UP-TO-DATE / FROM-CACHE), the pass/fail counts, and for every failure whether it predates Plan B (checked with `git log` / `git blame` and the known-issue docs, never guessed).
2. Phase B1 (directory sync, ORGT-U01..U19): lazy-pattern scan, spot-check of claims against code, mutation checks run by me on a scratch copy.
3. Phase B2 (ORG tier at the application gate, ORGT-U20..U32, section 6 call-site table): open every `isTrustedPeer` site and judge it.

I change no product code. The only files I write are under `logs/streams/V-verifier/`. Mutations are run in a scratch copy of the repository (`/home/user/verify/scratch`), never in the checkout that carries the gate result.

## Files I read first (similar-class rule does not apply: I add no code)
AGENTS.md, HUB-PARALLEL-AGENTS-PROTOCOL.md, HUB-QUALITY-CONTRACT.md, HUB-PLANS-PROMPTS.md (verifier prompt), HUB-OFFLINE-ORG-TRUST-PLAN.md (sections 3, 4 B1/B2, 6, 7 and the implementer's B1/B2 reports), the top of logs/handoff.md.

## Five ways this verification is most likely to go wrong
1. **Cached results counted as evidence.** Mitigation: read `Task ... UP-TO-DATE / FROM-CACHE` lines and count tests from the XML result files; rerun with `--rerun-tasks` when needed.
2. **Trusting the implementer's table.** Mitigation: grep production code for every policy method and every `isTrustedPeer`; compare with the claims line by line.
3. **Sandbox differences blamed on the code (or the reverse).** The Android SDK is installed here; desktop engine tests hung for 37 minutes in this sandbox earlier, so unrelated hangs are reported as environment, with the evidence.
4. **A mutation that leaks into the checkout.** Mitigation: scratch copy only; `git diff` clean check on the gate checkout before and after.
5. **Guessing provenance of failures.** Mitigation: `git log -S` / blame for every failing test, plus the doc entry (ERROR-106 etc.).

## Order of work
Gate in the background -> static review (this is cheap and independent of the build) -> mutation checks after the gate has finished (one Gradle at a time) -> report.
