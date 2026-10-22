# Brutal Code Review: Full-Subsystem Multi-Module Audit

**Date:** 2026-10-22  
**Scope:** Full repository audit across all core subsystems and UI modules (`core:calling`, `core:ptt`, `server`, `ui:callui`, `ui:platform-shims`, `core:discovery`, `core:radio`, `core:transfer`, `core:engine`, `core:swarm`, `desktop`, `ui:chat`, `core:messaging`, `core:enterprise`, `core:security`).  
**Stack Detected:** Kotlin Multiplatform (Kotlin 2.2.10, AGP 9.3.1, Compose Multiplatform BOM 2025.12.00, Room 2.8.4, SQLite/SQLCipher, Ktor Server & Client 3.x, WebRTC-KMP vendored composite build).  
**Deterministic Test Executions:**
- `:core:common:jvmTest` (BUILD SUCCESSFUL, 6s)
- `:core:ptt:jvmTest` (BUILD SUCCESSFUL, 16s)
- `:core:calling:jvmTest` (BUILD SUCCESSFUL, 11s)
- `:core:transfer:jvmTest` (BUILD SUCCESSFUL, 19s)
- `:core:security:jvmTest` (BUILD SUCCESSFUL, 25s)

---

## 1. Verdict

The codebase possesses strong low-level cryptographic primitives and protocol framing, but contains severe state machine, concurrency, and lifecycle flaws that cause silent data loss, audio deafness, and privilege escalation in multi-peer and desktop environments. The single biggest risk is unrecoverable data loss in `HubChatBridge.kt` where transient directory lookup or keystore errors permanently purge unread sealed messages from the hub mailbox. **This is NOT safe to ship** until the critical message purging, admin demotion, and relay connection races are resolved.

---

## 2. Critical Issues

### [F1] [CRITICAL] Inbound Sealed Message Purged on Transient Directory/Keystore Errors
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt:428-435`, `396-403`, and `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/DirectoryHubSealing.kt:90-95`
- **Evidence:**
  ```kotlin
  // DirectoryHubSealing.kt:90-95
  override suspend fun open(senderDeviceId: String, msgId: String, sentAt: Long, payload: String): UnsealedPayload? {
      val key = ownKey() ?: return null
      if (identityMismatch(senderDeviceId)) return null
      val sender = lookup(senderDeviceId)?.subjectPublicKey?.let { runCatching { Base64.decode(it) }.getOrNull() } ?: return null
      return HubSealing.open(key, sender, senderDeviceId, localDeviceId, msgId, sentAt, payload)
  }
  // HubChatBridge.kt:428-435, 401
  if (sealed) {
      val opened = sealing?.open(d.from, d.id, d.sentAt, d.payload)
      if (opened == null) {
          if (sealing != null && standing(d.from) == HubPeerStanding.UNAVAILABLE) return HubInboundOutcome.RETRY_LATER
          _droppedUnverified.update { it + 1 }
          return reject(d, "sealed message did not verify")
      }
  ...
  HubInboundOutcome.REJECTED -> port.recvAck(d.id, d.from)
  ```
- **Why it matters:** If `ownKey()` is null (keystore locked or initializing) or `lookup()` fails transiently due to a network glitch while `standing(d.from)` is cached as `MEMBER`, `sealing?.open()` returns null. Line 432 evaluates to false and line 434 returns `REJECTED`. In line 401, `REJECTED` executes `port.recvAck(d.id, d.from)`. The hub mailbox marks the unread message delivered and permanently deletes it. The user never sees the message and it cannot be recovered.
- **Fix:** Distinguish between crypto verification failures (bad signature/tampering) and transient key/directory lookup errors. If `ownKey() == null` or directory lookup threw/timed out, return `HubInboundOutcome.RETRY_LATER` to keep the envelope in the mailbox.

### [F2] [CRITICAL] Regular Group Members Can Demote Group Admins
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSignatureRules.kt:96-99`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/SignedGroups.kt:880-882, 954`
- **Evidence:**
  ```kotlin
  // GroupSignatureRules.kt:96-99
  // A regular member can only add an active non-admin member
  if (memberIssued) {
      if (cert.role != MemberCert.ROLE_MEMBER || !cert.active) return "issuer-privilege"
  }
  // SignedGroups.kt:880-882
  } else if (!cert.active || cert.role != MemberCert.ROLE_ADMIN) {
      if (adminKeys.remove(cert.subjectId) != null) adminKeysVersion++
  }
  ```
- **Why it matters:** While `adminIssued` enforces rule R-13 preventing admins from altering other admins or the founder (`cert.subjectId != cert.issuerId && adminLookup != null`), `memberIssued` only checks `cert.role != MemberCert.ROLE_MEMBER || !cert.active`. In a group where members may invite peers (`charter.membersMayAdd == true`), a regular member can issue an active certificate for an existing admin with `role = ROLE_MEMBER`. `checkCert` accepts it, `SignedGroups` removes the admin from `adminKeys`, and Room updates their row to regular member, allowing plain members to seize control of groups.
- **Fix:** In `GroupSignatureRules.kt`, add admin and founder protection to `memberIssued`:
  ```kotlin
  if (memberIssued) {
      if (cert.role != MemberCert.ROLE_MEMBER || !cert.active) return "issuer-privilege"
      if (cert.subjectId == charter.ownerId) return "issuer-privilege"
      if (adminLookup != null && adminLookup(cert.subjectId) != null) return "issuer-privilege"
      if (knownKey != null) return "issuer-privilege"
  }
  ```

### [F3] [CRITICAL] Stale Credentials / Renewal Race in `HubSession.bearer()` and `renew()`
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt:148`, `243`
- **Evidence:**
  ```kotlin
  override suspend fun bearer(forceRefresh: Boolean): String? = withContext(ioDispatcher) {
      val creds = credentials() ?: return@withContext null
      val seen = generation
  ...
      mutex.withLock {
  ...
          val signedIn = withTimeoutOrNull(NETWORK_BUDGET_MS) { authenticate(creds) }
  ```
- **Why it matters:** Coroutine A reads `credentials()` outside the lock, obtaining certificate serial 1. Before Coroutine A enters `mutex.withLock`, Coroutine B executes `renew()`, gets certificate serial 2, saves it to `trustStore`, and invalidates `cached`. Coroutine A then enters `mutex.withLock` and calls `authenticate(creds)` with stale serial 1. The hub rejects serial 1 as superseded/expired, Coroutine A sets `_authFailure.value = HubAuthFailure.CERT_EXPIRED`, and all active hub operations fail despite a valid renewed certificate existing.
- **Fix:** Move `credentials()` evaluation inside `mutex.withLock` in both `bearer()` and `renew()`.

### [F4] [CRITICAL] Recurrence of ERROR-397 Connection Kill Race on `FlashRelayClient.hubUrl` Mutation
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt:77-80`
- **Evidence:**
  ```kotlin
  public var hubUrl: String
      get() = hubUrlState.value
      set(value) {
          val old = hubUrlState.getAndUpdateCompat(value)
          if (old != value && running) {
              scope.launch { runCatching { transport.disconnect("Hub address changed") } }
              wake.trySend(Unit)
          }
      }
  ```
- **Why it matters:** `stopLocked()` in `FlashRelayClient` tracks `pendingDisconnect` under `lifecycleLock` to prevent `ERROR-397` (where an old disconnect kills a freshly dialled session). In `hubUrl.set`, an un-tracked `scope.launch { transport.disconnect(...) }` is spawned while `wake.trySend(Unit)` is called immediately. The connection loop wakes up, starts dialling the new hub, and while the new handshake is in flight, the un-tracked coroutine executes `disconnect()`, aborting the brand-new connection.
- **Fix:** Guard `hubUrl.set` with `withRelayLock(lifecycleLock)`, assign the launched disconnect job to `pendingDisconnect`, and join it before dialling.

### [F5] [CRITICAL] Swarm Piece Request Black Hole Stalls Transfers on Byte Budget Exhaustion
- **Location:** `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServePolicy.kt:138-146, 208-219`, `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServeHandler.kt:70-107`
- **Evidence:**
  ```kotlin
  // ServePolicy.kt:138-144
  var byteBudget = maxOutstandingBytesPerRequester - currentOutstandingBytes
  if (byteBudget <= 0) return ServeEvaluation.Rejected(...)
  ...
  // ServePolicy.kt:208-219
  val toServe = ArrayList<Int>()
  for (p in heldPieces) {
      if (byteBudget >= pieceSize) {
          toServe.add(p)
          byteBudget -= pieceSize
      }
  }
  return ServeEvaluation.Accepted(piecesToServe = toServe)
  ```
- **Why it matters:** If `0 < byteBudget < pieceSize` (e.g. 32 KiB budget remaining for a 64 KiB piece), Step 7 passes because `byteBudget > 0`. Step 9 fails because `byteBudget < pieceSize`. `toServe` is empty and `rejectedPieces` is empty. `ServeHandler.kt` emits zero commands: neither `ReadPiece` nor `SwarmFrame.Reject`. The requester keeps the pieces charged to `inFlightBytes` and cannot request them from other peers. The transfer stalls until the 5–20s request timeout fires, thrashing the requester's congestion window and penalizing the peer with a 5-second backoff.
- **Fix:** If `toServe.isEmpty()` and pieces were unserved due to budget, return `ServeEvaluation.Rejected(SwarmRejectReason.BUSY, retryAfterMs = 1000L)`. Partition unserved pieces in batch requests into `rejectedPieces`.

### [F6] [CRITICAL] Multi-Pane / Desktop Draft State Leaks and Corrupts Across Conversations
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:415-431`
- **Evidence:**
  ```kotlin
  var draft by remember { mutableStateOf("") }
  ...
  LaunchedEffect(state.draftText) {
      if (draft.isEmpty() && state.draftText.isNotEmpty()) {
          draft = state.draftText
      }
  }
  val latestDraft by rememberUpdatedState(draft)
  DisposableEffect(Unit) {
      onDispose { onPersistDraft(latestDraft) }
  }
  ```
- **Why it matters:** In dual-pane desktop/tablet layouts, selecting another conversation recomposes `FlashConversationScreen` with a new `state`. Because `remember { mutableStateOf("") }` has no key, `draft` retains the text typed for the previous conversation. `draft.isEmpty()` is false, so the new conversation's draft is ignored. When the screen disposes, `latestDraft` from Chat A is persisted into Chat B's database row, overwriting Chat B's draft.
- **Fix:** Key `draft` and `DisposableEffect` on `state.header.id`:
  ```kotlin
  var draft by remember(state.header.id) { mutableStateOf(state.draftText) }
  DisposableEffect(state.header.id) {
      onDispose { onPersistDraft(latestDraft) }
  }
  ```

### [F7] [CRITICAL] Negative Monotonic Clock on Desktop JVM Rejects Heartbeats and Kills PTT Sessions
- **Location:** `core/ptt/src/jvmMain/kotlin/com/transfer/flash/core/ptt/PttPlatform.jvm.kt:3`, `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:664-668`, `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/protocol/PttSessionCodec.kt:68`
- **Evidence:**
  ```kotlin
  // PttPlatform.jvm.kt:3
  public actual fun pttElapsedRealtimeMs(): Long = System.nanoTime() / NANOS_PER_MILLI
  // PttSessionCodec.kt:68
  val sentAt = fields.long("sentAt")?.takeIf { it > 0 } ?: return null
  ```
- **Why it matters:** Per Java specification, `System.nanoTime()` origin is arbitrary and frequently negative on Linux/Windows. When negative, `sentAt` is encoded as a negative integer. On decode, `it > 0` returns null and drops the packet. `RemoteActivity` is never recorded. After 5000ms (`heartbeatTimeoutMs`), the session aborts with `"Lost peer — listening stopped"`.
- **Fix:** Anchor `System.nanoTime()` to a strictly positive baseline offset using monotonic time.

### [F8] [CRITICAL] Distributed Floor Collision Permanently Mutes Third-Party Listeners
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt:248-276, 342-343`, `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:332`
- **Evidence:**
  ```kotlin
  // In reduceListening (lines 342-343):
  // A second floor claim while busy is ignored (no preemption in v1).
  else -> PttTransition(state, emptyList())
  ```
- **Why it matters:** Devices A and B press PTT simultaneously (`holderId="A" < "B"`). Listener C receives B's `Start` first and transitions to `Listening(B)`. C receives A's `Start` 5ms later; `reduceListening` discards it. Device B receives A's `Start`, yields to A (`"A" < "B"`), and sends `Stop(B)`. C receives B's `Stop`, transitions to `Idle`, and stops playout. Device A continues talking, but C is in `Idle` and drops all of A's audio packets. C remains completely deaf to A for up to 60 seconds.
- **Fix:** In `reduceListening`, track incoming claims within `collisionWindowMs`. If a claim arrives from a lower `holderId`, or current holder stops within the collision window, switch listening to the winning holder.

### [F9] [CRITICAL] Microphone Audio and Session Control Broadcast to Self and Untrusted Peers in `startTalk`
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:474-481, 520-521, 688`
- **Evidence:**
  ```kotlin
  // PttSessionEngine.kt:474-481 in startTalk()
  val freshMembers = snapshotMembers()
  members = freshMembers
  val announcedMembers = members.filter { sendControl(it, start) }
  members = announcedMembers
  // PttSessionEngine.kt:688 in senderLoop()
  members.forEach { sendAudio(it, frame) }
  ```
- **Why it matters:** Unlike `sendPing()` (line 207), which filters `it != self && isTrustedPeer(it)`, `startTalk()` accepts raw `snapshotMembers()`. Live microphone PCM frames and session controls are transmitted to unverified peers and looped back to self, violating Rule 19.
- **Fix:** Filter `snapshotMembers()` with `it != self && isTrustedPeer(it)` in `startTalk()`.

### [F10] [CRITICAL] Video Surface Desynchronization and Permanent Playback Freeze on URI Change
- **Location:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.android.kt:60-122`
- **Evidence:**
  ```kotlin
  DisposableEffect(uri) {
      onDispose {
          videoViewRef[0]?.stopPlayback()
          videoViewRef[0] = null
          isPreparedRef[0] = false
      }
  }
  AndroidView(
      factory = { ctx -> VideoView(ctx).apply { ... setVideoURI(resolved) } },
      modifier = modifier,
  )
  ```
- **Why it matters:** `AndroidView` lacks an `update` lambda and is not wrapped in `key(uri)`. When `uri` changes in `FlashMediaViewer`, `DisposableEffect(uri)` runs `onDispose`, setting `videoViewRef[0] = null`. Because `factory` never runs again, `videoViewRef[0]` remains null forever. All subsequent seek and play effects short-circuit, freezing video playback permanently.
- **Fix:** Wrap `AndroidView` in `key(uri) { ... }` or add an `update` lambda reloading the URI.

### [F11] [HIGH] Backward Secrecy Broken & Re-admitted Members Permanently Refused Group Secrets
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:4046-4065`
- **Evidence:**
  ```kotlin
  val rotations = groupRotationDao?.getAllForGroup(frame.groupId).orEmpty()
  val isRemoved = rotations.any { rot ->
      rot.removedIds.split(',').map { it.trim() }.contains(peerDeviceId)
  }
  if (isRemoved) return
  val record = secretStore.get(frame.groupId, frame.epoch) ?: return
  ```
- **Why it matters:** 1. A new member joining at epoch 10 can request epoch 1; lines 4057-4064 serve the past secret, breaking backward secrecy. 2. A member removed at epoch 2 and re-admitted at epoch 5 is permanently blocked from obtaining epoch 5+ secrets because epoch 2's rotation remains in `group_rotations`.
- **Fix:** Enforce `frame.epoch >= member.joinedEpoch`, and check that `peerDeviceId` is only blocked if present in `removedIds` of a rotation that occurred *after* their latest membership certificate.

### [F12] [HIGH] Group Outbox Stuck Retrying Forever & Removed Members Continually Sent Inbound Group Text
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6394-6435, 3597-3605`
- **Evidence:**
  ```kotlin
  val pending = deliveries.pendingForMessage(item.localId)
  if (pending.isEmpty()) {
      messageDao.updateStatusIfUnacknowledged(item.localId, "DELIVERED")
      outboxDao.delete(item.localId)
      return null
  }
  ```
- **Why it matters:** Unlike `drainGroupHubFile:6342`, `drainGroupMessage` does not filter out inactive members (`isActive == false`). Pending deliveries for removed members are never pruned, causing the outbox loop to retry until 7-day TTL expiry, leaving messages stuck in "SENT" single-checkmark state. On inbound, line 3597 does not check `isRemovedHere(members, frame.groupId)`, so kicked members continue receiving and persisting group texts.
- **Fix:** Filter pending deliveries by active members in `drainGroupMessage`, and check `isRemovedHere` in `onInboundGroupFrame`.

### [F13] [HIGH] Crash via `UninitializedPropertyAccessException` on Corrupt Completed Upload
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:241`, `487-495`
- **Evidence:**
  ```kotlin
  if (stored?.completed == true) return HubUploadOutcome.Done(ticketOf(stored))
  ...
  private fun ticketOf(s: UploadState): HubFileTicket = HubFileTicket(
      fileId = s.fileId,
      key = HubFileKey.decode(s.keyText) ?: key,
  ```
- **Why it matters:** If `s.keyText` is corrupt, line 489 falls back to `?: key`. At line 241, `key` is uninitialized. Accessing it throws `UninitializedPropertyAccessException`, crashing the application.
- **Fix:** Decode `key` safely before calling `ticketOf`: `HubFileKey.decode(stored.keyText) ?: return fail(HubFileError.LocalStorage("state"))`.

### [F14] [HIGH] Premature `true` Return on `CONNECTING` & Concurrent Sockets on `AUTHENTICATING`
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/KtorRelayTransport.kt:104-106`, `117-122`
- **Evidence:**
  ```kotlin
  if (_state.value == RelaySessionState.CONNECTED || _state.value == RelaySessionState.CONNECTING) {
      return@withContext true
  }
  ```
- **Why it matters:** If Caller 2 calls `connect()` while Caller 1 is in `CONNECTING`, Caller 2 is returned `true` before connection succeeds. If called while Caller 1 is in `AUTHENTICATING`, line 104 is false, launching a second concurrent WebSocket connection and overwriting `activeSession`.
- **Fix:** Protect `connect()` with a mutex and await `welcomeDeferred` if a connection is in flight.

### [F15] [HIGH] Integer Overflow in `HubFileLayout.of()` Allows Negative Chunk Counts
- **Location:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubFileSealing.kt:105-113`
- **Evidence:**
  ```kotlin
  val plainChunk = (cipherChunkBytes - HubFileSealing.TAG_BYTES).toLong()
  val count = (plainBytes + plainChunk - 1) / plainChunk
  if (count > HubContract.MAX_FILE_CHUNKS) return null
  return HubFileLayout(plainBytes, cipherChunkBytes, count.toInt())
  ```
- **Why it matters:** If `plainBytes` is near `Long.MAX_VALUE`, `plainBytes + plainChunk - 1` overflows to a negative integer. `count` becomes negative and passes `count > MAX_FILE_CHUNKS`, creating a `HubFileLayout` with negative chunk count.
- **Fix:** Check `if (plainBytes <= 0L || plainBytes > plainChunk * HubContract.MAX_FILE_CHUNKS) return null`.

### [F16] [HIGH] Windows Reserved Device Name Bypass on Component Truncation
- **Location:** `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashPathSanitizer.kt:47-53, 100-114`
- **Evidence:**
  ```kotlin
  val safe = if (isReservedDeviceName(replaced)) "_$replaced" else replaced
  return truncatePreservingExtension(safe)
  ```
- **Why it matters:** For an input like `"con" + " ".repeat(120) + "xyz.txt"`, `isReservedDeviceName` returns false before truncation. Truncation trims spaces and cuts off `"xyz"`, producing `"con.txt"`. On Windows, `con.txt` accesses the console driver or throws OS errors.
- **Fix:** Run `isReservedDeviceName` *after* `truncatePreservingExtension`. Add `"CLOCK$"` to `WINDOWS_RESERVED_NAMES`.

### [F17] [HIGH] Targeted Transfer Stream Leaks to Arbitrary Peer on Missing Active Session
- **Location:** `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:2098-2102`
- **Evidence:**
  ```kotlin
  val session = (peerId?.let { network.activeSessions.value[it] }
      ?: network.activeSessions.value.values.firstOrNull()) ?: return null
  val resolvedPeerId = peerDeviceId ?: session.peerDeviceId.value
  ```
- **Why it matters:** When `peerDeviceId = "peer-A"` is not in `activeSessions`, line 2100 falls back to `values.firstOrNull()` (e.g. `peer-B`). Line 2101 sets `resolvedPeerId = "peer-A"`. The stream encrypts data with Peer A's key and sends it over Peer B's socket. (Fixed on Android under finding F1 in `DiscoveryEngineHolder`, but missed on desktop).
- **Fix:** Only fall back to `firstOrNull()` when `peerDeviceId == null`.

### [F18] [HIGH] Session Displacement Race Strands In-Flight Messages, Causing Lost ACKs, Out-of-Order Delivery, and Replays
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/relay/RelayConnectionPool.kt:47-58`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt:262-282`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:712-743`
- **Evidence:**
  ```kotlin
  // RelayConnectionPool.kt:54-57
  scope.launch {
      old.offer(RelayFrameCodec.encode(RelayFrame.Kick(reason = KICK_SUPERSEDED)))
      old.closeGracefully("superseded", CloseReason.Codes.NORMAL)
  }
  ```
- **Why it matters:** When a client reconnects on Connection 2, Connection 1's socket hang delays `closeGracefully` for up to 10 seconds. In-flight messages in `old.inFlight` are not spilled until `sessionEnded(old)` runs. During this 10-second gap, ACKs sent on Connection 2 are dropped, and live messages bypass in-flight messages. When Connection 1 finally closes, old messages are spilled and replayed out-of-order.
- **Fix:** Extract and spill `old.inFlight` immediately in `register()` before launching the graceful socket shutdown.

### [F19] [HIGH] Message Retries Swallowed by Un-swept Expired Mailbox Rows Before TTL Sweep
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt:144-152, 203-211, 287`
- **Evidence:**
  ```kotlin
  conn.prepareStatement(
      "DELETE FROM mailbox_envelopes WHERE origin_device_id = ? AND msg_id = ? AND target_device_id = ? AND undeliverable_reason IS NOT NULL",
  ).use { ... }
  existingSeq(conn, m)?.let { return EnqueueResult.DuplicateQueued(it) }
  ```
- **Why it matters:** Line 145 does not delete expired rows (`expires_at <= now`). `existingSeq` selects `seq` without filtering `expires_at > now`. Retried messages return `DuplicateQueued(oldSeq)`. When the recipient connects, `scanRows` filters `expires_at > now`, so the recipient never gets the message. `TtlSweeper` deletes the expired row, resulting in permanent message loss.
- **Fix:** In `insert()`, delete rows where `undeliverable_reason IS NOT NULL OR expires_at <= ?`.

### [F20] [HIGH] Silent Permanent Discovery Drop on Resolve Failure (Android < 34)
- **Location:** `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt:52-58`, `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt:458-470, 1100-1106, 1478-1481`
- **Evidence:**
  ```kotlin
  // NsdResolveQueue.kt:52-58
  override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
      if (isCurrentGeneration()) Log.w(tag, "NSD resolve failed name=${info.serviceName} error=$errorCode")
      finishResolve(info.serviceName, isCurrentGeneration)
  }
  ```
- **Why it matters:** `monitorWithResolveQueue` sets `monitoredServices[serviceName] = true`. On resolve failure, `onResolveFailed` never invokes `MonitorEvents`. `presenceTick()` skips the service because `monitorStarted == true`. The peer becomes an invisible ghost and is never discovered until process restart.
- **Fix:** Route `onResolveFailed` to `events.onRegistrationFailed()`, demoting `monitoredServices[name] = false` and scheduling retry.

### [F21] [HIGH] Missing Resolve Timeout Wedges Entire `NsdResolveQueue` Forever (Android < 34)
- **Location:** `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt:35-41, 43-74`
- **Evidence:**
  ```kotlin
  private fun nextResolveLocked(isCurrentGeneration: () -> Boolean): NsdServiceInfo? {
      if (!isCurrentGeneration() || resolvingServiceName != null || queue.isEmpty()) return null
  ```
- **Why it matters:** Android `NsdManager.resolveService()` frequently hangs indefinitely if the peer drops mid-resolve. Because there is no timeout, `resolvingServiceName` is never cleared. All subsequent discovered services queue up and are never resolved.
- **Fix:** Add a 6-second timeout to force `finishResolve()` if callbacks do not fire.

### [F22] [HIGH] Bluetooth `RfcommLink.read` Cannot Detect EOF and Can Cause Silent Deafness
- **Location:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:308-329`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/driver/KissTncDriver.kt:321-327`
- **Evidence:**
  ```kotlin
  val n = input.available()
  if (n > 0) {
      result = input.read(buffer, 0, minOf(n, buffer.size))
      break
  }
  ```
- **Why it matters:** When the remote radio disconnects, `input.available()` returns 0. `input.read()` is never called, so `result = -1` (EOF) is never returned. `KissTncDriver` loops forever, thinking it is still connected.
- **Fix:** Perform direct blocking `input.read()` on `Dispatchers.IO` and let socket closure unblock it.

### [F23] [HIGH] Missing `BLUETOOTH_SCAN` Runtime Check Silently Closes Radio Scanning
- **Location:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt:93-98, 170-175`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/BluetoothPermissions.kt:39`
- **Evidence:**
  ```kotlin
  // BluetoothPermissions.kt:39
  public fun mandatory(sdkInt: Int): List<String> = if (sdkInt >= API_SPLIT_PERMISSIONS) listOf(CONNECT) else emptyList()
  ```
- **Why it matters:** `missingPermissions()` only checks for `BLUETOOTH_CONNECT`. On Android 12+, calling `startDiscovery()` without `BLUETOOTH_SCAN` throws `SecurityException`, which line 170 catches and silently calls `close()`, producing zero devices with no error.
- **Fix:** Include `BLUETOOTH_SCAN` in mandatory permissions for API 31+.

### [F24] [HIGH] Active Group Call UI State (`_activeCall`) Wiped by Previous 1:1 Call Delayed Teardown
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt:636-641`
- **Evidence:**
  ```kotlin
  scope.launch {
      kotlinx.coroutines.delay(2_000L)
      if (currentSession == null) _activeCall.value = null
  }
  ```
- **Why it matters:** When a 1:1 call ends, a 2-second delay is launched to clear `_activeCall`. If the user starts or joins a group call within that window, `currentGroupSession != null` while `currentSession == null`. The delayed task wakes up and sets `_activeCall.value = null`, closing the group call UI while media continues in the background.
- **Fix:** Guard with `if (currentSession == null && currentGroupSession == null) _activeCall.value = null`.

### [F25] [HIGH] `CallHealthMonitor` Severe Thermal State Inverts Data Saver Precedence, Forcing Video Decode on Overheating Devices
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallHealthMonitor.kt:69-82`
- **Evidence:**
  ```kotlin
  if (thermal >= FlashThermalStatus.SEVERE) {
      return Verdict(warning = FlashCallHealthWarning.HOT, struggling = true, receiveCap = 1, acceptNew = false, showingFewer = true)
  }
  val struggling = thermal >= FlashThermalStatus.MODERATE || cpuHigh
  if (dataSaver) return Verdict(struggling = struggling, receiveCap = 0)
  ```
- **Why it matters:** If a user has `dataSaver = true` (`receiveCap = 0`), reaching `SEVERE` thermal status returns `receiveCap = 1` before checking `dataSaver`. Video starts receiving and decoding, worsening device overheating.
- **Fix:** Return `receiveCap = if (dataSaver) 0 else 1` in the `thermal >= SEVERE` branch.

### [F26] [HIGH] Android `PttPlayout.stop()` Freezes `commandMutex` for 1000ms Because `worker.join(1000L)` Precedes Track Flush
- **Location:** `core/ptt/src/androidMain/kotlin/com/transfer/flash/core/ptt/PttPlayout.kt:66-80`
- **Evidence:**
  ```kotlin
  runCatching { thread?.interrupt() }
  runCatching { thread?.join(1000L) }
  val track = audioTrack
  if (track != null) {
      audioTrack = null
      runCatching { track.pause() }
      runCatching { track.flush() }
  ```
- **Why it matters:** `AudioTrack.write()` is a native blocking call immune to thread interrupts. `join(1000L)` hangs for a full second while holding `commandMutex`, freezing all PTT UI actions and incoming frames.
- **Fix:** Pause and flush `audioTrack` *before* calling `worker?.join(1000L)`.

### [F27] [HIGH] Packet Loss Concealment (PLC) Loops Unattenuated 20ms Frame Indefinitely, Blasting 50 Hz Buzz
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttPlayoutCore.kt:73-77`, `core/ptt/src/commonTest/kotlin/com/transfer/flash/core/ptt/PttPlayoutCoreTest.kt:65-67`
- **Evidence:**
  ```kotlin
  is PttJitterBuffer.Poll.Concealed -> {
      out = last ?: zeros
      concealedTotal += 1
      level = PttAudioLevel.rms01(out) * CONCEALED_LEVEL_FACTOR
  }
  ```
- **Why it matters:** While `level` (for UI metering) is scaled by 0.5, `out` is unattenuated. Repeating the identical 20ms buffer across 20 lost packets generates a 50 Hz square-wave buzz into the listener's ear at maximum volume.
- **Fix:** Apply exponential audio sample attenuation to `out` and mute after 2 concealed packets.

### [F28] [HIGH] Unordered Coroutines for TCP Control Frames Invert `Start` and `Stop` Order
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:249-280`
- **Evidence:**
  ```kotlin
  public fun onControlFrame(peerId: String, frame: PttSessionFrame) {
      scope.launch {
          when (frame) {
              is PttSessionFrame.Start -> dispatchIo(RemoteStart(...))
              is PttSessionFrame.Stop -> dispatchIo(RemoteStop(...))
  ```
- **Why it matters:** Rapid button taps send `Start` then `Stop` over TCP. Launching unsequenced coroutines on `Dispatchers.Default` allows `Stop` to acquire `commandMutex` before `Start`. `Stop` is ignored in `Idle`, then `Start` transitions to `Listening`, leaving the receiver permanently stuck listening to silence.
- **Fix:** Sequence inbound control frame processing through an ordered Channel.

### [F29] [HIGH] Tapping "NotDownloaded" File Card Calls `onOpenAttachment(null, ...)` Instead of Downloading
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:1034-1058`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt:360-368`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashFileMessageCard.kt:140-141, 219-230`
- **Evidence:**
  ```kotlin
  // FlashConversationScreen.kt:1037-1056
  if (file.transferStatus == FlashFileTransferStatus.Failed || file.transferStatus == FlashFileTransferStatus.Paused) {
      onResumeTransfer(file.id)
  } else if (file.mimeType.startsWith("video/") && file.localUri != null) {
  ...
  } else {
      onOpenAttachment(file.localUri, file.mimeType, file.name)
  }
  ```
- **Why it matters:** Tapping "Tap to download" on an offer falls into `else`, calling `onOpenAttachment(null, ...)`. The app attempts to launch an external intent viewer with null URI instead of accepting the offer.
- **Fix:** Add `if (file.transferStatus == FlashFileTransferStatus.NotDownloaded) onAcceptOffer(file.id)`.

### [F30] [HIGH] `FlashImageGrid.kt` Freezes `onClick` in `pointerInput(Unit)` — Breaking Selection Mode
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashImageGrid.kt:523-533`
- **Evidence:**
  ```kotlin
  modifier = tileModifier.pointerInput(Unit) {
      detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() })
  }
  ```
- **Why it matters:** `pointerInput(Unit)` never restarts. When selection mode is active, `imageClick` routes to selection toggle. Because `pointerInput` captured the initial `onClick`, tapping an image opens `FlashMediaViewer` over the selection screen, violating requirement H-17.
- **Fix:** Use `val currentOnClick by rememberUpdatedState(onClick)`.

### [F31] [HIGH] `FlashSwipeToReply.kt` Launches Floods of Unconstrained Coroutines During Drag & Captures Stale `onReply`
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwipeToReply.kt:81-91, 129-131`
- **Evidence:**
  ```kotlin
  onHorizontalDrag = { change, dragAmount ->
      coroutineScope.launch { offsetX.snapTo(dampened) }
  }
  ```
- **Why it matters:** Touch drags fire at 120Hz. Launching a new coroutine on every movement floods the dispatcher with hundreds of coroutines per swipe. `onReply` is captured directly in `pointerInput(enabled)` and becomes stale.
- **Fix:** Use `rememberUpdatedState(onReply)` and use a suspending drag gesture loop.

### [F32] [HIGH] Stale Video Scaling Mode in `FlashCallVideoSurface.android.kt` Crops Screen Shares
- **Location:** `ui/callui/src/androidMain/kotlin/com/transfer/flash/ui/calling/FlashCallVideoSurface.android.kt:30-53`
- **Evidence:**
  ```kotlin
  AndroidView(
      factory = { context -> SurfaceViewRenderer(context).also { holder.attach(it, scaling) } },
      update = { holder.bind(track); it.setMirror(mirror) },
  )
  ```
- **Why it matters:** When `fit` changes dynamically (e.g. presenter starts sharing screen), `update` never calls `renderer.setScalingType()`. Video remains in `SCALE_ASPECT_BALANCED`, cropping edges of screen shares.
- **Fix:** Call `it.setScalingType(scaling.first, scaling.second)` in the `update` block.

### [F33] [HIGH] Fatal App Crash via `tiles.single()` on Duplicate Participant State in `FlashGroupVideoGrid`
- **Location:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGrid.kt:88-91`
- **Evidence:**
  ```kotlin
  if (main != null) {
      val participant = tiles.single()
  ```
- **Why it matters:** If `state.participants` contains duplicate entries for `main` during reconnect churn, `tiles.size > 1`. `tiles.single()` throws `IllegalArgumentException: List has more than one element`, crashing the call screen.
- **Fix:** Change `tiles.single()` to `tiles.firstOrNull() ?: return@Layout`.

---

## 3. Bugs Found

### [F34] [MEDIUM] Unconditional `DeliveryReceipt` Overwrites `READ` Status Back to `DELIVERED`
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6023`, `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:138-154`
- **Evidence:**
  ```kotlin
  is MessageWireFrame.DeliveryReceipt -> {
      messageDao.updateStatus(frame.messageId, "DELIVERED")
  ```
- **Why it matters:** If a `ReadReceipt` arrives before or races with `DeliveryReceipt`, `updateStatus` unconditionally overwrites `"READ"` with `"DELIVERED"`, regressing UI ticks from blue to grey.
- **Fix:** Use `updateStatusIfUnacknowledged(frame.messageId, "DELIVERED")`.

### [F35] [MEDIUM] Historical Group Messages From Former Members Dropped on Catch-up / SyncPush
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/SignedGroups.kt:1057`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:5356-5367`
- **Evidence:**
  ```kotlin
  val row = members.member(groupId, authorId)?.takeIf { it.isActive } ?: return null
  ```
- **Why it matters:** During historical catch-up sync, messages sent by members who later left or were removed return null from `verifiedAuthorLabel` because `it.isActive` is false, dropping valid history.
- **Fix:** Verify signatures against the author's key for the message epoch rather than requiring active membership today.

### [F36] [MEDIUM] Internal Voice Note Waveforms & Call Events Surface as Raw Text in Search Hits
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6573-6590, 7288-7290`, `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:233-236`
- **Evidence:** `encodeVoiceMeta` stores `vmsg:<duration>:<amplitudes>` in `messages.text`. Search queries match against this text and render `snippet = "...vmsg:4500:10,24,50,88..."`.
- **Why it matters:** Internal metadata leaks into search UI as raw gibberish strings.
- **Fix:** Filter out `vmsg:` and `cmsg:` prefixes from text search matches.

### [F37] [MEDIUM] Off-by-One in `E2eFrameCodec.decrypt()` Rejects Empty Plaintext Payloads
- **Location:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt:86-88`
- **Evidence:** `require(frame.size > NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS)`. 0-byte plaintext results in exactly 28 bytes (`12 + 16`). `28 > 28` fails.
- **Why it matters:** Decrypting valid empty string frames throws `IllegalArgumentException`.
- **Fix:** Change `>` to `>=`.

### [F38] [MEDIUM] Transient Disk Read Errors During Upload Trigger Permanent Remote Cancellation on Server
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:341, 468-475`
- **Evidence:** When `sealedChunk` fails with `LocalStorage`, line 470 calls `api.cancel(request.hubUrl, fileId)`.
- **Why it matters:** A transient local read glitch permanently cancels a 99% completed upload on the remote hub.
- **Fix:** Return `interrupted` rather than `fail`, exempting local storage errors from remote `cancel()`.

### [F39] [MEDIUM] `deleteExpired` Deletes Quarantined Rows and Emits Conflicting `EXPIRED` Notification for Messages Already `DROPPED`
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt:388-418`, `server/src/main/kotlin/com/transfer/flash/server/core/FlashServerEngine.kt:158`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:398-411`
- **Evidence:** `deleteExpired` queries `WHERE expires_at <= ?` without checking `undeliverable_reason IS NULL`.
- **Why it matters:** Quarantined messages already reported as `DROPPED` emit a second terminal `EXPIRED` event days later.
- **Fix:** Add `AND undeliverable_reason IS NULL` to `deleteExpired`'s notification list.

### [F40] [MEDIUM] Stale Trailing Bytes & Hash Mismatch on File Overwrite in `OkioRandomAccessSinkHandle`
- **Location:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt:64-70`
- **Evidence:** `fileSystem.openReadWrite(path)` does not truncate existing larger files.
- **Why it matters:** Overwriting an existing 50 MB file with 10 MB leaves 40 MB trailing data, failing SHA-256 with `MISMATCH`.
- **Fix:** In `init`, if `handle.size() > expectedTotalBytes`, call `handle.resize(expectedTotalBytes)`.

### [F41] [MEDIUM] Duplicate / Retransmitted Chunk Suppresses ACK Emission in `ReceivePipeline`
- **Location:** `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/ReceivePipeline.kt:317-334`
- **Evidence:** `return if (newlyMarked && session.pending.size >= ackEvery)`. On duplicate, `newlyMarked` is false.
- **Why it matters:** Retransmitted chunks produce no ACK, stalling the sender until retry timeout.
- **Fix:** Acknowledge retransmitted chunks immediately.

### [F42] [MEDIUM] `FlashCallSession.hangUp()` and `decline()` Silently Block Local Teardown on Network Exception
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:457-468`
- **Evidence:** `sendFrame(CallWireFrame.Hangup(...))` is called without `try/finally` before `end(...)`.
- **Why it matters:** If the socket drops, `hangUp()` throws and `end(...)` never runs, trapping the user on the call screen.
- **Fix:** Wrap `sendFrame` in `try/finally` so `end(...)` always executes.

### [F43] [MEDIUM] `CallQualityGovernor` Wall-Clock Dependency Allows Recovery Cooldown Bypass on Clock Step
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallQualityGovernor.kt:146-148`
- **Evidence:** `val inCooldown = lastStepAtMs != 0L && elapsed in 1 until recoveryCooldownMs`.
- **Why it matters:** If system time steps backward, `elapsed <= 0`, bypassing the 8-second recovery cooldown.
- **Fix:** Guard with `elapsed in 0 until recoveryCooldownMs` and use monotonic time.

### [F44] [MEDIUM] Rapid PTT Button Bounce while `Talking` Bypasses Permission and Session Guards
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:169-203`
- **Evidence:** Permission and peer checks are inside `if (floor is PttFloorState.Idle)`.
- **Why it matters:** A rapid double tap while `Talking` launches a second `LocalPress` that executes after transitioning to `Idle`, starting a new session without checking permissions.
- **Fix:** Debounce button presses and check permissions inside `processLocked`.

### [F45] [MEDIUM] `PttJitterBuffer` Sequence Number Wraparound and Initial Packet Drop
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBuffer.kt:38-55, 66-75`
- **Evidence:** Uses `seq < nextSeq` on u32 sequence numbers and drops packet 0 if packet 1 arrives first.
- **Why it matters:** Packets after sequence wraparound are dropped as `late`. Out-of-order initial packets are permanently dropped.
- **Fix:** Use signed modulo arithmetic `(seq - nextSeq).toInt() < 0`.

### [F46] [MEDIUM] `PttJitterBuffer` Dropping Earliest Packet on Overflow Guarantees Immediate Concealment Glitch
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttJitterBuffer.kt:58-61`
- **Evidence:** On overflow, `queue.remove(queue.firstKey())` deletes the exact packet about to be played.
- **Why it matters:** Causes immediate packet loss concealment and audio glitch.
- **Fix:** Drop newest packet (`queue.lastKey()`) or advance `nextSeq = queue.firstKey()`.

### [F47] [MEDIUM] `FlashVideoPlayer.kt` Subsequent Replays Fail to Seek to 0
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashVideoPlayer.kt:71, 176-184`, `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.android.kt:28-34`
- **Evidence:** Replay sets `seekToTarget = 0L`, but `seekToTarget` is never reset to null.
- **Why it matters:** On second replay, `seekToTarget = 0L` causes no state equality change; `LaunchedEffect` does not fire and seek is skipped.
- **Fix:** Reset `seekToTarget = null` once handled.

### [F48] [MEDIUM] `FlashConversationScreen.kt` `jumpToMessage` Index Off-By-One When Peer is Typing
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:581-593`, `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageList.kt:181-198`
- **Evidence:** When `peerTypingName != null`, an unindexed item is prepended to `LazyColumn`, shifting messages down by 1.
- **Why it matters:** Quoted reply jump scrolls to wrong message or typing indicator.
- **Fix:** Add +1 offset when `peerTypingName != null`.

### [F49] [MEDIUM] Multi-Select Toolbar Forward Action Silently Discards Attachments
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:641-658`
- **Evidence:** `onForward` filters on `it.text.isNotBlank()`.
- **Why it matters:** Forwarding selected photo/file messages says `"Nothing to forward"` and drops attachments.
- **Fix:** Include `images` and `fileAttachments` in forwarded payload.

### [F50] [MEDIUM] Captured Photo Lost on Process Death / Screen Rotation in `FlashCameraCapture.android.kt`
- **Location:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashCameraCapture.android.kt:24-39`
- **Evidence:** `currentUri` and `currentFile` use `remember` instead of `rememberSaveable`.
- **Why it matters:** Camera rotation kills background activity, resetting URI to null and losing captured photo.
- **Fix:** Use `rememberSaveable`.

### [F51] [MEDIUM] Coroutine Suspension Leak and Cancellation Collision in `FlashPermissions.android.kt`
- **Location:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashPermissions.android.kt:24-51`
- **Evidence:** Holds a single continuation in `pending.continuation`.
- **Why it matters:** Concurrent permission requests overwrite the first continuation, hanging it indefinitely.
- **Fix:** Guard `ensureGranted` with a mutex.

### [F52] [MEDIUM] Uncaught Exception in `FlashSharePicker` Coroutine Crashes Calling Screen
- **Location:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallShare.kt:286`
- **Evidence:** `produceState` calls `host.listSources()` without try-catch.
- **Why it matters:** Platform screen capture permission failure crashes active call.
- **Fix:** Wrap in `runCatching`.

### [F53] [MEDIUM] Stale / Unregistered `advertiseListener` Leak in `RealNsdManagerBridge`
- **Location:** `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt:326-339`
- **Evidence:** `advertiseListener = listener` is set before `registerService()` succeeds.
- **Why it matters:** If registration fails, unregistering throws `IllegalArgumentException: listener not registered`.
- **Fix:** Only set `advertiseListener` on registration success.

### [F54] [LOW] Multi-URL Messages Ignored in Links Search Scope
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/model/FlashSearchText.kt:49-60`
- **Evidence:** `firstUrl(text)` only inspects the first link.
- **Why it matters:** Second URL in a message is omitted from Links search results.
- **Fix:** Search across `URL_REGEX.findAll(text)`.

### [F55] [LOW] Direct Delivery Receipts & Reactions Cannot Be Sent Over Radio Transport
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:695-704, 6003-6005, 7500-7503`
- **Evidence:** `transportSink` falls back to `hubBridge`, but has no fallback to `radioBridge`.
- **Why it matters:** Radio-only peers cannot send delivery receipts or reactions.
- **Fix:** Add `radioBridge` fallback to `transportSink`.

### [F56] [LOW] False Positive in `onCompleted` Hook on Whole-File Hash `MISMATCH`
- **Location:** `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashInboundRouter.kt:708-716`
- **Evidence:** `host.onCompleted?.invoke(event, host.peerDeviceId)` passes `event` where `verified` is still true.
- **Why it matters:** Listeners are informed the file succeeded despite SHA-256 mismatch.
- **Fix:** Pass `ReceiveEvent.Completed(completeReply)`.

### [F57] [LOW] `CallStatusBook.apply` Clock Regression Freezes Remote Peer Reactions
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallStatusBook.kt:83-92`
- **Evidence:** `if (last == null || now - last >= MIN_REACTION_GAP_MS)`. Advances sequence but drops reaction if `now < last`.
- **Why it matters:** Clock backward step freezes peer reactions.
- **Fix:** Guard with `now < last || now - last >= MIN_REACTION_GAP_MS`.

### [F58] [LOW] Spurious `Stop` Control Frame Broadcast When Local Microphone Capture Fails
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:534-537`, `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt:240-247`
- **Evidence:** `MicDenied` emits `SendStop` even if `Start` was never announced.
- **Why it matters:** Sends spurious `Stop` frames for unknown sessions.
- **Fix:** Only emit `SendStop` if `state.announced` is true.

### [F59] [LOW] Blank `localId()` Disables Floor Collision Resolution
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:195`, `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt:250`
- **Evidence:** `holderId = localId().orEmpty()`. `state.holderId.isNotBlank()` evaluates to false if empty.
- **Why it matters:** Neither peer yields during collision.
- **Fix:** Verify `localId()` is non-blank before dispatching press.

### [F60] [LOW] Double-Counting of Dropped Spilled Messages in `RelayFrameRouter.spill`
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:728-738`, `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt:119-122, 420-423`
- **Evidence:** Both `mailboxQueue.enqueue` and `mailboxQueue.noteDropped()` increment the dropped metric.
- **Why it matters:** Metrics record twice the actual number of dropped messages.
- **Fix:** Remove `noteDropped()` call when `enqueue` already recorded it.

### [F61] [LOW] Missing `RECEIVER_NOT_EXPORTED` Flag on Android 14+ Broadcast Receivers
- **Location:** `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/android/AndroidRadioDiscovery.kt:164, 252`
- **Evidence:** `context.registerReceiver(receiver, filter)` without flags on API 34+.
- **Why it matters:** Potential SecurityException on Android 14+.
- **Fix:** Use `ContextCompat.registerReceiver(..., ContextCompat.RECEIVER_NOT_EXPORTED)`.

### [F62] [LOW] Integer Overflow in Slice Range Validation in `KissFrameCodec` and `StreamFramer`
- **Location:** `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/kiss/KissFrameCodec.kt:187`, `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/wire/StreamFramer.kt:42`
- **Evidence:** `require(offset >= 0 && length >= 0 && offset + length <= data.size)`.
- **Why it matters:** Integer overflow on `offset + length` bypasses check.
- **Fix:** Check `offset <= data.size - length`.

### [F63] [LOW] Non-Compliant Consecutive `0xFF` Handling in JVM JPEG EXIF Parser
- **Location:** `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt:165-175`
- **Evidence:** Treats second `0xFF` in `0xFF 0xFF 0xE1` as marker prefix.
- **Why it matters:** Fails to read EXIF orientation on JPEGs with fill bytes.
- **Fix:** Skip consecutive `0xFF` bytes in marker loop.

---

## 4. Security Issues

### [F64] [MEDIUM] Group Enumeration & Membership Oracle in `RelayFrameRouter.handleInboundMeta`
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:436-446`
- **Evidence:**
  ```kotlin
  val members = groups.membersOf(convId)
  if (members != null) {
      if (sender !in members) {
          offerFrame(origin, RelayFrame.Error(HubErrorCodes.ERR_NOT_MEMBER, MESSAGE_NOT_MEMBER))
          return
      }
  ...
  offerEphemeral(convId, forwarded)
  ```
- **Why it matters:** If the group exists and sender is not in it, hub replies with `ERR_NOT_MEMBER`. If the group does not exist, hub drops silently. An attacker can enumerate private group names.
- **Fix:** Return consistent silence or unified error matching `handleGroupSend`.

### [F65] [MEDIUM] Uncaught `InvalidKeySpecException` in `HubSealing.seal()` on Malformed Peer Public Key
- **Location:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubSealing.kt:117-121`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/DirectoryHubSealing.kt:87`
- **Evidence:** `PlatformCrypto.ecP256SharedSecret()` throws `InvalidKeySpecException` on corrupt key bytes.
- **Why it matters:** Corrupt directory public keys crash the application.
- **Fix:** Wrap in `runCatching` and return null.

### [F66] [LOW] Sensitive Key Material Lacks Memory Zeroing (`HubFileKey`, `GroupSecret`)
- **Location:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubFileSealing.kt:21`, `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupSecret.kt:13`
- **Evidence:** `HubFileKey` and `GroupSecret` store raw `ByteArray` without implementing `clear()` or `Destroyable`.
- **Why it matters:** Keys linger in heap memory until GC.
- **Fix:** Provide explicit `clear()` method filling byte array with zeros.

### [F67] [CANNOT CONFIRM] Path Traversal in `FlashMediaDecoder.resolveLocalFile`
- **Location:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt:167-182`
- **Evidence:** Resolves arbitrary file paths via `canonicalFile` and decodes via `BitmapFactory`.
- **Status:** I cannot confirm this from the provided code. Without an exfiltration path to extract decoded bitmaps, local decoding of arbitrary images does not constitute an exploitable file read.

### [F68] [CANNOT CONFIRM] SQL Injection via `escapeSqlLike`
- **Location:** `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6523-6528`, `core/persistence/src/commonMain/kotlin/com/transfer/flash/core/persistence/db/dao/MessageDao.kt:233-236`
- **Evidence:** Query binds `:query` with `ESCAPE '\'`.
- **Status:** I cannot confirm this from the provided code. Room compiles bound parameters using SQLite prepared statements; no injection vector is reachable.

---

## 5. Performance & Resource Concerns

### [F69] [HIGH] 60 FPS Whole-Screen Recomposition Loop in `FlashCallScreen` via Body Scope `scale.value` Read
- **Location:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt:386-389, 425`
- **Evidence:**
  ```kotlin
  val scale = rememberCallPulseScale(pulsing)
  val outerScale = FlashCallRippleMath.computeOuterGlowScale(isSpeaking, scale.value)
  ```
- **Why it matters:** Reading `scale.value` in the body scope of `FlashCallIdentityBlock` subscribes the entire composable to 60 Hz invalidation for the entire call duration. Benchmark required: measure CPU usage and frame render time on Android during active calls.
- **Fix:** Read `scale.value` only inside `Modifier.graphicsLayer { ... }`.

### [F70] [HIGH] Speaking Participant Tile Recomposing at 60 FPS in `FlashGroupVideoGrid`
- **Location:** `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGrid.kt:197-208`
- **Evidence:** `speakerPulse.value` is read in body scope to compute `speakerRingAlpha` for `Modifier.border()`.
- **Why it matters:** Recomposes video tile wrappers at 60 FPS while speaking. Benchmark required: measure frame drop rate in 4+ participant group video call.
- **Fix:** Draw border in draw phase via `Modifier.drawWithContent`.

### [F71] [HIGH] 50 Coroutines/sec Spawned on `Dispatchers.Default` Contending on `commandMutex` and Mutating StateFlows on Every Audio Frame
- **Location:** `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:341-350, 424-442`
- **Evidence:** Every 20ms audio packet launches a coroutine acquiring `commandMutex` and mutating `_state` and `_stats` StateFlows.
- **Why it matters:** Allocates 50 coroutines/sec and causes 100 StateFlow emissions/sec, pegging CPU and starving user actions. Benchmark required: measure allocation rate and thread lock contention under sustained PTT receive.
- **Fix:** Update a `@Volatile var lastAudioPacketMs: Long` without acquiring mutex or mutating StateFlow.

### [F72] [MEDIUM] Multicast Lock Skipped on Android 14+ Causes Packet Dropping in Hardware
- **Location:** `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/multicast/AndroidMulticastSocketFactory.kt:64-66, 88`, `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt:1789-1798`
- **Evidence:** `if (apiLevel >= SDK_MULTICAST_LOCK_NOT_NEEDED) return` skips lock on API 34+.
- **Why it matters:** Hardware Wi-Fi drops multicast packets for app UDP sockets with screen off. Benchmark required: measure packet loss rate on Android 14+ with screen off.
- **Fix:** Acquire multicast lock across all API levels.

### [F73] [MEDIUM] Omission of `isVideo` in `JvmImageDecoder` Cache Key
- **Location:** `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.jvm.kt:57`
- **Evidence:** Key is `"$source|$maxLongEdge|$lowColorDepth"`.
- **Why it matters:** Video and image thumbnails at same path collide. Benchmark required: verify memory cache collision frequency.
- **Fix:** Include `isVideo` in cache key.

### [F74] [LOW] Missing In-Shim Lifecycle Teardown in `rememberFlashAudioPlayer` and `rememberFlashVoiceRecorder`
- **Location:** `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashAudioPlayer.android.kt:13-17`, `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVoiceRecorder.android.kt:14-17`
- **Evidence:** Factory functions lack internal `DisposableEffect`.
- **Why it matters:** Future callers will leak native `MediaPlayer`/`MediaRecorder`. Benchmark required: measure native handle count over repeated audio playbacks.
- **Fix:** Embed `DisposableEffect` directly within the factory.

### [F75] [LOW] Missing `remember` on `buildHighlightedMessageText` in `FlashMessageBubble.kt`
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt:672-681`
- **Evidence:** Substring regex search runs on every bubble recomposition.
- **Why it matters:** Garbage allocation churn in search results. Benchmark required: measure allocation bytes per scroll frame in search mode.
- **Fix:** Wrap in `remember(message.text, searchQuery)`.

### [F76] [LOW] Per-Item Un-Remembered Lambda Allocations in `FlashMessageBubble.kt`
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashMessageBubble.kt:172-186, 359-378, 615-634`
- **Evidence:** Allocates 15 new lambdas per bubble on every recomposition.
- **Why it matters:** Defeats callback memoization in child composables. Benchmark required: measure recomposition count of `FlashFileMessageCard`.
- **Fix:** Memoize lambdas per `file.id`.

### [F77] [LOW] Key Instability in `FlashAttachmentStagingTray.kt` Using Item Index
- **Location:** `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashAttachmentStagingTray.kt:94-97`
- **Evidence:** `key = { index, item -> "${item.uri}_$index" }`.
- **Why it matters:** Removing an item recreates all subsequent cards. Benchmark required: measure UI frame duration on attachment deletion.
- **Fix:** Use `item.uri` as key.

### [F78] [LOW] Leaked `RelaySession.scope` Coroutine Context Due to Missing Invocations of `dispose()`
- **Location:** `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt:65, 285-287`
- **Evidence:** `RelaySession.dispose()` is never called anywhere in the codebase.
- **Why it matters:** `SupervisorJob` is never explicitly cancelled on session termination. Benchmark required: measure heap retention of disconnected WebSocket sessions.
- **Fix:** Call `session.dispose()` in `sessionEnded`.

### [F79] [CANNOT CONFIRM] Socket Resource Cleanliness in `BoundedBody.kt` When Stream is Cut Short
- **Location:** `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/BoundedBody.kt:24-29`
- **Evidence:** Read loop breaks without exhausting channel.
- **Status:** I cannot confirm this from the provided code. Benchmark required: measure open socket file descriptors when hitting `maxBytes` on Ktor CIO.

### [F80] [CANNOT CONFIRM] Asynchronous Media Release Contention on Rapid Call Reconnect
- **Location:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:2256`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt:626-643`
- **Evidence:** `releaseMedia()` runs asynchronously on `callMediaDispatcher` while `onEnded` clears session immediately.
- **Status:** I cannot confirm this from the provided code. Benchmark required: trace Android Camera2 HAL device state during rapid hangup/re-dial cycles.

---

## 6. Dependency Concerns
None found. Dependency coordinates and versions in `gradle/libs.versions.toml` are strictly pinned and align with build toolchains.

---

## 7. Dead / Inert / Duplicate / Useless Code
- `server/src/main/kotlin/com/transfer/flash/server/relay/RelaySession.kt:285-287`: `dispose()` is dead code (never called).
- `ui/platform-shims/src/jvmMain/kotlin/com/transfer/flash/ui/shims/FlashBackHandler.jvm.kt:16`: `enabled` and `onBack` parameters are completely unused.
- `ui/callui/src/jvmMain/kotlin/com/transfer/flash/ui/calling/FlashCallVideoSurface.jvm.kt:75`: `zOrderMediaOverlay: Boolean` is unused.
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:679`: `audioOut` drain in `senderLoop` runs after capture is enabled, dropping initial speech.
- `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/chunked/Chunker.kt:257-265`: Pooled buffer inside `buffered` is leaked to GC on `ChunkStream.close()`.

---

## 8. Overcomplicated Code & Simpler Alternatives

| Location | Current approach | Simpler alternative |
|---|---|---|
| `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:341-350` | Spawns coroutine, acquires commandMutex, updates StateFlows on every 20ms audio frame | Store `@Volatile var lastAudioPacketMs: Long` and check it in 1 Hz `Tick` |
| `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt:386-389` | Evaluates glow scale in body scope, invalidating composable tree at 60 FPS | Evaluate inside `Modifier.graphicsLayer { scaleX = ... }` draw block |
| `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/model/FlashSearchText.kt:49-60` | Extracts `firstUrl` and checks query against only the first link | `URL_REGEX.findAll(text).any { it.value.contains(query, true) }` |
| `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt:35-41` | Sequential unbounded FIFO without timeouts that deadlocks on single hang | Wrap each resolve in `withTimeoutOrNull(6000L)` |
| `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwipeToReply.kt:129-131` | Launches new coroutine on `coroutineScope` on every move event during drag | Use suspending pointer input loop calling `offsetX.snapTo()` directly |

---

## 9. Missing Tests

| Missing test | Why it matters | Suggested test location |
|---|---|---|
| Transient directory failure during sealed message receive | Prevents permanent mailbox purge on network glitch (F1) | `core/messaging/src/androidHostTest/.../HubSealingBridgeTest.kt` |
| Regular member attempting to demote admin | Prevents privilege escalation attack (F2) | `core/messaging/src/androidHostTest/.../GroupSignatureRulesTest.kt` |
| Concurrent `renew()` and `bearer()` call | Prevents authentication using superseded certificate serial (F3) | `core/enterprise/src/commonTest/.../HubSessionTest.kt` |
| Swarm piece request with byte budget < pieceSize | Prevents silent piece request black hole (F5) | `core/swarm/src/commonTest/.../ServePolicyTest.kt` |
| Conversation switch in dual-pane desktop | Verifies draft persistence does not corrupt adjacent chats (F6) | `ui/chat/src/commonTest/.../FlashConversationScreenTest.kt` |
| Negative `System.nanoTime()` in PTT heartbeat | Verifies PTT heartbeat codec accepts valid negative monotonic time (F7) | `core/ptt/src/jvmTest/.../JvmPttHeartbeatTest.kt` |
| PTT floor collision resolution listener switch | Verifies listener C does not go deaf to winning talker A (F8) | `core/ptt/src/commonTest/.../PttFloorMachineTest.kt` |
| Component truncation ending in Windows reserved name | Verifies `con.txt` cannot be generated by truncation (F16) | `core/engine/src/commonTest/.../FlashPathSanitizerTest.kt` |
| Targeted stream channel with offline peer | Verifies data does not leak to arbitrary peer on desktop (F17) | `desktop/src/jvmTest/.../DesktopEngineStreamTest.kt` |
| Session displacement during active in-flight transfer | Verifies in-flight messages are spilled immediately (F18) | `server/src/test/.../RelayConnectionPoolTest.kt` |

---

## 10. Recommended Fix Plan

1. **Step 1: Fix Sealed Message Purging & Privilege Escalation [F1, F2]**
   - Files: `core/messaging/.../HubChatBridge.kt`, `core/messaging/.../GroupSignatureRules.kt`
   - Action: Return `RETRY_LATER` on transient directory/keystore errors; forbid non-admins from signing certs for admins/owners.
   - Verify: Run `:core:messaging:jvmTest`.
2. **Step 2: Fix Enterprise Auth & Relay Concurrency Races [F3, F4, F13, F14, F18]**
   - Files: `core/enterprise/.../HubSession.kt`, `FlashRelayClient.kt`, `HubFileTransfers.kt`, `KtorRelayTransport.kt`, `server/.../RelayConnectionPool.kt`
   - Action: Evaluate `credentials()` inside mutex; join `pendingDisconnect` under lock; spill displaced in-flight messages immediately.
   - Verify: Run `:core:enterprise:jvmTest` and `:server:test`.
3. **Step 3: Fix Swarm Piece Black Hole & Large File Bounds [F5, F15, F16, F17, F40, F41]**
   - Files: `core/swarm/.../ServePolicy.kt`, `core/security/.../HubFileSealing.kt`, `core/engine/.../FlashPathSanitizer.kt`, `desktop/.../DesktopEngine.kt`, `core/transfer/.../RandomAccessSinkHandle.kt`
   - Action: Partition unserved pieces into `rejectedPieces`; check `plainBytes` overflow; sanitize reserved names post-truncation; truncate overwrites.
   - Verify: Run `:core:swarm:jvmTest`, `:core:transfer:jvmTest`, and `:core:engine:jvmTest`.
4. **Step 4: Fix PTT Floor Muting, Clocks, and Resource Freezes [F7, F8, F9, F26, F27, F28, F71]**
   - Files: `core/ptt/.../PttFloorMachine.kt`, `PttPlatform.jvm.kt`, `PttSessionEngine.kt`, `PttPlayout.kt`, `PttPlayoutCore.kt`
   - Action: Switch to winning talker on collision; anchor monotonic clock; filter untrusted peers; flush track before join; attenuate concealment; throttle frame events.
   - Verify: Run `:core:ptt:jvmTest`.
5. **Step 5: Fix Discovery & Calling Wedges [F20, F21, F22, F23, F24, F25, F32, F33]**
   - Files: `core/discovery/.../NsdResolveQueue.kt`, `NsdTransport.kt`, `core/radio/.../AndroidBluetoothCatalog.kt`, `core/calling/.../CallCoordinator.kt`, `CallHealthMonitor.kt`, `ui/callui/.../FlashGroupVideoGrid.kt`
   - Action: Demote on resolve failure; add resolve timeout; read blocking Bluetooth; guard group call active state; preserve Data Saver in severe heat; replace `single()`.
   - Verify: Run `:core:discovery:jvmTest` and `:core:calling:jvmTest`.
6. **Step 6: Fix UI Freezes, Leaks, and Action Bugs [F6, F10, F29, F30, F31, F69, F70]**
   - Files: `ui/chat/.../FlashConversationScreen.kt`, `FlashMessageBubble.kt`, `FlashImageGrid.kt`, `FlashSwipeToReply.kt`, `ui/platform-shims/.../FlashVideoSurface.android.kt`, `ui/callui/.../FlashCallScreen.kt`
   - Action: Key conversation draft; key video surface to URI; wire "NotDownloaded" to download; update pointer callbacks; move animation reads into draw phase.
   - Verify: Compile `:ui:chat:compileKotlinJvm` and run `:ui:callui:jvmTest`.

---

## 11. Report for the Fixing Agent

- TASK 1 [F1] [CRITICAL]: Prevent permanent purging of unread sealed messages on transient lookup failures.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/hub/HubChatBridge.kt:428-435`, `396-403`
  - Change: In `handleInbound`, if `opened == null` and `sealing?.open()` failed due to key availability or transient directory network error, return `HubInboundOutcome.RETRY_LATER` instead of `reject()`.
  - Verify: Write test in `HubChatBridgeTest` with failing directory lookup; assert outcome is `RETRY_LATER` and `recvAck` is NOT called.
  - Do NOT: Change the signature or encryption format of `HubSealing`.

- TASK 2 [F2] [CRITICAL]: Prevent regular group members from demoting group admins.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/protocol/GroupSignatureRules.kt:96-99`
  - Change: In `memberIssued` branch of `checkCert`, verify `cert.subjectId != charter.ownerId`, `adminLookup(cert.subjectId) == null`, and `knownKey == null`.
  - Verify: Run `GroupSignatureRulesTest` and add test case where regular member issues demotion cert for admin.
  - Do NOT: Modify charter rules for founder promotions.

- TASK 3 [F3] [CRITICAL]: Prevent stale certificate authentication race during renewal.
  - Files: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/HubSession.kt:148`, `243`
  - Change: Move `credentials()` invocation inside `mutex.withLock` in both `bearer()` and `renew()`.
  - Verify: Run `HubSessionTest`.
  - Do NOT: Change `NETWORK_BUDGET_MS` or token caching TTL.

- TASK 4 [F4] [CRITICAL]: Fix ERROR-397 reconnection kill race on hub address change.
  - Files: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/FlashRelayClient.kt:77-80`
  - Change: In `hubUrl.set`, acquire `lifecycleLock`, join existing `pendingDisconnect`, and assign new disconnect job to `pendingDisconnect` before triggering `wake`.
  - Verify: Run `FlashRelayClientTest`.
  - Do NOT: Remove the `pendingDisconnect` check from `startConnectionLoop`.

- TASK 5 [F5] [CRITICAL]: Fix silent piece request black hole when byte budget < pieceSize.
  - Files: `core/swarm/src/commonMain/kotlin/com/transfer/flash/core/swarm/engine/ServePolicy.kt:208-219`
  - Change: Collect pieces that cannot be served due to `byteBudget < pieceSize` into `rejectedPieces` with `SwarmRejectReason.BUSY`.
  - Verify: Run `ServePolicyTest` with request exceeding budget.
  - Do NOT: Increase `maxOutstandingBytesPerRequester` globally.

- TASK 6 [F6] [CRITICAL]: Key conversation draft and persistence to conversation ID.
  - Files: `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:415-431`
  - Change: Change `remember { mutableStateOf("") }` to `remember(state.header.id) { mutableStateOf(state.draftText) }` and key `DisposableEffect` on `state.header.id`.
  - Verify: Recompose `FlashConversationScreen` with new conversation ID; verify draft is reset to new conversation's draft.
  - Do NOT: Remove draft persistence on disposal.

- TASK 7 [F7] [CRITICAL]: Fix negative monotonic clock timestamps on desktop JVM.
  - Files: `core/ptt/src/jvmMain/kotlin/com/transfer/flash/core/ptt/PttPlatform.jvm.kt:3`
  - Change: Anchor `System.nanoTime()` to a strictly positive monotonic baseline offset.
  - Verify: Run `PttSessionCodecTest` on JVM.
  - Do NOT: Relax wire codec `takeIf { it > 0 }` requirement.

- TASK 8 [F8] [CRITICAL]: Fix PTT floor collision resolution muting third-party listeners.
  - Files: `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt:342-343`
  - Change: In `reduceListening`, track incoming claims within `collisionWindowMs`. If a claim arrives from a lower `holderId`, transition playout to the winning holder.
  - Verify: Run `PttFloorMachineTest` with 3-party collision scenario.
  - Do NOT: Allow floor preemption outside `collisionWindowMs`.

- TASK 9 [F9] [CRITICAL]: Filter self and untrusted peers from microphone audio broadcast.
  - Files: `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:474-481`
  - Change: In `startTalk`, filter `snapshotMembers()` with `it != self && isTrustedPeer(it)`.
  - Verify: Run `PttSessionEngineTest`.
  - Do NOT: Touch peer discovery logic.

- TASK 10 [F10] [CRITICAL]: Fix Android video surface playback freeze on URI change.
  - Files: `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashVideoSurface.android.kt:60-122`
  - Change: Key `AndroidView` with `key(uri) { ... }` or implement `update` block reloading URI.
  - Verify: Run `FlashMediaDecoderSecurityTest`.
  - Do NOT: Remove `stopPlayback()` on disposal.

- TASK 11 [F11] [HIGH]: Enforce backward secrecy boundary and fix re-admitted member lockout.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:4046-4065`
  - Change: Ensure `GsSecretRequest` verifies `frame.epoch >= member.joinedEpoch`, and only checks rotations occurring after the member's join date.
  - Verify: Run `GroupRotationTest`.
  - Do NOT: Allow arbitrary epoch access.

- TASK 12 [F12] [HIGH]: Prune inactive members from group outbox and check local removal on text inbound.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6394-6435, 3597-3605`
  - Change: In `drainGroupMessage`, filter pending deliveries by active members. In `onInboundGroupFrame` for `Message`, check `!isRemovedHere`.
  - Verify: Run `RealFlashChatRepositoryTest`.
  - Do NOT: Change 1:1 outbox logic.

- TASK 13 [F13] [HIGH]: Prevent crash on corrupt completed upload state.
  - Files: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/files/HubFileTransfers.kt:241`
  - Change: Safely decode `key` before calling `ticketOf(stored)`.
  - Verify: Run `HubFileTransfersTest`.
  - Do NOT: Drop valid completed uploads.

- TASK 14 [F14] [HIGH]: Protect `KtorRelayTransport.connect` with mutex and await handshake.
  - Files: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/KtorRelayTransport.kt:104-106, 117-122`
  - Change: Wrap `connect()` in a mutex; if connecting, await `welcomeDeferred`.
  - Verify: Run `KtorRelayTransportTest`.
  - Do NOT: Alter frame encoding.

- TASK 15 [F15] [HIGH]: Prevent integer overflow on large `plainBytes` in `HubFileLayout.of`.
  - Files: `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/enterprise/HubFileSealing.kt:105-113`
  - Change: Add `if (plainBytes <= 0L || plainBytes > plainChunk * HubContract.MAX_FILE_CHUNKS) return null`.
  - Verify: Run `HubFileSealingTest` with `Long.MAX_VALUE`.
  - Do NOT: Change `MAX_FILE_CHUNKS`.

- TASK 16 [F16] [HIGH]: Sanitize Windows reserved device names after truncation.
  - Files: `core/engine/src/commonMain/kotlin/com/transfer/flash/core/engine/FlashPathSanitizer.kt:47-53, 100-114`
  - Change: Move `isReservedDeviceName` check to run on the result of `truncatePreservingExtension`. Add `CLOCK$` to reserved list.
  - Verify: Run `FlashPathSanitizerTest` with `"con" + " ".repeat(120) + "xyz.txt"`.
  - Do NOT: Change max path length constants.

- TASK 17 [F17] [HIGH]: Prevent targeted transfer leakage to arbitrary connected peer on desktop.
  - Files: `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:2098-2102`
  - Change: Only fall back to `active.values.firstOrNull()` when `peerDeviceId == null`.
  - Verify: Run `DesktopEngineTest`.
  - Do NOT: Touch Android's `DiscoveryEngineHolder`.

- TASK 18 [F18] [HIGH]: Spill displaced in-flight messages immediately on session displacement.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/relay/RelayConnectionPool.kt:47-58`, `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:712-743`
  - Change: In `RelayConnectionPool.register`, extract `old.inFlight` and trigger `spill` immediately before launching `closeGracefully`.
  - Verify: Run `RelayConnectionPoolTest`.
  - Do NOT: Disable graceful WebSocket close frames.

- TASK 19 [F19] [HIGH]: Delete expired mailbox envelopes on resend.
  - Files: `server/src/main/kotlin/com/transfer/flash/server/mailbox/MailboxQueue.kt:144-152, 203-211`
  - Change: In `insert()`, delete rows matching `undeliverable_reason IS NOT NULL OR expires_at <= ?`. Filter `expires_at > ?` in `existingSeq`.
  - Verify: Run `MailboxQueueTest`.
  - Do NOT: Touch unexpired duplicate detection.

- TASK 20 [F20 & F21] [HIGH]: Demote failed NSD resolves and add timeout to `NsdResolveQueue`.
  - Files: `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdResolveQueue.kt:35-58`, `NsdTransport.kt:458-470`
  - Change: Add `onResolveFailedCallback` routing to `events.onRegistrationFailed()`. Add a 6-second timeout to `NsdResolveQueue` to unwedge hangs.
  - Verify: Run `NsdTransportTest`.
  - Do NOT: Alter Android 14+ native resolve listener.

- TASK 21 [F22] [HIGH]: Fix Bluetooth socket EOF detection in `AndroidBluetoothCatalog`.
  - Files: `core/radio/src/androidMain/kotlin/com/transfer/flash/core/radio/link/AndroidBluetoothCatalog.kt:308-329`
  - Change: Call `input.read()` directly on `Dispatchers.IO` instead of polling `input.available()`.
  - Verify: Run `KissTncDriverTest`.
  - Do NOT: Remove IO exception handling.

- TASK 22 [F23] [HIGH]: Add `BLUETOOTH_SCAN` permission check before scanning on Android 12+.
  - Files: `core/radio/src/commonMain/kotlin/com/transfer/flash/core/radio/link/BluetoothPermissions.kt:39`
  - Change: Include `BLUETOOTH_SCAN` in mandatory permissions for API 31+.
  - Verify: Run `AndroidRadioDiscoveryTest`.
  - Do NOT: Require location permissions on API 31+.

- TASK 23 [F24] [HIGH]: Guard `_activeCall` teardown against active group calls.
  - Files: `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt:636-641`
  - Change: Update line 640 to `if (currentSession == null && currentGroupSession == null) _activeCall.value = null`.
  - Verify: Run `CallCoordinatorGroupEndTest`.
  - Do NOT: Remove the 2-second delay.

- TASK 24 [F25] [HIGH]: Preserve Data Saver preference under `FlashThermalStatus.SEVERE`.
  - Files: `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallHealthMonitor.kt:69-82`
  - Change: Return `receiveCap = if (dataSaver) 0 else 1` when `thermal >= SEVERE`.
  - Verify: Run `CallHealthMonitorTest`.
  - Do NOT: Change the warning status.

- TASK 25 [F26] [HIGH]: Flush and pause `audioTrack` before joining worker thread in `PttPlayout`.
  - Files: `core/ptt/src/androidMain/kotlin/com/transfer/flash/core/ptt/PttPlayout.kt:66-80`
  - Change: Move `track.pause()` and `track.flush()` to execute before `thread?.join(1000L)`.
  - Verify: Run `PttPlayoutTest`.
  - Do NOT: Remove track release.

- TASK 26 [F27] [HIGH]: Apply exponential audio attenuation to repeated concealed frames.
  - Files: `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttPlayoutCore.kt:73-77`
  - Change: Multiply samples in `out` by attenuation factor on successive concealed packets; mute to zeros after 2 packets.
  - Verify: Run `PttPlayoutCoreTest`.
  - Do NOT: Change jitter buffer depth math.

- TASK 27 [F28] [HIGH]: Sequence inbound PTT control frames through an ordered queue.
  - Files: `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:249-280`
  - Change: Route `onControlFrame` through a sequential channel rather than launching independent coroutines on `Dispatchers.Default`.
  - Verify: Run `PttSessionEngineSessionTest`.
  - Do NOT: Touch audio packet dispatch.

- TASK 28 [F29] [HIGH]: Wire "NotDownloaded" card tap to `onAcceptOffer`.
  - Files: `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashConversationScreen.kt:1037`
  - Change: Add `if (file.transferStatus == FlashFileTransferStatus.NotDownloaded) onAcceptOffer(file.id)`.
  - Verify: Compile `:ui:chat:compileKotlinJvm`.
  - Do NOT: Change failed/paused retry handling.

- TASK 29 [F30] [HIGH]: Wrap pointer gesture callbacks in `rememberUpdatedState` in `FlashImageGrid`.
  - Files: `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashImageGrid.kt:523-533`
  - Change: Use `val currentOnClick by rememberUpdatedState(onClick)` inside `pointerInput`.
  - Verify: Verify selection mode toggles on image tap.
  - Do NOT: Remove long press gesture.

- TASK 30 [F31] [HIGH]: Eliminate coroutine flood and stale callbacks in `FlashSwipeToReply`.
  - Files: `ui/chat/src/commonMain/kotlin/com/transfer/flash/ui/chat/FlashSwipeToReply.kt:81-91, 129-131`
  - Change: Wrap `onReply` in `rememberUpdatedState`; use suspending gesture loop instead of launching coroutines on every drag event.
  - Verify: Test swipe-to-reply gesture.
  - Do NOT: Change damping threshold.

- TASK 31 [F32] [HIGH]: Update SurfaceViewRenderer scaling type in `update` block.
  - Files: `ui/callui/src/androidMain/kotlin/com/transfer/flash/ui/calling/FlashCallVideoSurface.android.kt:48-51`
  - Change: In `AndroidView.update`, call `it.setScalingType(scaling.first, scaling.second)`.
  - Verify: Run `FlashCallVideoSurfaceTest`.
  - Do NOT: Change `FlashVideoSink` holder.

- TASK 32 [F33] [HIGH]: Replace `tiles.single()` with `firstOrNull()` in `FlashGroupVideoGrid`.
  - Files: `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashGroupVideoGrid.kt:88-91`
  - Change: Change `tiles.single()` to `tiles.firstOrNull() ?: return@Layout`.
  - Verify: Run `FlashGroupVideoGridTest`.
  - Do NOT: Alter compact layout positioning math.

- TASK 33 [F34] [MEDIUM]: Guard delivery receipt status update against overwriting `READ`.
  - Files: `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:6023`
  - Change: Replace `messageDao.updateStatus` with `messageDao.updateStatusIfUnacknowledged(frame.messageId, "DELIVERED")`.
  - Verify: Run `RealFlashChatRepositoryTest`.
  - Do NOT: Modify read receipt logic.

- TASK 34 [F37] [MEDIUM]: Fix off-by-one check in `E2eFrameCodec.decrypt`.
  - Files: `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/crypto/E2eFrameCodec.kt:86-88`
  - Change: Change `>` to `>=`.
  - Verify: Add test in `SecureBinaryFrameCodecTest` encrypting and decrypting empty string `""`.
  - Do NOT: Modify nonce or tag size constants.

- TASK 35 [F40] [MEDIUM]: Truncate stale trailing bytes on file overwrite in `OkioRandomAccessSinkHandle`.
  - Files: `core/transfer/src/commonMain/kotlin/com/transfer/flash/core/transfer/policy/RandomAccessSinkHandle.kt:64-70`
  - Change: In `init`, if `handle.size() > expectedTotalBytes`, call `handle.resize(expectedTotalBytes)`.
  - Verify: Run `RandomAccessSinkHandleTest` writing a 1 MB file over an existing 5 MB file.
  - Do NOT: Call `resize` when expanding files (preserves ERROR-067 fix).

- TASK 36 [F42] [MEDIUM]: Wrap `FlashCallSession.hangUp()` signaling in try-finally.
  - Files: `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:457-468`
  - Change: Enclose `sendFrame(Hangup)` in `try/catch` and ensure `end(...)` executes in `finally`.
  - Verify: Run `FlashCallSessionTest`.
  - Do NOT: Swallow local media release exceptions.

- TASK 37 [F69] [HIGH]: Eliminate 60 FPS animation recomposition in `FlashCallScreen`.
  - Files: `ui/callui/src/commonMain/kotlin/com/transfer/flash/ui/calling/FlashCallScreen.kt:386-389, 425`
  - Change: Pass `scale` state into `graphicsLayer` drawing blocks rather than reading `.value` in `FlashCallIdentityBlock` body scope.
  - Verify: Run `:ui:callui:jvmTest`.
  - Do NOT: Remove ripple glow visual effects.

- TASK 38 [F71] [HIGH]: Throttle PTT audio packet event processing.
  - Files: `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt:341-350`
  - Change: Replace coroutine launch and StateFlow emission with `@Volatile var lastAudioPacketMs: Long`.
  - Verify: Run `PttSessionEngineTest`.
  - Do NOT: Remove audio playout buffer offer.

- INVESTIGATE 1 [F67] [CANNOT CONFIRM]: Path Traversal in `FlashMediaDecoder.resolveLocalFile`.
  - What to check: Verify whether any calling path in `ui:chat` or `ui:platform-shims` allows remote peers to provide arbitrary local file paths and observe or retrieve decoded bitmap content.
  - Evidence needed: Trace caller arguments to `rememberFlashImageDecoder` and verify whether file path strings originate from untrusted remote frames without directory containment sanitization.
  - Where to look: `ui/platform-shims/src/androidMain/kotlin/com/transfer/flash/ui/shims/FlashImageDecoder.android.kt:167-182` and its call sites in `FlashMessageBubble.kt` and `FlashMediaViewer.kt`.

- INVESTIGATE 2 [F79] [CANNOT CONFIRM]: Socket Resource Cleanliness in `BoundedBody.kt`.
  - What to check: Inspect Ktor CIO client behavior when `ByteReadChannel.readUTF8Line` or bounded loop exits before consuming the full response stream.
  - Evidence needed: Determine whether unconsumed CIO channels release TCP connections cleanly or keep socket descriptors open until timeout.
  - Where to look: `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/BoundedBody.kt:24-29`.

- INVESTIGATE 3 [F80] [CANNOT CONFIRM]: Asynchronous Media Release Contention on Rapid Call Reconnect.
  - What to check: Test rapid hangup and immediate re-dial on physical Android hardware (Camera2 service).
  - Evidence needed: Check logcat for `CameraAccessException: CAMERA_IN_USE` when `releaseMedia()` races `startMedia()`.
  - Where to look: `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:2256` and `CallCoordinator.kt:626-643`.
