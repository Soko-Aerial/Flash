# STREAM C backlog additions (fragment; integrator appends to `docs/testing/TEST-BACKLOG.md`)

Prefix **`R22C-`** (reserved for this stream). Source: `docs/audit/2026-10-22-comprehensive-brutal-code-review.md`
STREAM C, plus this stream's finding verification in `progress.md` / `errors.md`.

Suggested new backlog section heading for the integrator:

```markdown
## R22C-42..80 (2026-10-22) - comprehensive review, STREAM C (calling / push-to-talk)

All **Status: TODO** unless said otherwise. Source: ERROR-780.. and
`docs/audit/2026-10-22-comprehensive-brutal-code-review.md` STREAM C. Unit-tested on `agent/rvw22-c-calling-ptt`;
nothing here is device-verified yet.
```

---

- **R22C-42 (call teardown when signalling is already dead, F42):** two phones in a 1:1 call, then kill the
  network under the *caller's* app before it hangs up: turn airplane mode on during the call, then press Hang up
  within a second (and repeat with Decline on the ringing side: ring the phone, turn airplane mode on the ringing
  phone, tap Decline). **Pass:** the call screen closes at once on the device that pressed the button (it never
  stays on "In call" with a dead peer), the app does not crash, and logcat has `CALL: teardown frame Hangup not
  sent call=...` (or `Decline`) rather than an uncaught exception. Before the fix the call screen stayed up with no
  timeout left to rescue it. **Source:** ERROR-780, F42. **Status:** TODO (unit tests `hangUp_endsTheCallWhenThe
  FrameSendThrows`, `decline_stopsRingingWhenTheFrameSendThrows`, mutation-checked)

- **R22C-43 (video concession after a clock step, F43):** two phones on a weaker link (walk away from the router
  until the picture drops to reduced bitrate/resolution), then set the phone's date/time backwards by 1 hour while
  the call is up. **Pass:** the video stays on the reduced rung for at least the 8-second recovery cooldown from the
  step (no immediate flip back to full followed by another drop); once the cooldown is genuinely over the picture
  returns to full. Watch for `CALL: quality`/concession log lines; a strobe (up, down, up within a few seconds) is a
  FAIL. **Source:** ERROR-781, F43. **Status:** TODO (unit test `a backwards clock step does not bypass the
  recovery cooldown`, mutation-checked)

- **R22C-45 (PTT playout across the u32 sequence wrap, F45):** Android phone listening to PTT from the desktop
  (or another phone). This one cannot be reached by waiting; use the `:desktop:radioLinkTest`-style harness or a
  temporary test build that starts the sender's packet counter at `0x7FFFFFF0`. **Pass:** audio keeps playing across
  the wrap (`0xFFFFFFFF` → `0`), no `late` counter climb, no silence; also confirm the ordinary case still works:
  the first two packets of a burst arriving swapped still play in order. **Source:** ERROR-782, F45. **Status:**
  TODO (unit tests `aSequenceThatWrapsIsTheNextPacketNotAnOldOne`, `theFirstPacketsMayArriveInTheWrongOrder`,
  mutation-checked; note the wrap needs ~2.7 years of continuous 20 ms packets in the field, so this is a contract
  check, not a field bug — do not block a release on it)

- **R22C-46 (PTT overflow behaviour, F46):** Android phone listening; jam the receiver for a second (very weak
  Wi-Fi, or `tc`-style shaping on a hotspot) so the playout buffer overflows. **Pass:** the audio degrades by
  dropping the *newest* packets — the listener hears continuous, slightly delayed speech rather than a hole right
  after the jam — and `droppedOverflow` climbs while `lost` does not. **Source:** ERROR-783, F46. **Status:** TODO
  (unit test `overflowDropsTheNewestSoTheNextPollStillPlays`, mutation-checked)

- **R22C-57 (reactions after a clock step, F57):** two phones in a call; one sends a reaction, then the *other*
  phone's clock is set backwards by 1 hour, then the first phone sends two more reactions a second apart. **Pass:**
  both reactions appear on the phone whose clock moved (they are not swallowed), and a burst of five within a second
  still shows at most one — the flood rule applies from the new time. **Source:** ERROR-784, F57. **Status:** TODO
  (unit test `a backwards clock step does not freeze a peer's reactions`, mutation-checked)

- **R22C-44 (two quick PTT taps while talking, F44):** two phones paired and online with PTT; watch the *tapping*
  phone's logcat. **Steps:** start talking, then tap the PTT button twice as fast as you can; repeat with the
  microphone permission of the PTT app revoked between the taps (Settings → Apps → Flash → Permissions, do it while
  the first tap is still connecting). **Pass:** the two taps end the session and never start a second one; with the
  permission revoked mid-sequence the log shows `PTT press refused under the floor: no mic permission` (or
  `no peers`) and the overlay shows "Microphone permission is needed to talk" instead of opening a new capture; the
  notification/overlay never flickers into a second "talking" state. Before the fix the third tap could open a
  session without re-checking anything. **Source:** ERROR-785, F44. **Status:** TODO (unit test "a press queued
  behind a stop cannot open a session with the microphone switched off", mutation-checked)

- **R22C-58 (a failed capture start must not send a Stop, F58):** two phones paired, PTT session ready; on one,
  revoke the microphone permission and press PTT (or use a build where the capture device fails to open). **Pass:**
  the pressing phone says "Microphone unavailable — session ended" and returns to idle, the other phone shows
  nothing at all (no "stopped talking", no listening state, no "unknown session" line in its log), and the pressing
  phone's log shows no `Stop` control frame. Then run the ordinary mid-session failure (kill the mic by starting a
  phone call, or `adb shell` revoking the permission while talking): the members must still get the Stop and end
  their listening. **Source:** ERROR-786, F58. **Status:** TODO (machine tests
  `micDeniedBeforeAnnounceReleasesTheFloorWithoutSendingStop` / `micDeniedAfterAnnounceTellsTheMembers` plus the
  engine assertion, mutation-checked)

- **R22C-59 (PTT press with no device identity, F59):** on a phone that has never completed setup (fresh install,
  identity not yet generated — a debug build that delays identity loading is the reliable way), press the hardware
  PTT button and the in-app PTT button. **Pass:** nothing starts (no capture, no Start frame), the log says
  `PTT press refused: no local device id yet`, and once the identity exists a press works normally. With two such
  phones the collision case cannot be reproduced by design (both refuse). **Source:** ERROR-787, F59. **Status:**
  TODO (unit tests "a press without a local device id is refused" and "a blank local device id is refused too",
  mutation-checked)

- **R22C-80 (media release vs. the next call's media, F80 — INVESTIGATION):** two phones; call A, hang up, and dial
  again within ~200 ms (repeat 20 times, ideally from a script or with a fast double-tap on the call button). If you
  can, capture `adb logcat -b all` plus `adb shell dumpsys media.camera` (or `dumpsys media.audio_flinger`) right
  after the re-dial. **Pass (to answer the question, not a fix):** every re-dial either reaches audio or shows the
  existing "camera refused / microphone unavailable" banner with Try again, and the log never shows the *new*
  session's `getUserMedia` failing while the *old* session's `CALL: releaseMedia` is still running. Report the two
  log line timestamps for a failing case; if none fails in 20 cycles, F80 is closed as no-evidence. **Source:**
  F80 (CANNOT-CONFIRM). **Status:** TODO (no production change; see `progress.md` "F80")
