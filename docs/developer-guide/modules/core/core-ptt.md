# Core Push-To-Talk Module (`:core:ptt`)

> **Rewritten 2026-09-30 (ADR-058).** The previous revision of this page described an API that the code never had
> (`pressFloor`, `releaseFloor`, `joinChannel`, `FlashPttState`, `REQUEST_FLOOR` / `FLOOR_GRANTED` frames, Opus chunks
> in `FLASH_PTT_CHUNK`). None of that exists. What follows was written from the code; the authoritative sources remain
> `docs/protocol.md` (wire format), `docs/architecture/public-api.md` section 14 (public API) and ADR-031 / ADR-032 / ADR-058.
> The old text is in git history.

`:core:ptt` is the push-to-talk voice engine: a strict **half-duplex** floor where one device transmits and every other
paired, online device listens. It is a Kotlin Multiplatform module with an **Android** and a **JVM** target, used by the
Android app and by the Windows desktop app.

---

## 1. Gradle dependency

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-ptt:v2.1.0-beta.1")   // JitPack coordinate; a mavenLocal build uses group com.transfer.flash
}
```

`core-engine` already exposes it on Android (`api`), and `ui-callui` `api`s it because the shared session card takes a
`FlashPtt`. Nothing is created until the host builds the engine and attaches it (a third-party host: `FlashEngine.attachPtt`; the Android app builds it itself in `DiscoveryEngineHolder`, the desktop app in `DesktopEngine.assemble`).

---

## 2. How a session works (ADR-032)

- **Floor:** there is no request/grant round trip. The floor machine (`PttFloorMachine`, in `:core:messaging`) is a pure
  reducer: a local press on an idle device starts capture and announces `Start`; a device that hears a `Start` from a
  trusted peer becomes a listener; a second press stops. If two devices start within the 1.5 s collision window, the one with the lower
  device id keeps the floor and the other stops and listens (no coordinator). Control frames are text (`FLASH_PTT` ping; `FLASH_PTSS` start / stop / leave / heartbeat / heartbeat-ack).
- **Audio:** raw PCM16 little-endian mono in binary frames with the `PTT1` magic, 16 kHz / 20 ms (MEDIUM and HIGH devices) or
  8 kHz / 60 ms (LOW). There is no codec. A receiver feeds a small jitter buffer (target depth 120 ms) and repeats the last
  packet at half level, then zeros, rather than stalling.
- **Safety rules:** a 60 s cap per burst, a 1 Hz heartbeat that also measures RTT, mutual exclusion with calls and voice notes,
  scope = paired peers only, and trust is checked against the *authenticated transport peer*, never against a claimed id.
- **Effect order that must not change:** `StartAnnounced` is sent before capture packets are enabled; a listener's local stop
  stops playout and then sends `Leave`, and the holder id must survive the playout stop (ERROR-046).

---

## 3. The public surface

`FlashPtt` (in `com.transfer.flash.core.ptt`) is the whole public interface:

| Member | Meaning |
|---|---|
| `state: StateFlow<PttFloorState>` | `Idle`, `Talking` or `Listening` |
| `stats: StateFlow<PttSessionStats?>` | 1 Hz telemetry (role, RTT, loss, depth, level, members); null when idle |
| `notices: SharedFlow<String>` | user-facing one-shots ("<name> stopped talking", burst warnings) |
| `pings: Flow<PttPingEvent>` | accepted, de-duplicated `FLASH_PTT` pings |
| `onPttButton(): PttPressOutcome` | toggle the floor; `ACCEPTED`, `NO_PEERS`, `NO_MIC`, `CALL_ACTIVE`, `VOICE_NOTE_ACTIVE` |
| `sendPing(): Boolean` / `postNotice(text)` | send a `FLASH_PTT` ping to the paired peers; show a one-shot notice |
| `stopLocal()` / `onCallStarted()` / `shutdown()` | stop, tear down for a call, terminal teardown |
| `acquireVoiceNoteLease()` / `releaseVoiceNoteLease(id)` | the microphone gate for voice messages |
| `onInboundText(peerId, text)` / `onInboundBinary(peerId, data)` | host routing seams; return true for PTT frames **including rejected ones** |

The host routes inbound frames to the engine **before** its own parsers, so a forged PTT frame can never be read as a chat frame.

---

## 4. The audio seam (ADR-058)

`PttSessionEngine` is commonMain and never touches audio hardware. It takes a `PttAudioPlatform`:

```kotlin
public interface PttAudioPlatform {
    public fun createCapture(requestedRateHz: Int, packetMs: Int,
        onPacket: (pcm: ByteArray, captureTsMs: Long) -> Unit, onCaptureLost: () -> Unit): PttCaptureDevice
    public fun createPlayout(sampleRateHz: Int, packetMs: Int,
        onAmplitude: (Float) -> Unit, onPlayoutLost: () -> Unit): PttPlayoutDevice
}
```

| Target | Implementation |
|---|---|
| Android | `AudioRecord` / `AudioTrack` (`PttCapture`, `PttPlayout`, internal) |
| JVM (Windows; Linux untested) | `javax.sound.sampled` through `PttPcmLines` (`JvmPttCapture`, `JvmPttPlayout`); tries the requested rate then 8 kHz and reports the rate it opened |

A host that needs neither (tests, a new platform) passes its own implementation; `DesktopEngine` takes one as a constructor
parameter so tests never open a real microphone. Three small `expect`s complete the module: `pttElapsedRealtimeMs()`
(public; the clock `PttFloorState.startedAtMs` uses), `PttLock`, and `platformPttAudio()`.

---

## 5. Hosting it

- **Android:** the engine is created by `DiscoveryEngineHolder` (`FlashEngine.attachPtt` does the same for a third-party host); `PttSessionOverlay` (app) adds the deferred hardware press, the
  `RECORD_AUDIO` prompt and the notification mirror, and draws the card.
- **Desktop:** `DesktopEngine.assemble` builds the engine over its WebSocket sessions (members = active sessions that are paired),
  and the shell adds a mic button, `Ctrl+Shift+T` (in-window only) and `Esc`.
- **The card** is `PttSessionOverlayContent` in `:ui:callui`, shared by both hosts (UI-051, Addendum A).

## 6. Status

Unit-tested on both targets, including two real desktop engines talking through real sockets with fake audio. **Not
device-verified**: desktop <-> phone, other audio hardware, and the Android path after the ADR-058 re-shape are
`PTTD-01`...`PTTD-07` in `docs/testing/TEST-BACKLOG.md`. Known limits: Windows' microphone privacy switch can give silent
capture the engine cannot detect; a cold capture open took about 1 s on the one machine measured (EXP-018).
