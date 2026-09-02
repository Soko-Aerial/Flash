# Phase 02 — Delete `core/transfer/wslegacy/`

**Blocked by:** Phase 00.
**Risk:** low, but it is a deletion — verify before you delete.
**Decisions needed:** none.

## Why this phase exists

`core/transfer/wslegacy/` is dead code. It also contains 2 of the 3 Android-coupled
files in `core/transfer`. Deleting it is the cheapest Android-decoupling in the whole
migration: it removes `android.content.Context`, `android.net.Uri`,
`android.os.SystemClock`, `android.provider.OpenableColumns`, and `android.util.Log`
from the module in one commit.

See AUDIT.md CORRECTION 3.

## Preconditions

- Phase 00 logged and passing.
- Phase 01 committed (so the deletion diff is not tangled with line-ending churn).

## Files in scope

Delete exactly these, and nothing else:

```
core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/WsTransferManager.kt
core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/WsDiscovery.kt
core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/WsPairingStore.kt
core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/LegacyDiscoveredDevice.kt
core/transfer/src/test/java/com/transfer/flash/core/transfer/wslegacy/WsPairingStoreTest.kt
```

## Steps

### Step 1 — Re-verify the code is dead. Do not skip this.

The audit found no external references, but you must confirm it yourself before
deleting anything.

Search for every public symbol the package exposes:

```bash
grep -rn --include=*.kt -E "WsTransferManager|WsDiscovery|WsPairingStore|LegacyDiscoveredDevice" . | grep -v "/wslegacy/"
```

**Expected: no output.**

If there IS output, **stop**. The package is not dead. Abort the phase, record what
references it in the log, and report to the human. Do not delete partially.

Also check for string-based or reflective references:

```bash
grep -rn --include=*.kt --include=*.pro --include=*.xml -E "wslegacy" . | grep -v "docs/"
```

Review anything in `consumer-rules.pro` or `proguard-rules.pro` — a keep rule naming
these classes must be removed too.

### Step 2 — Check the public API surface

`core/transfer` runs `explicitApi()`. If any of these classes is `public` and was
re-exported, deleting it is a **breaking API change** for library consumers.

```bash
grep -rn "public" core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/
```

If any `public class`/`public fun` exists here, note it in the log under
**Deviations** and add a line to `docs/decisions.md` as a new ADR recording the
removal. Per CONVENTIONS.md R8, append a new ADR — never edit an existing one.

Given the package is unreachable from anywhere else in the repo, the practical risk
is that an external consumer imported it directly. The library is at version 1.0.0
and was only just published, so this is acceptable — but it must be **recorded**, not
assumed.

### Step 3 — Delete

```bash
git rm -r core/transfer/src/main/java/com/transfer/flash/core/transfer/wslegacy/ core/transfer/src/test/java/com/transfer/flash/core/transfer/wslegacy/
```

### Step 4 — Remove now-unused dependencies

Read `core/transfer/build.gradle.kts`. If any dependency existed **only** for
`wslegacy` (a WebSocket client, an OkHttp dependency, etc.), remove it. Do not guess
— confirm with a grep that no remaining file uses it.

If nothing becomes unused, say so explicitly in the log. That is a normal outcome.

## Verification

```bash
./gradlew :app:assembleDebug testDebugUnitTest --no-configuration-cache
```

Must pass.

Then confirm the Android coupling actually dropped:

```bash
grep -rn --include=*.kt -E "^import android\.|^import androidx\." core/transfer/src/main/
```

Expected: **no output**. (`RealFlashTransferRepository.kt` uses fully-qualified
`android.util.Log` with no import, so it will not appear here — that is Phase 03's
job, not this one.)

Record the before count (3 files) and after count (1 file) in the log.

## Do NOT

- Do not delete anything outside the five files listed above.
- Do not "tidy up" `RealFlashTransferRepository.kt` while you are here. Its
  `android.util.Log` calls belong to Phase 03.
- Do not delete `core/transfer/src/main/java/.../protocol/WsTransferMessages.kt` or
  `model/WsTransferModels.kt`. Despite the `Ws` prefix these are the **live** transfer
  protocol, not legacy. Deleting them breaks the wire format (CONVENTIONS.md R8).

## Completion checklist

- [ ] Step 1 grep returned no external references
- [ ] Step 2 API-surface check done; ADR appended if any public symbol was removed
- [ ] Exactly 5 files deleted
- [ ] `build.gradle.kts` dependency check done and result recorded
- [ ] Build + tests pass
- [ ] Post-deletion grep confirms no `android.*` imports remain in `core/transfer/src/main`
- [ ] Committed as one commit
- [ ] Log entry appended

## Rollback

```bash
git revert <commit-sha>
```
