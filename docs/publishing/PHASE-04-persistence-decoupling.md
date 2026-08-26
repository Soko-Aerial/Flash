# Phase 4 — Persistence / SQLCipher Decoupling

**Goal:** stop forcing Room + the SQLCipher native `.so` payload (4 ABIs) onto
consumers who only want LAN file transfer. Make persistence an **optional**
capability, not a hard compile dependency of the transfer path.

**Prereq:** Phases 2–3. This is **Major**, not a hard blocker for the umbrella
`core-engine` artifact (which intentionally bundles everything). It matters for a
lightweight `core-transfer` consumer and for overall APK size / DX.

---

## The problem

`core:transfer → implementation(project(":core:persistence"))`, and
`core:persistence` pulls `room-runtime`, `room-ktx`, the KSP compiler,
`androidx.sqlite`, and `net.zetetic:sqlcipher-android:4.18.0` (ships
`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` native libraries). So any consumer of
the transfer path inherits an encrypted‑DB engine + megabytes of native code —
even though transfer already treats its DAOs as **nullable** (transfer runs
without a DB; only *resume‑across‑restart* is disabled when absent).

The dependency points the wrong way: a high‑level feature (transfer) compile‑time
depends on a concrete storage implementation (Room/SQLCipher).

---

## Task 4.1 — Invert the dependency (recommended)

Define the storage contract **in the transfer module** and let persistence
implement it. Transfer then has zero compile dependency on Room/SQLCipher.

1. In `core:transfer`, find every reference to a persistence type (the nullable
   DAOs — e.g. `TransferDao`, `TransferChunkDao` — and `FlashSettingsDataStore`).
   List them; that set defines the port.
2. Declare a minimal interface **owned by transfer** capturing only what transfer
   actually calls, e.g.:
   ```kotlin
   // core:transfer — the only storage surface transfer knows about.
   public interface TransferStore {
       suspend fun saveProgress(id: String, receivedBits: ByteArray)
       suspend fun loadProgress(id: String): ByteArray?
       // …exactly the methods transfer invokes today, no more.
   }
   ```
   Keep it nullable at the call sites exactly as the DAOs are today
   (`transferStore?.…`), preserving the "runs without a DB" behavior.
3. In `core:persistence`, add an adapter `RoomTransferStore(dao: TransferDao, …)`
   that implements `TransferStore` by delegating to the Room DAOs.
4. Remove `implementation(project(":core:persistence"))` from
   `core/transfer/build.gradle.kts`. Transfer now depends only on
   `common`/`security`/`network`/`discovery`.
5. `core:engine` (which already `api`s persistence) constructs the
   `RoomTransferStore` and passes it into the transfer repository — or passes
   `null` to run DB‑less. This is the one place the two are wired together.

6. Repeat the same inversion for `core:messaging` (it exposes `MessageDao`,
   `ConversationDao`, `OutboxDao`, `ReceiptDao`). Messaging defines its own
   `MessageStore` port; persistence adapts it; messaging drops the persistence
   dep. (If messaging is out of scope for the first library release, you may
   instead leave messaging as‑is and simply **not publish** `core-messaging` yet —
   see Task 4.3.)

**Acceptance:** `core/transfer/build.gradle.kts` has no `:core:persistence`
dependency; `./gradlew :core:transfer:dependencies` shows no `room` / `sqlcipher`
on any transfer configuration; transfer's `.api` no longer exposes DAO types.

---

## Task 4.2 — Interim option (if 4.1 is too large this pass)

If the inversion cannot be completed now, at minimum make the cost **visible and
opt‑out‑able** rather than silent:

- Keep transfer→persistence but document loudly in the README that the transfer
  path bundles SQLCipher native libraries (list ABIs + approximate size).
- Show consumers how to trim ABIs they don't ship:
  ```kotlin
  android { defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } } }
  ```
- Do **not** treat this as done — file it as a known limitation. Task 4.1 is the
  real fix.

---

## Task 4.3 — Decide the published module set

Not every core module has to ship in v1. Publishing fewer, cleaner artifacts is
better than shipping leaky ones.

- **Definitely publish:** `core-engine` (umbrella) + `core-common`.
- **Publish if their `.api` is clean and scopes are fixed:** `core-transfer`,
  `core-network`, `core-discovery`, `core-security`.
- **Hold back if still DAO‑coupled:** `core-messaging`, `core-persistence` (or
  publish `core-persistence` explicitly as the optional storage add‑on).
- JitPack builds and serves whatever the repo produces; you control the *supported*
  set purely through what the README documents. Undocumented modules still
  resolve but aren't promised.

**Acceptance:** the README (Phase 5) lists exactly which artifacts are supported
and which are optional/experimental.

---

## Verification (hand off)

```bash
./gradlew :core:transfer:dependencies --configuration releaseCompileClasspath
./gradlew apiCheck :sample:consumer:assembleDebug
```
Confirm no `net.zetetic`/`androidx.room` on transfer's release compile classpath
after Task 4.1.

