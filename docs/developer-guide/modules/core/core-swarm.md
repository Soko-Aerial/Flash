# Core Swarm Module (`:core:swarm`)

> **Written 2026-10-09 from the code.** Until then this published module had no guide. Verified against
> `core/swarm/src`, `core/engine/.../FlashEngine.kt`, and how the two real hosts attach it. Design and ADRs:
> `docs/transfer/GROUP-SWARM-DESIGN.md`, `docs/transfer/GROUP-SWARM-IMPLEMENTATION-PLAN.md`, ADR-070..075 (all
> status as recorded in `docs/decisions.md`). **Not device-verified** (`SWM-07`..`SWM-29` in `docs/testing/TEST-BACKLOG.md`).

`:core:swarm` sends one file to the members of a group the way a torrent would: every member that already holds
pieces serves them to the others, so the sender does not upload the whole file once per member. It is a **sans-IO**
engine (no sockets, no database, no Android types) plus a small driver, so the same code runs on Android and on the
desktop JVM. It is **not** a transport: pieces travel as `FSW1` frames over the sessions the network layer already has.

It is a Kotlin Multiplatform module with an **Android** and a **JVM** target (`core/swarm/build.gradle.kts`), and
`api`-depends on `:core:common` and `:core:transfer`.

---

## 1. Gradle dependency

```kotlin
dependencies {
    implementation("com.github.Kali452345.Flash:core-swarm:v2.1.0-beta")
}
```

`core-engine` already `api`s it, so an umbrella consumer has it on the classpath. Nothing runs until the host attaches
it, and **in the Flash apps the feature is off by default**: the Android host calls `attachSwarm()` only when the
user's "group swarm" setting is on (`groupSwarmEnabled` in `DiscoveryEngineHolder`).

---

## 2. Public API (what a host touches)

### `FlashSwarm` (package `...core.swarm.api`)

| Member | Meaning |
|---|---|
| `rows: StateFlow<List<FlashTransfer>>` | The transfer rows the swarm manages (shown next to normal transfers). |
| `status(transferId): StateFlow<FlashSwarmStatus?>` | Live detail for one row. |
| `accept(id)` / `decline(id)` | Receiver answers an offered swarm transfer (decline = local cancel). |
| `pause(id)` / `resume(id)` | User pause and resume. |
| `cancelLocal(id)` | Receiver removes its own partial copy; others are unaffected. |
| `cancelAsOrigin(id, reason = USER)` | Origin cancels for everyone: signs and broadcasts a tombstone (`SwarmTombstoneReason`). |
| `pauseForSystem(id, reason)` | Marks the row `WAITING_FOR_SYSTEM` (for example an Android 15 foreground-service timeout). |
| `reevaluate()` | Recomputes wait reasons after a network change or wake-up. |
| `runRetentionCleanup()` | Purges expired records and tombstones and orphaned partial files. |

### `FlashSwarmStatus`

`root` (`ContentRoot`), `groupId`, `holdersOnline`, `distributedCopies`, `canGoOffline` (the sender may leave: enough
copies exist), `waitReason: SwarmWaitReason?`, `piecesDone` / `totalPieces`, `bytesDone` / `totalBytes`, `isComplete`,
`pieceBlocks: List<Int>` (the availability map the transfers UI draws) and, on the sender only, `recipients`
(`RecipientSnapshot` per member).

`SwarmWaitReason` has seven values, each with a defined recovery event: `WAITING_FOR_SENDER`, `WAITING_FOR_HOLDERS`,
`WAITING_FOR_NETWORK`, `WAITING_FOR_SPACE`, `WAITING_FOR_STORAGE`, `WAITING_FOR_SYSTEM`, `WAITING_FOR_SESSION`.

### `FlashSwarmConfig`

| Field | Default | Meaning |
|---|---|---|
| `profile` | `MEDIUM` | `FlashSwarmProfile` `LOW` / `MEDIUM` / `HIGH`: serve slots 1 / 2 / 4 and an in-flight budget of 8 / 16 / 32 MiB. |
| `autoAccept` | `false` | Accept offered swarm files without asking. |
| `servingEnabled` | `true` | Whether this device serves pieces to others. |

The engine-level `SwarmConfig` also carries `retentionMs` (default **7 days**, ADR-100: group files are offered and kept for
7 days; the swarm retention was deliberately not raised).

### Attaching it to an engine

```kotlin
val swarm: FlashSwarm? = engine.attachSwarm(FlashSwarmConfig(profile = FlashSwarmProfile.MEDIUM))
swarm?.rows?.collect { /* show the group-file rows */ }
engine.detachSwarm()   // idempotent; engine.close() detaches too
```

`FlashEngine` exposes `swarm`, `attachSwarm(config)` (builds a binding wired to the facade, returns `null` if the
engine cannot supply the needed parts), `attachSwarm(swarm)` (attach one you built) and `detachSwarm()`.

---

## 3. Seams a host (or a custom transport) implements

The driver talks to the world only through these interfaces, which is what makes the engine testable without a network:

| Interface | Role |
|---|---|
| `SwarmTransport` | `send(peerId, frame: ByteArray): Boolean`, `requestSession(peerId)`, and `connectedPeers: StateFlow<Map<String, Set<String>>>` (peer to the features it advertised in HELLO). |
| `SwarmGroupContext` | Group rules: `isPeerAllowed`, `isServeAllowed`, `isLocalActiveMember`, plus `signStatement` / `verifyStatement` / `authorKey` for signed tombstones. |
| `PieceStorage` | Reads and writes pieces (`SourceHandle`, `PartialHandle`) and finalizes a finished file. Android's is `AndroidPieceStorage`. |
| `SwarmStateStore` | Persists `SwarmContentRecord`s and `SwarmTombstone`s (the Room adapter is `RoomSwarmStateStore` in `:core:engine`; the tables and DAO are in `:core:persistence`). |

`core:engine` ships the glue, `SwarmHostBinding` (package `...core.engine.swarm`), which connects the driver to the
WebSocket mesh through the magic-frame router. The Android app (`DiscoveryEngineHolder.attachSwarm`) and the desktop app
(`DesktopEngine`) both construct a `SwarmHostBinding` with their own storage, state store, group context and
"is a call active / is serving allowed" callbacks (serving is refused in ECO discovery mode and during a call).

---

## 4. Inside the engine (for contributors)

| Package | Contents |
|---|---|
| `...swarm.engine` | `SwarmEngine` (pure state machine: events in, `SwarmCommand`s out), `PiecePicker`, `RequestWindow`, `SourceSelector`, `ServePolicy`, `StrikeBook`, `WaitClassifier`, `SwarmSnapshot`. |
| `...swarm.driver` | `SwarmDriver` (runs the engine against the seams above). |
| `...swarm.codec` | `SwarmFrame` / `SwarmFrameCodec` (the `FSW1` frames), `ManifestCodec`, `SwarmStatement` (the bytes that get signed). |
| `...swarm.model` | `Bitfield`, `PieceMath`, `ManifestBuilder`, `SwarmManifest`, `ContentRoot`, `FileIdentity`, lifecycle / failure / reject / tombstone enums. |

The wire format is in `docs/protocol.md` ("Group swarm wire FSW1 v1"). Random choices use `SeededRandom`, so tests are
deterministic.

---

## 5. Limits and facts to know

- Needs a group: membership and signing come from the group layer (`SwarmGroupContext`), so a 1:1 chat does not use it.
- Vouched members receive swarm offers (ERROR-117 / ADR-086); a removed member is excluded.
- Retention and cancel propagation use signed tombstones; cancel-for-everyone only works while members can still be reached.
- Everything here is unit, property and host-lifecycle tested; none of it is device-verified.
