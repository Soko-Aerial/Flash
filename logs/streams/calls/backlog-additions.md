# Stream C (calls) — device-test additions

Reserved prefix: **PERF-C**. The integrator folds these into `docs/testing/TEST-BACKLOG.md`. Nothing in this stream is
device-verified: no phone and no desktop call session was used.

## PERF-C01 — Call and PTT quality on two devices (covers every finding in this stream)

**Why it exists.** Every item below is a change this stream made without a device: the desktop camera, the occluded
tile, the mesh stats pass, the SDP body, the PTT framing, the jitter buffer's learned start gate, the desktop audio
pre-warm, the Android `AudioTrack` mode and the call-dock layout.

**Setup.** Two phones (one of them the Infinix X6882B or the BelFone SCP810, which the audit used) plus the Windows
desktop on the same Wi-Fi. Build with `versionName` from this branch. Start a log capture on each device
(`CALL_DIAG`, `CALL_RENDER`, `PTT_*`, `PROBE` tags).

**Steps and what to read.**

1. **Desktop camera off** (`OPT-O11-02`). Desktop joins a video call with a phone, then the dock's camera button turns
   the camera off. **Pass:** the webcam LED goes out (and any OS camera-in-use indicator clears) within a second; the
   log shows no new `CALL_RENDER … source=` lines for that tile; turning the camera back on restores the picture
   without renegotiation. **Fail:** the LED stays on, or a frame is still converted while off.
2. **Camera switch while muted** (`ERROR-981`). With the camera off, press Flip. **Pass:** nothing happens visually
   and the LED stays off; the picture returns from the new camera only when the camera is turned back on.
3. **Data saver / remote camera off** (`OPT-O11-06`). On the desktop, enable data saver during a video call (or have
   the phone turn its camera off). **Pass:** the placeholder covers the tile and the desktop log shows no
   `CALL_RENDER` "render stats" line for that surface (no frames converted). Turning data saver off brings the picture
   back with no black tile (the renderer must not have been rebuilt).
4. **Mesh call stats** (`OPT-O11-04`). Four participants for two minutes. **Pass:** `CALL_DIAG` leg lines keep arriving
   at the tier's interval, the RTT/kbps numbers are plausible against a separate ping, and the UI does not stutter.
   Watch for dropped frames in the app's own frame stats if available.
5. **PTT listen quality** (`OPT-O12-01`, `-02`, `-04`). A 60-second PTT burst between two phones, then a burst on the
   desktop. **Pass:** the audio is intelligible with no crackle; `ptt` stats update smoothly (the level bar moves at
   ~10 Hz, not 50 Hz). On the second and third bursts, `PERF`-relevant: the listener's first sound arrives no later
   than the first burst (the learned start gate may lower the 120 ms gate on a clean LAN — record the delay before the
   first syllable at each burst). **Fail:** a clipped first syllable on a later burst (the gate went too low for that
   link) — record the link and the burst index, and the depth that `PttJitterDepth` had learnt.
6. **Desktop first press** (`OPT-O12-03`). Cold-start the desktop app and press PTT within ~2 s. **Pass:** the log has
   one `PTT_JSND Audio warm-up opened=2 lines in <n>ms` line before the press, and the first words are not clipped.
   **Note:** the warm-up opens the microphone for a few milliseconds at start-up; a Windows privacy indicator may blink
   once. Record whether it does.
7. **Android playout path** (`OPT-O12-05`). On each phone, receive PTT audio and read `PTT_OUT Playout started … 
   performanceMode=<n>`. **Pass:** the value is 1 (`PERFORMANCE_MODE_LOW_LATENCY`) on a device that supports the fast
   path; record the value per handset. Listen for underruns (a hole in the received voice) — the buffer was *not*
   reduced, so none is expected.
8. **Voice and video on a mixed call** (`OPT-O11-05`). Phone ↔ desktop call: **pass** if the desktop's incoming video
   is not black (that is what `enforceVp8Only` protects) and the SDP log line for the answer has no `a=fmtp:` in the
   video section.
9. **Call dock layout** (`OPT-O03-18`). On a 360 dp phone in a 6-button video call and on a narrow window on the
   desktop: **pass** if the six circles do not touch or overlap, the labels are not clipped, and every tap lands on its
   own button. Compare against a screenshot from before this branch if one exists (the sizes at 360 dp are identical
   by arithmetic).

**Status:** TODO. **Source:** `OPT-O11-02/-04/-05/-06`, `OPT-O12-01/-02/-03/-04/-05`, `OPT-O03-18`, `OPT-O28-03`,
`ERROR-981`, `EXP-135`.

## PERF-C02 — `CALL_DIAG` quality numbers on a bad link (`OPT-O28-03`)

**Setup.** The two phones from `PERF-C01`, plus a way to make the link bad (walk out of range, or a microwave/other
2.4 GHz traffic). Start a voice call with video on.

**Steps.** Read the `ain` section of the `CALL_DIAG` leg lines for ~60 s while the link is good, then while it is bad.

**Pass:** `jb=` appears with a plausible value (tens of ms), rises when the link degrades, and `plc=` rises with it
(over ~2 % should match audible robotic artefacts); `avDrift=` stays within ±50 ms when lip-sync looks right. On a
backend that reports none of the counters, the fields are simply absent — that is a pass too, and worth recording
(which host omits them).

**Status:** TODO. **Source:** `OPT-O28-03`, `CallQualityMathTest`.

## PERF-C03 — the `:webrtc-kmp` fork republish (`OPT-O11-02`)

**Why.** The desktop camera fix lives in the vendored fork. Until the fork is republished (ADR-103) and the app's
version bumped, the desktop app still ships the old behaviour.

**Steps.** Publish the fork (`:webrtc-kmp:webrtc-kmp:publishToMavenLocal` or a JitPack release), bump the version, then
run `PERF-C01` steps 1–2 on the desktop.

**Status:** TODO (blocked on the integrator/owner deciding the version bump). **Source:** `OPT-O11-02`,
`third_party/webrtc-kmp/MODIFICATIONS.md`.
