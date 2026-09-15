# Pairing / desktop-session handover (2026-09-14)

Written at the end of a long session, for whoever picks this up next (human or agent). Read this
**first**; then `logs/migration.md` (the tail entries are this session's) and
`PAIRING-GATE-RUNBOOK.md` (the diagnosis table).

**Nothing from this session is committed** — the whole working tree is the session's work.

## What is verified working (do not re-litigate these)

| Fact | How it was verified |
|---|---|
| Desktop discovers the phone via **multicast** (JmDNS still produces nothing for it) | `Found Flash V760 at 192.168.1.104:45822` in the live app |
| Desktop dials a discovered peer **on the discovery edge** and holds a session | `Auto-connect dialing` → `Session up … outbound=true` in the live app |
| **Pairing works both directions through the real app**, dialog included | `DesktopEnginePairingTest` (headless, two real engines) + a live pair |
| **Accepting works**: both sides `Confirmed` + trust persisted **on both sides** | same test, `acceptLocal()` then `trust.isTrusted(peer)` both ways |
| Trust **and the peer's name** survive a restart | `DesktopTrustStoreTest` — a second store over the same directory |
| A partial `startAll` failure no longer aborts the composition | `DesktopEngine`/`DiscoveryEngineHolder` log and continue |
| The desktop's scope is no longer cancelled at startup | `[bring-up] … assemble complete` in the live app |

## The queue, in priority order

1. **One log closes the "trusted peer doesn't appear after accepting" question.** Pair once, then read
   `~/.flash/desktop.log` for `[shell] engine pairing state:` vs `[shell] Nearby screen state:`.
   Suspect: `DesktopShell.kt` removes a peer from *Discovered* once its id is in `trustedIds`, so if the
   trusted row isn't composed in that same frame the device leaves both lists. **Evidence, not a guess.**
2. **Mirror the hello request/answer into the app host.** The fix landed in the shared coordinator only.
   App-side files: `app/src/main/java/com/transfer/flash/pairing/PairingFraming.kt` (`encodeHello`, the
   `TYPE_HELLO` decode) and `.../pairing/PairingCoordinator.kt` (`onInbound` Hello branch, `beginPair`
   wait loop). Rules: ask with `hrq=1`; **answer with a plain hello** (never echo the flag); re-ask
   every ~400 ms inside `FINGERPRINT_WAIT_MS`. Backwards compatible — an old peer ignores the flag.
3. **Connect glare.** `JvmWsFlashNetwork.registerSession` / `resolveGlareTie` in **both**
   `core/network/src/jvmMain` and `core/network/src/androidMain`. The loser is rejected silently and its
   dialer hangs the full 6 s handshake timeout; one measured run had the two sides **not converge in
   30 s** (`alpha` had a session, `beta` none). Add per-side logging first — both endpoints emit the same
   `I/WS:` lines into one stream, which made the failing run ambiguous.
4. **Desktop chat** — `DesktopEngine.chats = EmptyFlashChatRepository`; blocked on **09B-2** (Room-KMP,
   D5 = C pending). Not a bug. Phone messages have nowhere to land and no conversation screen exists.
5. **`AlertDialog` sheets on desktop** (e.g. `FlashAddMembersSheet.kt`): Material3 `AlertDialog` renders
   through `androidx.compose.ui.window.Dialog` — a **separate native window** with its own stacking.
   Recommendation: convert them to in-screen overlays, the pattern the pairing dialog uses (it works).
6. **Run the Phase 16 ladder** (`PAIRING-GATE-RUNBOOK.md`) — L2/L3 through the *app*, L4 decline, L5
   gating, L6 restart, L7 revoke, L9 negative. Then update README rows **16** (G2/G6) and **23**.

## Hard-won rules — these cost the session, do not rediscover them

- **A composable body re-executes, and `Window(...)` does not block.** `application { }` is what keeps
  the process alive (its `runBlocking` parks `main`). A bare `engine.stop()` after `Window(...)` ran
  ~300 ms after `start()`, cancelling the engine scope mid-bring-up: transports kept running while every
  `scope.launch` silently became a no-op. Use `DisposableEffect(Unit) { onDispose { … } }`.
- **`derivedStateOf` tracks snapshot state only.** A plain `val` computed above it and captured by the
  lambda is a **constant**. That is what kept the pairing dialog invisible: the request arrived fresh,
  the phase stayed `Idle` forever. Anything such a lambda reads must be state, or read inside the lambda.
- **The Gradle console is not a record.** `:desktop:run` is a `JavaExec` under a progress renderer that
  rewrites lines, and stderr interleaves. `~/.flash/desktop.log` is complete and ordered — argue from it,
  never from a line's absence in a paste.
- **`jstack` a stalled app.** "No thread in `assemble()`, workers idle, transports alive" has exactly one
  shape: the scope was cancelled. That is how the `stop()` bug was found after two wrong theories.
- **The harness is not the product.** It *discards* `startAll`'s result; the app used to `require` it.
  Any "works in the harness, not in the app" report should start from that asymmetry.

## Commands

```bash
# env for every Gradle call
export JAVA_HOME="/c/Users/KaliOxygen/.gradle/jdks/jetbrains_s_r_o_-21-amd64-windows.2"
export JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\Users\KaliOxygen\.gradle\afunix'

./gradlew :desktop:jvmTest              # boot, auto-dial, pairing(+accept), trust-store
./gradlew :core:security:jvmTest        # coordinator
./gradlew :core:engine:jvmTest          # harness + pairing loopback over the real wire
./gradlew :desktop:run                  # the app; logs to ~/.flash/desktop.log

# drive pairing into the app without touching the GUI (initiator = harness)
./gradlew :core:engine:interopHarness --args="pair 127.0.0.1 45822 40"

# capture ONLY the Flash window (not the whole screen) — build/capture-flash.ps1
powershell -NoProfile -File build/capture-flash.ps1 build/flash-window.png
```

Reset state: `rm -rf ~/.flash` (new identity ⇒ the phone's trust in the old one dies) and
`rm -rf /c/Users/KaliOxygen/AppData/Local/Temp/flash-interop-pair*` for the harness.
