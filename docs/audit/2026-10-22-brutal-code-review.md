# Brutal Code Review — 2026-10-22 (scope `dde95a17..6861ac61`)

> **Fix status (2026-10-22, `dev`; unit-tested, NOT device-verified, NOT deployed):**
> **F1 fixed** — `usage()`, `oldestAgeMs()`, `oldestAgesMs()` in `MailboxQueue` filter `undeliverable_reason IS NULL`; `mailbox_totals` (and therefore
> `totals()`) still counts the rows on disk, as the review demands, because the SQLite triggers in `MailboxSchema` own that number and are never
> touched by `markQuarantined()`. Three new tests in `MailboxQuarantineTest` (recipient quota, sender quota, and the stats/oldest-age pair, which also
> pins that the on-disk totals still count the rows).
> **F2 fixed** — the cursor payload is length-framed (`v1|<len>:<foldedName>|<deviceId>`), UTF-8 explicit, and an unframed cursor written by an older
> hub build is still parsed so a page in flight during an upgrade does not start answering 400. Two new tests in `DirectoryPrivacyTest` (unit
> round-trip, and a two-page walk over a colleague named `Alice | Ops` with no duplicate or loop).
> **F3 fixed** — `WebSocketCodec` rents the payload buffer through `ByteArrayPool.use`, so a frame cut short by EOF or a timeout returns it.
> New test pinning the pool identity (`WebSocketCodecTest`).
> **F4 fixed** — `RenewalPolicy.isRenewable`/`shouldRenew` derive their windows from `cert.validTo - cert.validFrom` when the caller passes none, which
> is the same derivation `EnrollmentService` already used; an explicit window still wins, so the hub's configured overrides keep their meaning. Test in
> `CertChainTest` plus two `HubSessionTest` cases (a 365-day certificate starts renewing 36.5 days early; an expired long-lived one is renewed inside
> its own grace instead of failing locally).
> **F5 fixed** — `cached = null` moved inside `mutex.withLock` in `HubSession.renew()`.
> **F6 fixed** — `CachedMailboxStats.oldestAgeMs(deviceId)` is answered from the snapshot (+ drift, bounded like `oldestAgesMs`), never from the
> delegate; the test double now makes a delegate call an error.
> **F7 — no change**, correctly: the review itself calls it a nit and states the `accumulatedRemovedMemberIds.removeAll(accumulatedMembers.keys)` line
> is defensive, not a defect. Nothing verified against the Flash server contradicts that.
> **F8 — the review's diagnosis is wrong; the symptom is real and is fixed.** See §3.
> **Found while running the suite, fixed:** `CachedMailboxStatsTest.aStaleReadReturnsTheOldNumbersAtOnceWhileOneRefreshRuns` asserted an asynchronous
> counter (`db.loads == 2`) the instant its reader thread returned. The refresh runs on the cache's own scope, so the assertion raced it
> (`expected:<2> but was:<1>`, seen once in a full `:server:test` run). The test now waits for the refresh to enter the delegate, then asserts it was
> entered exactly once — the intent ("one refresh, not twenty") is unchanged. Recorded as **ERROR-741**.

Scope reviewed by the reviewer: `dde95a17..6861ac61` on `dev` (the 10-step Server/Hub internals audit plus the earlier review fixes). This document is
the fixing agent's record: what each finding claimed, what the code actually does, what changed, and what is still owed. Findings keep the reviewer's
numbering (`F1`..`F8`). The 2026-10-21 review at `docs/audit/2026-10-21-brutal-code-review.md` uses the same numbers for different findings — every
code comment written by this round says "2026-10-22 review Fn" to keep them apart.

## 1. Verdict

All six defects were real; one diagnosis (F8) was wrong and one item (F7) needed no change. Three of them mattered:

| ID | Reviewer's severity | Verified? | What was actually wrong | Verdict |
|---|---|---|---|---|
| F1 | HIGH | **confirmed** | `usage()` counts quarantined envelopes, so 1,000 unopenable rows lock a device out of its own mailbox for the whole 7-day TTL | FIXED |
| F2 | MEDIUM | **confirmed** | the people cursor splits on `\|`, so a name containing `\|` repeats on every page (a paging loop, not just a duplicate) | FIXED |
| F3 | MEDIUM | **confirmed** | an exception mid-payload leaks the pooled buffer; 8 such drops empty the bucket (capacity 8) | FIXED |
| F4 | LOW | **confirmed** | the client's renewal window was hardcoded (24 h / 72 h) while the hub derives it from the certificate's lifetime | FIXED |
| F5 | LOW | **confirmed** | `cached = null` outside the mutex: a coroutine next in line can read the old serial's token | FIXED |
| F6 | LOW | **confirmed** | `MailboxStats by delegate` left `oldestAgeMs(deviceId)` reaching SQLite on the caller's thread | FIXED |
| F7 | NIT | confirmed as "not a defect" | tombstone accumulation is defensive hygiene against non-Flash hubs | NO CHANGE |
| F8 | MEDIUM | **diagnosis wrong** | the test never reached a port collision: the server ignored the port it was given | FIXED (differently) |

Nothing in the review's §4 (security) or §6 (dependencies) needed action: `SEC-02..05` were already verified clean by the reviewer, and this round
changed no dependency.

## 2. Per-finding record

### F1 — quarantined rows exhausted the per-device mailbox quota

`markQuarantined()` sets `undeliverable_reason`; `scanRows()` skips such rows, so the recipient never acknowledges and never deletes them. Three
queries still counted them: `usage()` (the per-recipient and per-sender quota check), `oldestAgeMs(deviceId)` and `oldestAgesMs(limit)`.

The fix is the review's `AND undeliverable_reason IS NULL` on all three. Deliberately **not** changed:

- `markQuarantined()` does not touch `mailbox_totals` (the review is right: the `mailbox_totals_ai/ad` triggers own it, and a manual decrement is
  double-counted when the TTL sweep deletes the row).
- `totals()`/`queuedCount()` keep counting quarantined rows. Those rows still hold disk, and the global budget is a disk budget; only the
  per-device "how much can this device still receive" question changes. This split is stated in the KDoc so the next reader does not "fix" it back.
- `pendingCount(target)` inherits the filter (it calls `usage()`), which is the right answer for its only meaning ("still deliverable"); its only
  callers are server tests.

### F2 — a display name containing `|` broke the people cursor

Confirmed exactly as described (`split("|", limit = 3)` cuts `alice | ops` in half, and `OrganizationDirectory.searchPeople` then re-emits the row on the
next page). Fixed with the review's length-prefix framing plus an explicit `Charsets.UTF_8` on both ends.

One addition the review did not ask for: `decodeCursor` still accepts the old unframed shape. A hub upgraded under a client that is mid-pagination would
otherwise answer `400` to a cursor it issued itself a second earlier. The ambiguity of the old shape is unresolvable, which is the defect; new cursors
are unambiguous, and a legacy cursor can at worst repeat one row once.

### F3 — a frame cut short leaked its pooled buffer

Confirmed: `readFullyInto` throws before `ByteArrayPool.release(pooled)`, and `MAX_POOL_CAPACITY` is 8 per bucket, so eight unexpected disconnects
emptied the 16 KiB bucket for the life of the process. The payload is now rented via `ByteArrayPool.use(length)` and the exact copy is taken inside the
block (a received payload is never released by a caller, which is why the copy must stay). The new test clears the pool, marks one 16 KiB array, feeds a
truncated 1,000-byte binary frame, and asserts the very same array comes back out of `acquire`. The JVM codec has no pool and was not touched (the
review says the same).

### F4 — the client used a renewal window the hub does not use

Confirmed for both entry points: `HubSession.renewLocked` (the gate that stops a renewal from ever being attempted) and
`EnrollmentCredentials.needsRenewalAt` (the proactive-renewal decision), both of which reached `RenewalPolicy`'s hardcoded defaults while
`EnrollmentService` passed `earlyWindowFor(lifetime)`/`graceFor(lifetime)` explicitly.

`RenewalPolicy.isRenewable`/`shouldRenew` now take nullable windows: null means "derive from this certificate's lifetime", an explicit value still
wins (the server keeps passing its configured overrides, so its behaviour is byte-for-byte unchanged). Tests: `CertChainTest` (365-day certificate:
renewable 18 days after expiry, not 19; an explicit 3-day grace refuses at +4 days) and two `HubSessionTest` cases (expired 5 days ago on a ~380-day
certificate → renewed; 5 days left on a 365-day certificate → `shouldRenew` true).

Test-authoring note for the next agent: the test organisation's delegation lives `T0 - 1 day .. T0 + 400 days`, and `checkChain` refuses a leaf that
outlives its delegation (`LEAF_OUTSIDE_DELEGATION`) as well as a delegation past its own window (`DELEGATION_EXPIRED`). A "certificate expired N days
ago" test therefore has to keep both the old leaf and the new one inside the delegation.

### F5 — the cached bearer token could outlive its certificate

Confirmed: `bearer()` reads `cached` inside the lock, `renew()` cleared it after releasing it. A waiting coroutine could take the lock in that gap and
hand out a token bound to the retired serial. The assignment is now inside the same `mutex.withLock` block as the renewal.

### F6 — one stats method still blocked on SQLite

Confirmed: the class delegates `MailboxStats by delegate` and overrode only three of the five methods. `oldestAgeMs(deviceId)` now comes from the
snapshot, with the same bounded drift `oldestAgesMs` applies. The test double throws from that method, so a regression is a test failure rather than a
slow path. There is no production caller of `oldestAgeMs(deviceId)` today — this was a latent contract violation, not a live stall.

### F7 — no change

The reviewer's own text says the tombstone accumulation is defensive, not a defect. Verified: `OrganizationDirectory` emits `removedDeviceIds` on the
first page only, and `OrgDirectorySync` keeps the accumulation as protection against a hub that behaves differently. Changing it would remove a
safety net to satisfy a nit. No code, no test, nothing owed.

### F8 — the review's cause is wrong (port reuse); the failure is real

The review says: "restarting `serverNetwork` with port 0, the OS frequently reallocates the exact port just closed", and prescribes holding a dummy
socket on `oldPort` or retrying until the port differs.

That is not what the code does. `WsTransferServer.start()` took no port at all:

```kotlin
public fun start(): Int {
    if (isRunning) return listenPort
    val socket = runCatching {
        ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PREFERRED_PORT)) }
    }.getOrElse { ServerSocket(0) }
    ...
}
```

Every start bound `PREFERRED_PORT` (45822) and fell back to an ephemeral port only when 45822 was taken. `FlashNetwork.start(listenPort)` passed the
value and the server dropped it, so `network.start(0)` **always** returned 45822 — unless another test process already held it, in which case it
returned some other ephemeral port. The retry loop the reviewer's fix implies therefore could not work: it would restart the server ten times and get
45822 ten times (tried, and that is exactly what it printed: `the test needs a different port (got 45822 ten times)`). The failure is
environment-dependent for the same reason — it fails when 45822 is free (the "new" port equals the old one) and passes when something else holds it.

Fixed properly, in the two places that own the contract:

- `WsTransferServer` (Android **and** JVM) gained `start(preferredPort: Int)`; `start()` is now `start(PREFERRED_PORT)`, so both the source and binary
  signature of the no-argument form are unchanged and the "preferred port, ephemeral fallback" behaviour is intact.
- `WsFlashNetwork.start(listenPort)` and `JvmWsFlashNetwork.start(listenPort)` pass a positive `listenPort` through and keep calling `start()` for `0`,
  i.e. production (every host starts with `0`) is unchanged.
- The test now asks for `oldPort + n` and asserts the server really listens there; a new test (`testAnExplicitlyRequestedPortIsThePortTheHostListensOn`)
  asks the kernel for a free port, requests it, and then dials it, so the port contract is pinned without depending on what the OS does with 45822.

Because the test requested a different port only by accident before, the redial retarget test now proves what it claims: the redial has to leave the
stale route, because the stale port is genuinely gone.

Same pattern, deliberately not changed: `LanProbeServer.createServerSocket()` (the legacy plain-TCP LAN transport behind `DefaultFlashNetwork`) also
ignores the `listenPort` that `DefaultFlashNetwork.start` is given, and `DefaultFlashNetworkTest` calls the no-argument form. It is the old LAN path,
no test depends on the port, and touching it is a separate decision; recorded here so it is not lost.

## 3. Verification

```
:server:test                              650 tests, 0 failures (new F1 tests; the de-raced CachedMailboxStatsTest)
:core:hub-protocol:jvmTest                124 tests, 0 failures (F4)
:core:hub-protocol:testAndroidHostTest    118 tests, 0 failures
:core:enterprise:jvmTest                  268 tests, 0 failures (F4, F5)
:core:enterprise:testAndroidHostTest      159 tests, 0 failures
:core:network:testAndroidHostTest         406 tests, 0 failures (F3, F8)
:core:network:jvmTest                     313 tests, 0 failures (the JVM server's new overload)
:app:compileDebugKotlin                   green
:desktop:compileKotlinJvm                 green
```

Mutation checks. Each edit was applied, the named test was run and failed, then the edit was reverted and the whole set above re-run green:

| Finding | Mutation | Caught by |
|---|---|---|
| F1 | `usage()` without `AND undeliverable_reason IS NULL` | `MailboxQuarantineTest`: `quarantinedRowsDoNotCountAgainstTheRecipientQuota`, `quarantinedRowsDoNotCountAgainstTheSendersQuota`, `quarantinedRowsAreNotWaitingInTheStatsAndStillCountOnDisk` (3 failed / 9) |
| F2 | `decodeCursor` always takes the legacy `split("|", limit = 3)` path | `DirectoryPrivacyTest`: `theCursorSurvivesAVerticalBarInTheDisplayName`, `aDisplayNameWithAVerticalBarPagesWithoutDuplicatesOrLoops` and `followingTheCursorWalksTheWholeDirectoryExactlyOnce` (3 failed / 15) |
| F3 | `acquire` … `release` again with the release after `readFullyInto` | `WebSocketCodecTest > a frame cut short mid payload still gives its pooled buffer back (review F3)` (1 failed / 23) |
| F4 | `isRenewable`/`shouldRenew` back to the hardcoded 24 h / 72 h defaults | `CertChainTest.renewalWindowGrowsWithTheCertificatesOwnLifetime` and `HubSessionTest.aLongLivedCertificateIsRenewedInsideItsOwnGraceWindow` (2 failed / 24 in `HubSessionTest`) |
| F6 | the `oldestAgeMs` override deleted, so `by delegate` answers | `CachedMailboxStatsTest.oneDevicesOldestAgeComesFromTheSnapshotAndGrowsWithIt` (1 failed / 6) |
| F8 | `bind(InetSocketAddress(PREFERRED_PORT))` inside the parameterised `start` | `testRedialLoopFollowsADiscoveredRouteAfterTheOldOneWentStale` **and** `testAnExplicitlyRequestedPortIsThePortTheHostListensOn` (2 failed / 11) — direct proof of §F8's diagnosis |

**F5 has no mutation check**, and deliberately so: the defect is a scheduling window between releasing the mutex and clearing `cached`, so no test can
open that window deterministically (a waiter either sees the cleared field or is still blocked). The change is a two-line scope move, reviewed rather
than pinned, and the existing renewal/token tests still pass. Anyone who wants it pinned will need a seam (e.g. an injectable `onAfterRenew` hook), and
that would be added for the test's sake alone.

Not run: no device, no emulator, no deployment. Nothing here changes a wire shape (the cursor is opaque to clients and its bytes only ever travel
between a hub build and itself), so no protocol document changed; the only client-visible behaviour change is that a long-lived device certificate is
now renewed instead of being declared invalid.

## 4. What is still owed

- `RVW22-01..04` in `docs/testing/TEST-BACKLOG.md` §4 (device/manual checks).
- An owner decision, if it is ever wanted: should `LanProbeServer`/`DefaultFlashNetwork.start(listenPort)` honour an explicit port too (§F8 above)?
- Nothing else: the review's §9 "missing tests" list is covered by the tests named in §2, and §10's fix plan is implemented except the part of its
  step 4 that is based on the wrong diagnosis.
