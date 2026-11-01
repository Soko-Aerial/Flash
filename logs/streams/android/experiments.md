# Stream G (Android) — Experiment Log

> **Read this banner first.** Nothing in this file was measured. These four entries are *design expectations* written
> from the code diff, and the first version of them asserted currents ("15–35 mA", "12–20 mA"), durations ("< 35 ms") and
> duty cycles ("< 5 %", "> 90 % deep sleep") that no device produced. Those numbers are **withdrawn**; each entry now says
> what the change should do and names the device test in `docs/testing/TEST-BACKLOG.md` that has to establish it. Nothing
> here may be quoted as a measurement until that test runs (§23, and the Wave 2 precedent `EXP-095`..`098`).

---

## EXP-105 — Standby wake lock lease duty cycle (design expectation, NOT measured)

### Objective
Establish that `DiscoveryEngineHolder` releases the CPU partial wake lock when transfers, calls and PTT sessions are
inactive, so the Application Processor can power-collapse in standby.

### Code under test
`DiscoveryEngineHolder.kt` — `updateCpuWakeLockState()`, `acquireWakeLockLease()`, `WAKE_LOCK_TIMEOUT_MS`,
`WAKE_LOCK_REFRESH_INTERVAL_MS`

### What changed
Baseline held `wakeLock.acquire()` from engine start to engine teardown with no timeout. Now the lock is held only while
a transfer is `Transferring`/`Verifying`, a call is live, a PTT floor is open, or a bounded lease is outstanding; every
lease carries a 10-minute safety timeout refreshed by a 5-minute watchdog while work remains.

### Device test that would measure it
`PERF-G01`: two-hour screen-off idle standby on an Infinix X6882B and a Samsung device, `dumpsys power` wake-lock totals
and `dumpsys batterystats` drain, compared against a build with the change reverted on the same day.

### Conclusion
Structural only: a wake lock with a bounded lease cannot outlive the work that justified it. The duty cycle it produces
is unknown until `PERF-G01`.

---

## EXP-106 — Headless keepalive worker fast path (design expectation, NOT measured)

### Objective
Establish the execution time of `FlashKeepaliveWorker.doWork()` on an idle device with an empty outbox.

### Code under test
`FlashKeepaliveWorker.kt` — the pre-boot `SELECT COUNT(*)` checks

### What changed
Baseline always started the headless engine (crypto, TLS, socket bind, NSD registration, AutoConnector) and waited for a
session before teardown. Now two scalar counts are read first; when both are zero the worker returns `Result.success()`
without starting the engine. WorkManager's 15-minute period gives four runs an hour, so the old path's ceiling was 4 × 45 s
of awake time per hour — arithmetic, not a measurement — and the change removes it whenever there is nothing to flush.

### Device test that would measure it
`PERF-G02`: `adb shell dumpsys jobscheduler` / WorkManager job timing for both paths, plus a `batterystats` diff over six
hours idle. Baseline figures (45 s awake per run, `~150 mA` peak) are the *old code's own wait*, not an observed draw.

### Conclusion
A worker with nothing to do no longer pays for an engine start. How many milliseconds the fast path costs is `PERF-G02`'s
answer.

---

## EXP-107 — Transsion Tier 3 audio gating when the screen is on (design expectation, NOT measured)

### Objective
Establish that `TranssionHiberKeepAlive` keeps the silent `AudioTrack` paused while the screen is interactive.

### Code under test
`TranssionHiberKeepAlive.kt` — `startAudio()`, `onScreenStateChanged()`, the `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF` receiver

### What changed
Tier 3 used to play the silent PCM loop for as long as the session was active. Now `play()` happens only when
`PowerManager.isInteractive` is false: `startAudio()` reads it before playing, `onScreenStateChanged(true)` pauses, and
`onScreenStateChanged(false)` resumes if the session is still active and no permanent-focus-loss cooldown is running.
The Hiber freezer that the tier defends against only freezes with the screen off (EXP / HIB notes), so nothing is lost.

### Device test that would measure it
`PERF-G04` on a Transsion handset: `dumpsys audio` for the owning client and a `batterystats` drain comparison with the
screen on and the app foregrounded for 30 minutes, gating on versus off. The audio-path current it saves is unknown.

### Conclusion
The audio path is idle whenever it cannot help. Any mAh figure quoted for it is unmeasured.

---

## EXP-108 — Subnet sweep deferral during cold start (design expectation, NOT measured)

### Objective
Establish that a cold start no longer floods the local /24 with TCP connect probes while SQLite and the first frame are
being prepared.

### Code under test
`DiscoveryEngineHolder.kt` — `INITIAL_SWEEP_START_DELAY_MS = 30_000L`

### What changed
`sweep.start()` used to run at engine start, up to 254 probes against port 45822 alongside database open and Compose's
first frame. The automatic fallback sweep now starts 30 s later; mDNS/NSD discovery, cached routes and an explicit
"Scan network" are unaffected and still immediate.

### Device test that would measure it
`PERF-G07`: `Perfetto`/`systrace` start-up trace, cold start with a cleared cache, comparing probe-start timestamps and
first-frame time against the reverted build.

### Conclusion
Two sources of contention no longer overlap by construction. The launch-time saving is `PERF-G07`'s to state.
