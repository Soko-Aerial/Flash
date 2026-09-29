# PC0 runbook: baseline cost of idle sessions and reconnects

Part of `PRESENCE-CONNECTIONS-PLAN.md` (phase PC0). **Measure only; no product code changes.** Results go into
`logs/experiments.md` as one EXP entry per phone (template at the end).

## 1. What PC0 answers

1. What does each idle session cost a phone with the screen off? Compare a 0-peer run with runs of 1, 4 and 8 peers.
2. What happens above the session cap? A 19-peer run (explained in §2).
3. How long does it take after a Wi-Fi drop until every peer is back, and how many handshakes does that take?

These numbers decide how aggressive ECO must be (PC5) and are the baseline PC1 is judged against.

## 2. Facts that shape the measurement (verified in code, 2026-09-28)

- **Every host caps live sessions at 8** (`SessionHardeningPolicy.DEFAULT_MAX_CONCURRENT_SESSIONS`, enforced in
  `WsFlashNetwork.registerSession` and `JvmWsFlashNetwork`; nothing overrides it). A phone cannot hold 12 or 19
  sessions today. The 19-peer run therefore measures **today's behaviour with 19 devices around**: 8 held
  sessions plus 11 peers refused over and over ("session cap reached"). Both sides retry every 5 s, so this
  run measures the cost of that churn.
  **Superseded 2026-09-29 (ADR-057):** the ceiling is now **24** in every mode, so a 19-peer run holds 19 sessions and
  no longer produces refusals; it measures the cost of a 20-member group. Refusals now start above 24 peers. Any PC0
  number recorded before that date was taken against the old ceiling of 8.
- **The engine holds a partial wake lock for its whole lifetime** (`DiscoveryEngineHolder.acquirePowerLocks`),
  so the CPU never suspends while Flash runs. Counting "wake-ups" says nothing. Measure CPU time, Wi-Fi traffic
  and battery drain, always against the **0-peer run** of the same phone.
- The low-latency Wi-Fi lock is only active with the screen on (Android docs, checked 2026-09-24), so
  screen-off runs measure normal Wi-Fi power save.

## 3. Tools

| Tool | What it does |
|---|---|
| `./gradlew :core:engine:peerFarm --args="--count=N --minutes=M"` | Runs N headless Flash peers ("Farm 01…") in one JVM on the PC. Each has a persisted identity under `~/.flash-peer-farm/peer-NN`, the product's TLS, keepalive and discovery. Farm peers never dial each other, but dial every other Flash device every 5 s like the product. Prints a status line every `--report` s and `[degrade]`/`[recover]` lines, and writes a CSV of every session up/down to `~/.flash-peer-farm/runs/`. `--help` lists all options. |
| `tools/pc0/phone-baseline.ps1 -Action start` / `collect` | Resets batterystats and enlarges the logcat buffer; after the run, saves the raw dumps to `measurements/pc0/<label>/` (git-ignored) and prints a summary: drain, Flash's CPU and Wi-Fi lines, and the phone's dial / cap-refusal counts. |
| `tools/pc0/phone-baseline.ps1 -Action toggle-wifi -OffSeconds 10` | Wi-Fi off, wait, on. For the reconnect test. |

Gradle needs this repo's usual environment (JBR 21 and the `JAVA_TOOL_OPTIONS` AF_UNIX fix; see
`logs/handoff.md`). Pass `--no-daemon` if the output is piped, or Gradle may keep the pipe open after the run.

## 4. Setup (every session)

1. **Network:** the phone and the PC on the same Wi-Fi / subnet. Wired PC preferred (the PC's own link then
   doesn't compete with the phone for airtime). Record the router, band and channel if known.
2. **Nothing else speaking Flash:** close the desktop Flash app, and stop Flash on other phones. They would
   otherwise hold sessions with the farm and with the phone under test. **Observed 2026-09-28:** the Infinix
   X6882B was still advertised on the LAN while its port 45822 refused connections. A farm peer then dials it
   every 5 s. Check the farm log for `dial … failed` lines before starting and remove the cause.
   **Lead, not diagnosed:** on that phone the Flash process was alive with no service running, the system
   `NsdService` held a multicast lock, and batterystats showed Flash's multicast lock held 6 h 40 min while
   its engine wake lock was held for 62 ms over 13 h. An NSD registration (and multicast lock) that outlives the
   engine would explain the lingering advertisement. Worth its own investigation; for PC0, make sure the
   engine is really running (the notification is showing) before every run, R0 included.
3. **Windows firewall:** allow Java (the JBR 21 `java.exe`) inbound on the Private profile, and the network
   profile must be Private.
   **One network on the PC.** The farm binds mDNS and the multicast beacon to *one* interface it picks itself
   (`mDNS responders=1 candidates=[…]` in its first lines). On 2026-09-28 it picked a phone's USB-tethering link
   (`10.171.146.x`) over the PC's Wi-Fi, so it could never see the phone under test. Turn off USB tethering and
   other adapters, then check that the `candidates` address is on the phone's subnet (`adb shell ip -4 addr show wlan0`).
   **Stopping the farm:** killing the Gradle process can leave the farm JVM running (seen 2026-09-28). Check
   for a `java.exe` with `preferIPv4Stack` in its command line and end it before the next run.
4. **Phone:** Flash updated and opened once; discovery mode **STANDARD**; Bluetooth off; no other apps
   syncing if possible.
   **OEM freezer check (ERROR-074).** Transsion phones (Infinix, Tecno, itel) freeze Flash ~10 s after screen-off,
   even with a foreground service and "Battery: Unrestricted". A run on a frozen process measures nothing (the
   first R0 on the Infinix, 2026-09-28, was frozen for the whole hour). Before every run, with the phone plugged
   in: `adb logcat -c`, screen off, wait 60 s, then
   `adb logcat -d | findstr "Hiber.*transfer.flash"`. Any `freeze uid` line: apply the OEM's own per-app
   setting and check again. Record in the EXP entry whether the phone froze Flash or had been exempted.
   Other OEMs have their own freezers; on a new phone, check that Flash still logs with the screen off. Record: battery-optimisation exemption on or off, and the battery level range. Keep
   each run in the same range (e.g. start between 60 % and 90 %) and let the phone cool between runs.
5. **Start the farm, then check before unplugging:**
   - The phone's Nearby list shows all N farm peers. Several JmDNS responders in one JVM may not all be seen
     equally. The smoke run on 2026-09-28 showed uneven discovery *among* farm peers, so confirm on the phone.
   - The farm's status line shows the phone with N sessions (8 at most).

## 5. Battery runs (screen off, 60 min each)

For each phone, in this order, one run per row:

| Run | Farm | Label |
|---|---|---|
| R0 | none (Flash running, farm stopped) | `<phone>-n0` |
| R1 | `--count=1` | `<phone>-n1` |
| R4 | `--count=4` | `<phone>-n4` |
| R8 | `--count=8` | `<phone>-n8` |
| R19 | `--count=19` | `<phone>-n19` (cap + churn) |

Steps per run:
1. Start the farm with `--minutes=70 --report=60`. Wait until the phone holds the expected sessions.
2. `phone-baseline.ps1 -Action start -Label <label> -Serial <serial>`.
3. Unplug USB, screen off, don't touch the phone for 60 min.
4. Plug in and immediately run `phone-baseline.ps1 -Action collect -Label <label> -Serial <serial>`.
5. Keep the farm console output and CSV name with the run notes. The CSV shows whether sessions flapped
   during the run (a flap invalidates a battery run; see the invariants in the plan's §2).

**Farm tier:** default `--tier=HIGH` (the desktop's 10 s ping). To model a room full of low-end phones, repeat
R4 or R8 with `--tier=LOW` (15 s). Record the tier in every row.

## 6. Reconnect runs (plugged in is fine)

With the farm at `--count=4`, then `--count=8`:
1. Wait for all sessions to be up.
2. `phone-baseline.ps1 -Action toggle-wifi -OffSeconds 10`, three times, 2 min apart.
3. From the farm output, record each `[recover]` line: seconds until all farm sessions were back, session-ups
   and farm dials in between. From the phone (`-Action collect` with a new label afterwards), record the dial
   count.

## 7. Known limits of this rig

- All farm peers share one IP and the PC's network adapter. The phone's cost per session is realistic
  (TLS session, keepalive, discovery records); the *farm's* side is not a model of 19 phones' radios.
- Farm peers are desktop-kind peers: the phone doesn't probe them for data channels. Idle runs don't use data
  channels anyway.
- The batterystats summary lines are printed raw because the layout differs by Android version. The
  extraction was checked against a real dump from the Infinix X6882B (Android 14, API 34) on 2026-09-28; other
  phones may lay it out differently, so compare `summary.txt` with `batterystats.txt` on each phone's first run.
  That dump had no per-app Wi-Fi traffic lines (Flash had carried no traffic); confirm they appear in a run
  with sessions.
- The logcat counts are only as complete as the log buffer (`-G 16M` is best-effort; some devices refuse it).

## 8. EXP entry template (`logs/experiments.md`)

```markdown
## EXP-0NN — PC0 baseline: <phone>

### Device
Model, Android version, Flash version, tier the app picked, battery-optimisation exemption on/off.

### Network
Router/AP, band, channel width if known, PC wired or Wi-Fi.

### Battery runs (screen off, 60 min, farm tier HIGH unless noted)
| Run | Sessions held | Drain %/h | Flash CPU (s) | Flash Wi-Fi rx/tx | Phone dials | Cap refusals | Notes |
|---|---|---|---|---|---|---|---|
| R0 | 0 | | | | | | |
| R1 | 1 | | | | | | |
| R4 | 4 | | | | | | |
| R8 | 8 | | | | | | |
| R19 | 8 + 11 refused | | | | | | |

### Reconnect runs
| Farm size | Toggle | Seconds until all back | Session-ups | Farm dials | Phone dials |
|---|---|---|---|---|---|

### Conclusion
Cost per idle session, the cost of churn above the cap, and what it means for ECO (PC5) and PC1.
```
