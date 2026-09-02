# Phase 00 — Baseline

**Blocked by:** nothing. This is the first phase.
**Risk:** none. This phase changes no source code.
**Decisions needed:** none.

## Why this phase exists

Once refactoring starts you cannot go back and measure what the working
implementation did. Everything after this phase is compared against the numbers
recorded here. A migration without a baseline cannot prove it did not regress.

## Preconditions

- On branch `dev`.
- `git status` shows no uncommitted changes you care about losing. If it does, commit
  or stash them first — this phase does not modify files, but later ones do.
- An Android device or emulator (API 24+) available. Two are needed for step 5.

## Steps

### Step 1 — Record the exact toolchain

Run each and paste the output into the log:

```bash
./gradlew --version
```

```bash
java -version
```

```bash
git rev-parse HEAD
```

### Step 2 — Clean build

```bash
./gradlew clean build --no-configuration-cache
```

Record wall-clock duration. If this fails, **stop**. Do not start the migration on a
broken build. Report the failure and end the phase.

### Step 3 — Full unit test run

```bash
./gradlew testDebugUnitTest --no-configuration-cache
```

Record:
- total test count
- pass/fail/skip counts
- any tests that were already failing before you started

That last item matters. If a test is red at baseline, a later phase must not be
blamed for it.

### Step 4 — Record per-module test counts

```bash
./gradlew testDebugUnitTest --no-configuration-cache -i 2>&1 | grep -E "^:.*:testDebugUnitTest" 
```

If that produces nothing useful, read the HTML reports under
`*/build/reports/tests/testDebugUnitTest/index.html` and record the per-module totals
by hand. The number to capture is: **how many tests exist in each `core/*` and `ui/*`
module today.**

### Step 5 — Android↔Android functional baseline

Install the debug app on two devices on the same LAN and record, as plain prose in
the log:

1. **Discovery** — does device A see device B? How long until the peer appears?
   Does it work over a Wi-Fi hotspot as well as a normal AP? (See the
   `nsd-hotspot-discovery` notes — this path has known asymmetry.)
2. **Pairing** — complete a pairing. Record the numeric comparison code flow working.
3. **Small file transfer** — send a file under 1 MB. Record success and elapsed time.
4. **Large file transfer** — send a file over 100 MB. Record:
   - elapsed time
   - computed throughput in MB/s
   - whether progress updates appeared smoothly
5. **Cancellation** — cancel mid-transfer. Record what both sides show afterwards.
6. **Interrupted transfer / resume** — kill Wi-Fi mid-transfer, restore it. Record
   whether the transfer resumes and whether it resumes from the correct offset.
7. **Inbound accept gate** — confirm an incoming transfer waits for accept before
   data flows. (See the `transfer-offer-gate` notes: sender-starts-paused plus the
   load-bearing `acceptSession`→`RESUME` ordering. This is easy to break later.)
8. **Messaging** — send a text message, a reaction, and a voice message.

### Step 6 — Record the throughput number prominently

Put the large-file MB/s figure on its own line in the log, formatted exactly:

```
BASELINE_THROUGHPUT_MBPS = <number>
```

Old plan §20 requires that the desktop implementation introduce no artificial
throttling. Phase 23 compares against this literal string.

## Verification

There is nothing to verify beyond having recorded the data — this phase writes no
code. The phase is done when the log entry contains all six steps' output.

## Do NOT

- Do not fix any failing test you find. Record it and move on.
- Do not upgrade any dependency.
- Do not commit source changes. If you must commit, commit only `logs/migration.md`.

## Completion checklist

- [ ] Toolchain versions recorded
- [ ] `clean build` result recorded (and passing)
- [ ] Test totals recorded, including pre-existing failures
- [ ] Per-module test counts recorded
- [ ] All 8 functional checks in step 5 recorded
- [ ] `BASELINE_THROUGHPUT_MBPS = <n>` line present in the log
- [ ] Log entry appended to `logs/migration.md`

## If you cannot do step 5

Two physical devices may not be available. If so, record explicitly:

```
Step 5 NOT PERFORMED — reason: <reason>
```

This is acceptable but it weakens Phase 23, which then has no behavioural baseline to
compare against. Flag it to the human rather than silently skipping it.
