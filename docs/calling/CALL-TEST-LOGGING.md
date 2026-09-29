# Call test logging — what to collect after a call test, and what each line means

**Added:** 2026-09-29 (owner request: "add logging for all the tests so I can compile and send"). Applies to the
group-call device checks in `docs/calling/GROUP-VIDEO-PLAN.md` §8 (G1–G7) and to 1:1 calls.

## 1. Collecting logs

| Device | How | Notes |
|---|---|---|
| Desktop (`:desktop:run` or the portable `Flash.exe`) | Copy `%USERPROFILE%\.flash\desktop.log` **before relaunching Flash** | The file is rewritten at every launch. Lines carry `HH:mm:ss.SSS` since 2026-09-29. Camera lines from the WebRTC layer (`[webrtc-jvm] camera …`) go to the console only. |
| Android | `adb logcat -v time > phone-<name>.txt` started before the call; stop after hanging up | Filter later, not at capture: the tags below plus `Hiber` (ERROR-074) and `AndroidRuntime`. |

Keep the screen on for every phone in the call (Transsion freezer, ERROR-074). Note each device's clock offset if
the lines need to be lined up across devices.

Useful filter: `grep -E "GROUP_CALL|CALL_DIAG|CALL_RENDER|CALL/|WS: Call|Hiber"`.

## 2. Lines

### `GROUP_CALL` — call lifecycle (every event)
- `acquire media video=… tier=… camera asked WxH@fps`, then `camera opened WxH@fps` (desktop reports the opened
  mode; Android may print `?`). Group calls ask for the tier profile capped at 720p (ERROR-075).
- `rx from=<peer> [via=<relay>] leg=<STATE> pc#<n> <frame>` — every inbound call frame except ICE candidates.
  `via` appears when another participant relayed it (a `gjoin`).
- `Leg <peer> pc#<n> created role=offerer|answerer` — a new connection to that participant. `pc#` counts
  connections per participant, so `pc#2` means the first one was thrown away.
- `Leg <peer> pc#<n> kept on gaccept|gjoin via <peer> (…)` / `rebuilt on … (…)` — a repeated accept/join arrived
  while the connection was being set up (ERROR-076). `Re-sent pending offer` follows a keep when this device offers.
- `Sent offer … pc#n`, `Answered offer … pc#n`, `Applied answer … pc#n`, `Offer from … on existing pc#n …`,
  `Stale answer from … ignored` — signalling.
- `Leg <peer> pc#n ice=<Checking|Connected|Failed…>` and `… state changed to <…>` — ICE (network path) and the
  whole connection (ICE + DTLS). ICE Connected followed by state Failed points at DTLS (keys), not the network.
- `video route limits=… sending={peer=height} free=n receive={peer=STATE} focus=… main=… compact=… health=…` —
  the G3–G6 video router's state, logged when it changes. `sending` is who gets this device's video and at what
  height (G4); `receive` whose video this device asked for and what came back (`REQUESTED`, `RECEIVING`,
  `BUSY`, `CAMERA_OFF`, `UNMANAGED`); `limits` is `GroupVideoLimits` (receive/send caps, split budget, `acceptNew`).
- `Leg <peer> video send=on|off height=…` and `video sender tuned … height=…p max=…kbps` — an encoding switched.
- `health warning=… thermal=… cpu=…% softwareDecode=…` — G6 changes.
- `Call … is full` — G7.

The `WS: Call sendFrame action=…` lines are the outbound side of the same frames.

### `CALL_DIAG` — every 5 s during a connected call
One line per connection and one for the process:

```text
CALL_DIAG leg=<peer> pc#1 CONNECTED rtt=4ms bwe=2400kbps path=host/host | vin 640x360 24.0fps dec=libvpx 2.1ms dropped=0 freezes=0 lost=0 jitter=3ms pli=0 780kbps | vout 960x540 30.0fps enc=libvpx 6.8ms limit=none 900kbps | cam 1280x720 30.0fps | ain 31kbps lost=0 jitter=2ms concealed=0 | aout 29kbps
CALL_DIAG proc cpu=180%core (23% of 8 cores) heap=210/4096MB committed=900MB direct=12MB threads=95 sysFree=6000MB call=ae668f1b video=true tier=high band=5 GHz thermal=NONE g6cpu=23% camera=720p30 legs=[beede0b3:CONNECTED#1,…]
```

- `vin` — this participant's video arriving here: size, frames decoded per second, decoder (`libvpx` = software,
  `c2.*`/`OMX.*` hardware on Android), decode time per frame, `dropped` (decoded but not shown), `freezes`.
- `vout` — this device's video to that participant: size, fps, encoder, encode time, `limit` = WebRTC's
  `qualityLimitationReason` (`cpu` means the encoder is lowering quality because the CPU can't keep up,
  `bandwidth` the network). Present but `0kbps` when the encoding is off (not requested).
- `cam` — the camera's actual output (media-source), where the backend reports it.
- `proc` — CPU as % of one core and of all cores; memory. On the desktop **`committed` is the number that
  shows native memory** (on Windows it is the process's private bytes); a steady climb during a call is a leak.
  Android shows `native=` (native heap) instead.

### `CALL_RENDER` — desktop only, every 5 s per video tile
- `render track=<id> source=WxH output=WxH rotation=…` when a tile's picture size changes.
- `render stats track=<id> in=…fps converted=…fps drawn=…fps dropped=n source=… output=… convertMs=… target=WxH`:
  frames that arrived, were converted, were drawn on screen, and were skipped because the screen hadn't drawn the
  previous one. `output` is smaller than `source` when the tile is small (ERROR-075). `track` matches the
  `id=` in `Received track on leg … : Video id=…`.

## 3. What to look at per test

| Test | Look for |
|---|---|
| Any video call on the desktop | `CALL_DIAG proc … committed=` stays flat over minutes; `CALL_RENDER … dropped` is low while the window is visible |
| G1 (every participant's video) | one `Received track … Video` per participant; a `CALL_RENDER` stats line per tile |
| G2 (bands) | `Leg <peer> band=…` lines; `band=` on the proc line |
| G3 (video by request) | `rx … VideoRequest/VideoGrant/VideoDeny`, `video route … receive=` |
| G4 (budgets) | `video route … sending={peer=height}`, `video sender tuned … height=`, `vout WxH` on the diag line |
| G5 (focus) | `video route … focus=… main=…` after a tap |
| G6 (health) | `health warning=…`, `thermal=` / `g6cpu=` on the proc line, `vdeny … thermal` |
| G7 (caps) | `turning <peer> away` / `is full` |
| A leg that never connects | its `pc#` numbers, `kept`/`rebuilt`, `ice=` vs `state changed to`, `Stale answer` |
