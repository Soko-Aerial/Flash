# Flash Public API Specification

**Date:** 2026-09-11  
**Version:** `1.3.0`  
**Status:** IMPLEMENTED — every signature below was re-verified against module source on 2026-09-11
(this revision adds the §7/§10 calling seam: `FlashEngine.calls`, `attachCalling`/`detachCalling`,
`onInboundCallText`/`onCallSignalingLost`/`onCallSignalingRestored`, and the `compileOnly` contract
behind them; the previous revision added the §6 group members, §7 group calling and §14 `:core:ptt`).

Scope is the surface a third-party consumer compiles against. Internal wiring is deliberately
absent: `internal` declarations, `@FlashInternalApi` members, Room entities/DAOs, and the
WebSocket/TCP transport classes are not supported API. If it is not listed here, do not depend
on it.

---

## 1. Design Rules & Stability Annotations

1. **Abstractions, not implementations.** Every module's entry point is a Kotlin `interface` —
   `FlashDiscovery`, `FlashNetwork`, `FlashTransferRepository`, `FlashChatRepository`,
   `FlashCalling`, `FlashCallMedia`, `FlashPtt`, `FlashTrustStore`, `FlashIdentityStore`,
   `FlashCrypto`, `FlashEngine`. Concrete types (`DefaultFlashNetwork`, `CallCoordinator`,
   `PttSessionEngine`, `SampleFlashChatRepository`, `KeystoreFlashCrypto`, …) exist so a host can
   construct one; consumers hold the interface. Three documented exceptions, each because the type
   *is* the configuration and a factory would only add ceremony: `FlashSettingsDataStore` (§9, one
   DataStore per file), `DefaultFlashEngine` (§10, the hand-assembly path), and
   `DefaultFlashPairingProtocol` (§8, every constructor argument is a host decision). Room forces
   a fourth, `FlashDatabase`, whose DAOs are public only because a public accessor cannot return
   an `internal` type — see §9 for why they are still off-limits.
2. **Information hiding.** No socket, stream, codec, Room, or platform implementation type
   appears in a public signature. The single sanctioned third-party leak is webrtc-kmp's
   `VideoTrack` in §7 (ADR-025) — a video renderer has to be handed the real track, and any
   wrapper would have to expose it again to be useful.
3. **Immutability.** Public data models are `data class`es with `val` properties only. Derived
   values are computed `get()` properties, never mutable state.
4. **Reactive state.** Retained state is exposed as `StateFlow`; event streams as `Flow`.
5. **Result type, not exceptions.** Fallible operations return `FlashResult<T>`. `FlashError` is
   a **sealed interface, not a `Throwable`** — Flash defines no exception type of its own, and
   nothing documented here throws one. (`FlashResult.runCatching` wraps a thrown platform
   exception into `FlashError.Unknown`.)
6. **Stability annotations** (declared in `:core:common`):
   - `@FlashInternalApi` — opt-in level `ERROR`. Public across Gradle modules for internal
     wiring, forbidden for third-party consumers.
   - `@FlashExperimentalApi` — opt-in level `WARNING`. Preview API, subject to change.
7. **Enforcement.** Every `:core:*` module compiles with `explicitApi()` in strict mode
   (ADR-023), so visibility is always declared, never inferred. The `:ui:*` modules do not:
   Compose modules are public-by-default and their supported surface is the composables listed
   in §11–§13.
8. **Compile targets.** `:core:*` = `compileSdk 35` (ADR-022, widest consumer reach); `:ui:*` =
   `compileSdk 37`. All modules `minSdk 24`, Java 11 bytecode.

---

## 2. Core Common Module (`:core:common`)

Shared models, the result type, and framing primitives. No Flash dependencies of its own; every
other module depends on it, usually transitively.

### `FlashDevice`, `FlashDeviceId`, `FlashTransportType`, `FlashPeerPresence`
- **Stability:** Stable
- **Purpose:** Identity of a peer and the medium it was reached over.
- **Definition:**
  ```kotlin
  public data class FlashDevice(
      val id: FlashDeviceId,
      val friendlyName: String,
      val transportType: FlashTransportType,
      val presence: FlashPeerPresence = FlashPeerPresence.Online,
      val protocolVersion: Int = 1,
  )

  @JvmInline
  public value class FlashDeviceId(public val value: String)

  public enum class FlashTransportType { LAN, WIFI_DIRECT, WEBSOCKET, RELAY, MESH, UNKNOWN }

  public enum class FlashPeerPresence { Online, Offline, Typing, Connecting }
  ```

### `FlashResult<T>` & `FlashError`
- **Stability:** Stable
- **Purpose:** Return type of every fallible operation in the public API.
- **Definition:**
  ```kotlin
  public sealed interface FlashResult<out T> {
      public data class Success<out T>(val value: T) : FlashResult<T>
      public data class Failure(val error: FlashError) : FlashResult<Nothing>

      public val isSuccess: Boolean get() = this is Success
      public val isFailure: Boolean get() = this is Failure

      public companion object {
          public inline fun <T> runCatching(block: () -> T): FlashResult<T>
      }
  }
  ```
- **Extensions** (same package, `com.transfer.flash.core.common.result`):
  `getOrNull()`, `getOrElse { }`, `map { }`, `flatMap { }`, `onSuccess { }`, `onFailure { }`,
  `fold(onSuccess, onFailure)`.

- **Errors:**
  ```kotlin
  public sealed interface FlashError {
      public data class NetworkUnavailable(val message: String? = null) : FlashError
      public data class PeerUnavailable(val deviceId: String, val message: String? = null) : FlashError
      public data class ConnectionTimeout(val timeoutMs: Long, val message: String? = null) : FlashError
      public data class ProtocolMismatch(val expected: Int, val actual: Int) : FlashError
      public data class TransferFailed(val transferId: String, val reason: String) : FlashError
      public data class VerificationFailed(val expectedHash: String, val actualHash: String) : FlashError
      public data class StorageError(val message: String, val cause: Throwable? = null) : FlashError
      public data class Cancelled(val reason: String? = null) : FlashError
      public data class Unknown(val message: String, val cause: Throwable? = null) : FlashError
  }
  ```

### Remaining public surface

| Type | Stability | Purpose |
|---|---|---|
| `FlashInternalApi`, `FlashExperimentalApi` | Stable | Opt-in stability annotations (§1.6). |
| `FlashTimeSource`, `SystemTimeSource` | Stable | Injectable clock; tests substitute it. |
| `Base64` | Stable | Pure-Kotlin RFC 4648 codec (ADR-027) — no `java.util.Base64`, which needs API 26. |
| `FlashLog`, `FlashLogEntry`, `FlashLogSink`, `FlashPlatformLogSink` | `@FlashInternalApi` | Logging facade shared by the modules. Not consumer API. |
| `FlashProtocol`, `FlashEnvelope`, `FlashTextFraming` | `@FlashInternalApi` | Wire version + text/binary framing. Not consumer API. |

`FlashLogger` and `FlashIdGenerator` are `internal` — mentioned only because older drafts of
this document listed them as public.

---

## 3. Core Discovery Module (`:core:discovery`)

### `FlashDiscovery`
- **Stability:** Stable
- **Purpose:** Headless discovery contract. Controls advertising and browsing over LAN
  mDNS/NSD, with a Wi-Fi Direct transport behind the same interface.
- **Definition:**
  ```kotlin
  public interface FlashDiscovery {
      public val state: StateFlow<FlashDiscoveryState>
      public val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>>

      public suspend fun startDiscovery(): FlashResult<Unit>
      public suspend fun stopDiscovery(): FlashResult<Unit>
      public suspend fun startAdvertising(listenPort: Int): FlashResult<Unit>
      public suspend fun stopAdvertising(): FlashResult<Unit>
      public suspend fun stopAll(): FlashResult<Unit>
  }
  ```

- **Models:**
  ```kotlin
  public data class FlashDiscoveredEndpoint(
      val device: FlashDevice,
      val hostAddress: String,
      val port: Int,
      val serviceName: String,
  ) {
      public val deviceId: FlashDeviceId get() = device.id
      public val friendlyName: String get() = device.friendlyName
      public val transportType: FlashTransportType get() = device.transportType
  }

  public data class FlashDiscoveryState(
      val isDiscovering: Boolean = false,
      val isAdvertising: Boolean = false,
      val advertisedPort: Int = 0,
      val statusMessage: String = "Idle",
  )
  ```
- **Why an endpoint, not a device:** a peer is only connectable with an address and port, which
  a `FlashDevice` does not carry. `discoveredEndpoints` is therefore the flow to collect;
  `endpoint.device` is the identity inside it.
- **Lifecycle & threading:** suspend functions execute on `Dispatchers.IO`. Flow emissions are
  thread-safe and state-retaining. `stopAll()` is the single teardown call — it stops browsing
  and advertising on every transport.

---

## 4. Core Network Module (`:core:network`)

### `FlashNetwork`
- **Stability:** Stable
- **Purpose:** Listens, dials, and owns the live peer sessions. WebSocket mesh in the shipped
  implementation; the interface says nothing about the transport.
- **Definition:**
  ```kotlin
  public interface FlashNetwork {
      public val networkState: StateFlow<FlashNetworkState>
      public val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>>
      public val connectionHealth: StateFlow<FlashConnectionHealth>

      public suspend fun start(listenPort: Int = 0): FlashResult<Int>
      public suspend fun stop(): FlashResult<Unit>
      public suspend fun connect(device: FlashDevice): FlashResult<FlashSession>
      public suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession>
      public suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit>
      public fun retryConnection(): Boolean = false
  }
  ```
  `start(0)` binds an ephemeral port and returns the port actually bound — that value is what
  gets passed to `FlashDiscovery.startAdvertising`.

### `FlashSession`
- **Stability:** Stable
- **Purpose:** One live duplex connection to one peer.
- **Definition:**
  ```kotlin
  public interface FlashSession {
      public val peer: FlashDevice
      public val peerDeviceId: FlashDeviceId get() = peer.id
      public val connectionState: StateFlow<FlashConnectionState>
      public val transportType: FlashTransportType
      public val frameAcks: Flow<FrameAck> get() = emptyFlow()

      public suspend fun send(message: ByteArray): FlashResult<Unit>
      public suspend fun sendText(text: String): FlashResult<Unit>   // default: send(text.toByteArray())
      public fun disconnect(reason: String = "Normal disconnect")
  }
  ```
  `disconnect` is **not** suspending — it is a fire-and-forget close so it can be called from a
  callback or a `finally` block.
- **Models:**
  ```kotlin
  public enum class FlashConnectionState { Connecting, Connected, Disconnecting, Disconnected, Failed }

  public enum class FlashConnectionHealth {
      Offline,      // nothing reachable
      Connecting,   // peers visible or a dial in flight
      Connected,    // at least one healthy session
      Degraded,     // sessions exist but are unhealthy
  }

  public enum class FrameAckStage { SocketWritten, PeerAcknowledged }

  public data class FrameAck(val frameId: String, val stage: FrameAckStage, val atMs: Long)
  ```
  `frameAcks` is the two-stage delivery signal behind chat's sent/delivered ticks. It defaults to
  an empty flow, so a transport that cannot report acknowledgements is still a valid
  `FlashSession`.

### `WsFlashNetwork` — session freshness (ERROR-031)
- **Stability:** Evolving. The concrete WS implementation, reached directly by the app layer (and
  by `:core:engine`'s auto-connect gate) for things the `FlashNetwork` interface deliberately does
  not model.
- **Definition (the additions only):**
  ```kotlin
  public class WsFlashNetwork(
      /* … existing parameters … */
      private val nowMs: () -> Long = System::currentTimeMillis,
      private val onUsableNetwork: () -> Unit = {},
  ) : FlashNetwork {
      public fun hasLiveSession(deviceId: String): Boolean
  }
  ```
- **`hasLiveSession`** is true only when the peer has a session that is **carrying traffic**: open,
  `Connected`, and with an inbound frame of any kind (including a keepalive PONG) inside
  `STALE_SESSION_AFTER_MS`. Every recovery path must gate on this rather than on
  `activeSessions.containsKey(...)`: a session present in the map but dead on the wire used to veto
  its own replacement, which is the zombie-session bug (ERROR-031). Callers today:
  `AutoConnectGate.tryBegin` (via `Flash.kt`), `runAutoConnectSweep`, and the Wi-Fi-rejoin sweep.
- **`nowMs`** is injectable purely so the freshness rule is testable without waiting out a real
  staleness window.
- **`onUsableNetwork`** fires when the platform reports a usable network again. `:app` uses it to
  re-attempt a foreground-service promotion that was refused while backgrounded — Wi-Fi rejoin is
  one of the two moments the app is plausibly allowed to promote again (`onScreenOn` is the other).
- **`WsConnection.lastInboundAtMs`** (also public) exposes the keepalive's inbound stamp, which is
  what `hasLiveSession` reads.

---

## 5. Core Transfer Module (`:core:transfer`)

### `FlashTransferRepository`
- **Stability:** Stable
- **Purpose:** Chunked file transfer: offers, resume, checksum verification, progress.
- **Definition:**
  ```kotlin
  public interface FlashTransferRepository {
      public val activeTransfers: StateFlow<List<FlashTransfer>>

      public suspend fun sendFile(
          targetDevice: FlashDevice,
          fileUri: String,
          displayName: String,
          fileSize: Long,
      ): FlashResult<FlashTransferId>

      public suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit>
      public suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit>
      public suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit>

      // Inbound-offer gate. Defaults return Success so a lightweight implementation compiles.
      public suspend fun acceptIncoming(transferId: FlashTransferId): FlashResult<Unit>
      public suspend fun declineIncoming(transferId: FlashTransferId): FlashResult<Unit>
  }
  ```

- **Host-driven inbound hooks** — same interface, but called *by* the host's transport rather
  than by application code. Every one has a no-op/`false` default, so a consumer that only
  sends files can ignore them:
  ```kotlin
  public fun onInboundFrame(bytes: ByteArray): Boolean = false

  public fun onIncomingOffered(
      transferId: String, fileId: String, fileName: String,
      totalBytes: Long, peerName: String, peerDeviceId: String? = null,
  )
  public fun onIncomingStarted(
      transferId: String, fileId: String, fileName: String, totalBytes: Long,
      peerName: String, peerDeviceId: String? = null, localPath: String? = null,
  )
  public fun onIncomingProgress(transferId: String, bytesDone: Long)
  public fun onIncomingCompleted(transferId: String, verified: Boolean, localPath: String? = null)
  public fun onIncomingFailed(transferId: String, reason: String)

  // Retry discriminator, consulted on the session-started edge (see "Retry" below).
  public fun isResumableInboundRetry(transferId: String): Boolean
  ```
  `onInboundFrame` returns true when the bytes were a transfer frame it consumed, letting a host
  chain it ahead of its other binary handlers — the same convention as
  `FlashCalling.onInboundText` (§7).
- **Offer gate:** with `autoAcceptIncoming = false` (the default, §10) an inbound file arrives as
  `FlashTransferState.Offered` and stays there until `acceptIncoming` / `declineIncoming`.
- **Retry:** `resumeTransfer` on a `Failed` transfer restarts it from either side. A failed receive
  is torn down completely (sink closed, pipeline session dropped) while the partial file and the
  persisted done-set survive, so the sender's retry reaches the host as a *new* session. Hosts must
  therefore ask `isResumableInboundRetry(transferId)` on the session-started edge and resolve the
  destination sink immediately when it returns true; skipping that check re-opens the offer gate on
  a transfer the user already accepted, leaving the sink deferred and every chunk dropped.
  `Cancelled` is excluded — a declined offer is never auto-accepted because the sender tried again,
  which is also why `FlashTransferItemUi.retryable` is false for those rows (§12).
- **Models:**
  ```kotlin
  public data class FlashTransfer(
      val id: FlashTransferId,
      val peerName: String,
      val fileName: String,
      val direction: FlashTransferDirection,
      val bytesDone: Long,
      val bytesTotal: Long,
      val state: FlashTransferState,
      val speedBytesPerSec: Long = 0L,
      val etaSeconds: Long = 0L,
      val errorMessage: String? = null,
      val sourceUri: String? = null,      // outbound: the SAF uri being read
      val wireFileId: String? = null,     // protocol-level file id, for resume
      val peerDeviceId: String? = null,
      val localPath: String? = null,      // inbound: where it landed on disk
  )

  @JvmInline
  public value class FlashTransferId(public val value: String)

  public enum class FlashTransferDirection { Sending, Receiving }

  public enum class FlashTransferState {
      Offered, Queued, Transferring, Paused, Verifying, Completed, Failed, Cancelled,
  }
  ```

---

## 6. Core Messaging Module (`:core:messaging`)

### `FlashChatRepository`
- **Stability:** Experimental (`core-messaging` is published but its API may still change)
- **Purpose:** Chat list, one open conversation, sending, reactions, drafts, and selection-mode
  bulk actions. Backend-agnostic: `:ui:chat` renders whatever implements this.
- **Shape:** the repository exposes **two render-ready `StateFlow`s**, not entity collections. It
  is a presenter, not a DAO — the Room-backed implementation maps rows to UI models once, and the
  UI layer never touches a domain model. Everything that mutates is `fun` returning `Unit`; the
  result is observed on the state flows rather than returned.
- **Definition:**
  ```kotlin
  public interface FlashChatRepository {
      public val chatListState: StateFlow<FlashChatListUiState>
      public val conversationState: StateFlow<FlashConversationUiState>

      public fun openConversation(conversationId: String)
      public fun closeConversation()

      public fun sendText(text: String)
      public fun sendReply(text: String, replyToId: String, replyToPreview: String)
      public fun sendAttachment(
          conversationId: String, transferId: String, fileName: String, mimeType: String,
          sizeBytes: Long, localPath: String?,
          voiceDurationMs: Long = 0L, voiceAmplitudes: List<Int> = emptyList(),
      )
      public fun openAttachmentPicker()

      public fun saveDraft(text: String)
      public fun setTyping(isTyping: Boolean)
      public fun toggleReaction(messageId: String, emoji: String)
      public suspend fun searchMessageBodies(query: String): Set<String>

      public fun deleteMessage(localId: String)
      public fun deleteMessages(localIds: Set<String>)

      // Chat-list selection mode (UI-013)
      public fun enterListSelectionMode(conversationId: String)
      public fun toggleListSelection(conversationId: String)
      public fun clearListSelection()
      public fun archiveConversation(conversationId: String)
      public fun archiveConversations(ids: Set<String>)
      public fun unarchiveConversation(conversationId: String)
      public fun unarchiveConversations(ids: Set<String>)
      public fun deleteConversations(ids: Set<String>)
      public fun setConversationsPinned(ids: Set<String>, pinned: Boolean)
      public fun setConversationsMuted(ids: Set<String>, muted: Boolean)
      public fun markConversationUnread(conversationId: String)
      public fun markConversationsRead(ids: Set<String>)

      // Groups (ADR-030)
      public suspend fun createGroup(name: String, memberIds: Set<String>): FlashResult<String>
      public suspend fun addGroupMembers(groupId: String, memberIds: Set<String>): FlashResult<Unit>
      public suspend fun leaveGroup(groupId: String): FlashResult<Unit>
      public suspend fun groupMembers(groupId: String): List<FlashGroupMemberUi>

      // Group media (F4)
      public suspend fun beginGroupAttachment(
          groupId: String, recipientDeviceId: String, fileName: String, mimeType: String,
          sizeBytes: Long, wireFileId: String,
      ): Pair<String, String>?
      public suspend fun beginGroupAttachment(
          groupId: String, recipientDeviceId: String, messageId: String, transferId: String,
          wireFileId: String, fileName: String, mimeType: String, sizeBytes: Long,
      ): Boolean
      public fun sendGroupAttachment(
          conversationId: String, messageId: String, transferId: String, fileName: String,
          mimeType: String, sizeBytes: Long, localPath: String?,
          voiceDurationMs: Long = 0L, voiceAmplitudes: List<Int> = emptyList(),
      )
      public fun getRecipientTransferIds(messageId: String): Set<String>
  }
  ```
  Conversation ids are plain `String`s here (the peer device id for a 1:1 thread, the group id for
  a group thread), not `FlashConversationId` — that value class belongs to the domain models below.

- **Groups (ADR-030).** `createGroup` / `addGroupMembers` / `leaveGroup` return `FlashResult<String>`
  / `FlashResult<Unit>` and are the only members here that talk to the network: membership is an
  operation log fanned out to trusted members (`FLASH_GROUP`, see `docs/protocol.md`), so a failure
  is a real error and not a state the UI can read off a flow. Max six members including the creator;
  only trusted (paired) peers may be added; `leaveGroup` persists a tombstone so a replayed `add`
  cannot silently rejoin. `groupMembers` returns the roster for a group (names, online flags,
  `FlashMemberRole`); it is suspend because it reads the store, and a lightweight implementation
  returns an empty list.
- **Group media (F4).** Two `beginGroupAttachment` overloads exist and the *shorter one is the
  legacy compatibility hook*: it cannot supply the complete identity tuple, so its default returns
  `null`. The four-id overload (`messageId` + `transferId` + `wireFileId` + recipient) is the one a
  host calls per recipient; `sendGroupAttachment` then writes the sender's single row under the
  shared `messageId`, and `getRecipientTransferIds` maps that row back to the per-recipient
  transfers so progress can be aggregated.

- **Every method except the two state flows, `openConversation`, `closeConversation`,
  `sendText`, `openAttachmentPicker` and the three list-selection calls has a default body**, so a
  minimal implementation is small. `SampleFlashChatRepository` is a public in-memory
  implementation shipped for previews and for hosts substituting demo state (ADR-020). The group
  defaults are *inert rather than functional* — `createGroup`/`addGroupMembers`/`leaveGroup` return
  an `Unknown("Groups unavailable")` failure, `groupMembers` is empty, and both
  `beginGroupAttachment` overloads decline — so a lightweight host compiles and reports honestly
  instead of silently accepting a group it cannot deliver.

### UI state models (what the flows carry)
```kotlin
public data class FlashChatListUiState(
    val items: List<FlashChatListItemUi> = emptyList(),
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

public data class FlashConversationUiState(
    val header: FlashChatHeaderUiState,
    val messages: List<FlashMessageUi>,
    val draftText: String = "",
)
```
| Type | Purpose |
|---|---|
| `FlashMessageUi` | One row: text, images, files, voice notes, reactions, quoted reply, delivery status, grouping position, and `callEvent`. |
| `FlashCallEventUi`, `FlashCallEventKind` | A call row in the thread (UI-050). Kinds: `Outgoing`, `Incoming`, `Missed`, `Unanswered`; derived `missed` and `durationLabel` ("7:04"). |
| `FlashChatListItemUi` | One chat-list row: preview, unread count, pinned/muted/typing, presence, sort order. |
| `FlashChatHeaderUiState` | Conversation header: title, avatar, presence, transport, encryption, group member summary, `showCallActions`. |
| `FlashAttachment`, `FlashFileAttachmentUi`, `FlashImageAttachmentUi`, `FlashVoiceAttachmentUi`, `FlashAttachmentProgress`, `FlashFileTransferStatus` | Attachment rendering + live transfer state inside a bubble. |
| `FlashReaction`, `FlashQuotedReplyUi` | Reaction chips and the reply quote card. |
| `FlashGroupMemberUi`, `FlashMemberRole` | Group member rows (UI-029). |
| `FlashMessageGroupPosition`, `FlashListPreviewDelivery`, `FlashNetworkTransport` | Bubble grouping, list-preview tick, transport badge. |

### Domain models
`FlashMessage`, `FlashConversation`, `FlashConversationDetail`, `FlashMessageId`,
`FlashConversationId`, `FlashMessageStatus` are the persistence-facing shapes. They are public
and stable, but note that **the repository does not expose them** — it exposes the `*Ui` models
above. Use these when writing your own store, not when consuming `FlashChatRepository`.

```kotlin
public data class FlashMessage(
    val id: FlashMessageId,
    val conversationId: FlashConversationId,
    val senderId: FlashDeviceId,
    val senderName: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: FlashMessageStatus = FlashMessageStatus.Sent,
    val attachments: List<FlashAttachment> = emptyList(),
)

public enum class FlashMessageStatus { Pending, Sent, Delivered, Read, Failed }
```

---

## 7. Core Calling Module (`:core:calling`)

1:1 voice and video calling (C7, ADR-025). Media rides WebRTC; signaling rides whatever duplex
text transport the host already owns.

**Reachable through `Flash.create`, but opt-in and host-built.** `FlashEngine` exposes
`engine.calls` and `engine.attachCalling(...)`; the facade routes inbound `FLASH_CALL` frames to the
attached engine and drives its signaling-recovery window from the sessions it observes. The engine
itself is the host's to construct, because calling needs three things only an app can provide — a
signaling channel it already owns, runtime `RECORD_AUDIO`/`CAMERA` grants, and a
`microphone|camera` foreground service declared in its own manifest — so `Flash.create` wires no
calling factory and `calls` is null until [`attachCalling`](#flashengine) is called. A consumer
depends on `core-calling` directly (it is `compileOnly` on the engine — see §10) and wires the
seams below. Permission list: README → Permissions.

### `FlashCalling`
- **Stability:** Experimental
- **Purpose:** the entire call surface — place, answer, end, and control at most one call at a
  time.
- **Definition:**
  ```kotlin
  public interface FlashCalling {
      public val activeCall: StateFlow<FlashCallUiState?>
      public val ongoingGroupCalls: StateFlow<Map<String, OngoingGroupCallUi>>
      public val media: FlashCallMedia?

      public suspend fun startCall(peerId: String, peerName: String, video: Boolean): Boolean
      public suspend fun accept(): Boolean
      public suspend fun decline(): Boolean
      public suspend fun hangUp(): Boolean

      public fun toggleMute(): Boolean
      public fun toggleCamera(): Boolean
      public suspend fun switchCamera()
      public fun setSpeaker(on: Boolean)

      // Group calls
      public suspend fun startGroupCall(
          groupId: String, groupName: String, memberIds: List<String>, video: Boolean,
      ): Boolean
      public suspend fun joinGroupCall(
          groupId: String, callId: String, memberIds: List<String>, video: Boolean = false,
      ): Boolean
      public suspend fun queryGroupCall(groupId: String, memberIds: List<String>)

      public suspend fun onInboundText(peerId: String, text: String): Boolean
      public fun onSignalingLost(peerId: String)
      public fun onSignalingRestored(peerId: String)
  }
  ```
- **Why `Boolean` and not `FlashResult`:** every failure here is one of two things the caller
  already knows how to handle — "there is no call to act on" or "local media could not be
  acquired" — and both collapse to false. There is no error payload a call UI would render, so
  the module does not invent one. This is the one deviation from §1.5 in the published API.
- **`activeCall` is the only thing a navigation layer needs.** It goes non-null on
  DIALING/RINGING and back to null a short grace window after ENDED, so a host pushes and pops
  its call route off that single flow — no manual push/pop pairing to get wrong.

### The two host seams

Signaling is plain text (`FLASH_CALL …`, see `docs/protocol.md`), so any duplex text channel
works. The module never opens a socket of its own; it is handed both directions:

- **outbound** — a `sendFrame(peerId, text) -> Boolean` lambda supplied at construction. Every
  frame the module emits goes through it. Always the host's, engine or not.
- **inbound** — `onInboundText(peerId, text)` returns true when the text was a `FLASH_CALL` frame
  it consumed, false when it is not a call frame at all. **A `FlashEngine` consumer does not call
  this one.** `Flash.create` recognizes the `FLASH_CALL` prefix itself and routes the frame to the
  attached engine through `FlashEngine.onInboundCallText`, which is the same call without the
  `FlashCalling` type on the dispatch path (`compileOnly`, §10) — and a recognized call frame is
  never handed to another handler whether or not anything consumed it. Only a host that depends on
  `core-calling` *without* the umbrella chains it ahead of its own text handlers itself:
  `if (calling.onInboundText(id, t)) return`.

`onSignalingLost(peerId)` closes the loop for transport death — without it a call sits waiting
for frames that can no longer arrive. Call it from the transport's disconnect callback. It opens a
**recovery window** rather than ending the call (ERROR-033): a mesh Wi-Fi roam takes the signaling
session down as a matter of course, and the transport redials it in seconds, so ending the call on
the spot made a two-second radio outage indistinguishable from a hang-up. `onSignalingRestored`
closes that window and lets the session renegotiate — the ICE restart offer that rebuilds the media
path needs this channel to travel on, so a host that calls only `onSignalingLost` has a call that
survives the grace period and then dies anyway. Call `onSignalingRestored` whenever a session comes
up, not only after a loss. **Also already done for a `FlashEngine` consumer:** the facade watches
the same live sessions the rest of the engine does, so `FlashEngine.onCallSignalingLost` /
`onCallSignalingRestored` fire on every session-down/up edge without the host wiring anything.

`attachCalling` can only be called once — a second attach is ignored, like `attachPtt`. Detaching
(`detachCalling()`, and `close()`) stops routing without ending a call: `FlashCalling` exposes no
shutdown, and the media, audio route and foreground service belong to the host, so hang-up stays
with the host's own `hangUp()`.

There is a **third, optional seam**: `CallCoordinator(prioritiseVoice: () -> Boolean = { true })`.
It gates the audio-priority work of ERROR-031 / D8 — the audio sender's `Priority.HIGH` /
high `bitratePriority`, video's demotion to `Priority.LOW`, and the adaptive governor that steps
video down when audio degrades. It is a **lambda, not a value**, for two reasons: `:core:calling`
must not depend on persistence (ADR-024), and it is read once per stats sample, so flipping the
user's "Prioritise voice quality" switch takes effect on the call in progress rather than the next
one. Default `{ true }`; returning false restores symmetric treatment of the two streams. The
video bitrate ceiling is *not* gated on it — a ceiling is written into the description both ends
must agree on, so wire content must not depend on which device has a switch flipped.

And a **fourth**: `CallCoordinator(performanceMode: () -> FlashPerformanceMode = { HIGH })`
(ERROR-033). Capture resolution, Opus packetization and the call-recovery windows all come from the
tier, so a 2 GB API-27 handset stops asking for 1080p30 and stops paying ~100 packets/second of
header overhead for 25 kbit/s of speech. A lambda for the same two reasons, read once per session,
and defaulted to `HIGH` — whose profiles *are* the pre-tiering constants, so a caller that does not
tier behaves exactly as before. Unlike `prioritiseVoice` this one **does** change wire content, which
is why the SDP work is split in two: `CallSdp.tuneLocal` asserts our own tier into the description we
send, and `tuneRemote` reads the peer's declaration and reconciles it by taking the longer frame and
the smaller ceiling of the two. Both endpoints therefore converge on byte-identical parameters
whichever of them offered, even when their tiers differ.

### Group calling (N participants)

Group calls ride the same `FLASH_CALL` prefix and the same two seams as a 1:1 call, with five
extra actions (`ginvite`, `gaccept`, `gdecline`, `gjoin`, `ghangup`) plus `gpresence`/`gquery` for
"is a call already running in this group?" — see the `docs/protocol.md` Calling section. The wire
difference matters to a host: the *sender* is not always the caller, so receivers must resolve the
participant from the frame's `from` and not from the transport peer they received it through.

- **`ongoingGroupCalls`** is `StateFlow<Map<String, OngoingGroupCallUi>>` keyed by `groupId`.
  Non-empty means some peer announced a live call in that group, which is what a conversation
  header renders its "rejoin" banner from. It is a separate flow from `activeCall` on purpose: a
  device can be *in* a group call and still be told about another group's call.
- **`startGroupCall(groupId, groupName, memberIds, video)`** invites every member in `memberIds`
  and returns false when a call is already live or local media could not be acquired. The session
  is a full mesh: each pair negotiates its own `PeerConnection` leg, with a glare-free offer
  election so two members starting at once do not both offer.
- **`joinGroupCall(groupId, callId, memberIds, video)`** joins a call announced through
  `ongoingGroupCalls` (the banner's action), and **`queryGroupCall(groupId, memberIds)`** fans one
  `gquery` out to the group's trusted members — used when presence was missed but the group is
  open. Both are no-ops when the member list has no trusted peer other than the local device.
- **Leaving is per leg.** A member dropping out closes only its own legs; the call continues for
  everyone else, and the sole remaining member gets a solo-grace window before the call ends
  rather than an immediate teardown.
- **`activeCall` is still the single call in flight** (1:1 or group): a group call publishes
  `FlashCallUiState.peerId = groupId` and `peerName = groupName`, so the same `FlashCallScreen` and
  the same navigation push/pop logic work for both. §13's `FlashCallScreen` signature is unchanged
  by group support.
- **`OngoingGroupCallUi`** (in `:core:calling`, `model` package):
  ```kotlin
  public data class OngoingGroupCallUi(
      public val callId: String,
      public val groupId: String,
      public val groupName: String,
      public val initiatorId: String,
      public val video: Boolean,
      public val participantCount: Int = 1,
      public val lastSeenTimestamp: Long = System.currentTimeMillis(),
  )
  ```
  `lastSeenTimestamp` is local bookkeeping, not wire data: the coordinator sweeps the map every
  5 s and drops any entry not re-announced within 12 s, so a stale banner cannot outlive the call
  that produced it.

### `FlashCallMedia`
- **Stability:** Experimental
- **Purpose:** renderable tracks and live quality metrics for the active call. Split out from
  `FlashCallUiState` because tracks and stats are live platform objects, not data a `data class`
  can carry.
- **Definition:**
  ```kotlin
  public interface FlashCallMedia {
      public val stats: StateFlow<FlashCallStats?>
      public val localVideoTrack: StateFlow<VideoTrack?>
      public val remoteVideoTrack: StateFlow<VideoTrack?>
  }
  ```
- **Read-only by design:** controls live on `FlashCalling`, so a UI layer can bind video and a
  latency readout without also being handed the ability to mutate the call.
- **Tracks are flows, not values,** because a track's identity changes mid-call —
  renegotiation, a camera flip, or the peer enabling video all swap the object. A renderer must
  re-bind on every emission and must **not** release its renderer on a track change:
  `EglRenderer.release()` is terminal and leaves the surface permanently black.

> **The `VideoTrack` exception (§1.2).** `VideoTrack` is `com.shepeliev.webrtckmp.VideoTrack` —
> the one third-party type Flash lets cross a published boundary. A renderer has to be handed
> the real track, and any wrapper would have to expose it again to be useful. `:core:calling`
> declares webrtc-kmp with `api()` so consumers get the type transitively; the trade-off is
> ~30 MB of native WebRTC in the APK, which is why calling is a separate artifact rather than
> part of `core-engine`.

### `FlashCallUiState`
- **Stability:** Experimental
- **Purpose:** immutable snapshot of the active call.
- **Definition:**
  ```kotlin
  public data class FlashCallUiState(
      public val callId: String,
      public val peerId: String,          // doubles as the conversation id
      public val peerName: String,
      public val direction: FlashCallDirection,
      public val video: Boolean,
      public val state: FlashCallState,
      public val endReason: FlashCallEndReason? = null,
      public val connectedAt: Long? = null,   // epoch ms when media started
      public val micMuted: Boolean = false,
      public val cameraOff: Boolean = false,
      public val speakerOn: Boolean = false,
      public val videoLimitReason: String? = null,
  )
  ```
  `videoLimitReason` is non-null while the audio-protective governor has traded video quality away
  to protect the voice stream (ERROR-031 / D8) — e.g. "Video paused to protect the call audio". It
  is user-facing copy, not a code: a picture that gets worse **on purpose** has to be
  distinguishable from a picture that gets worse because the app is broken, so a UI that renders
  video should render this string too. Null means no concession is in effect, including whenever
  the "Prioritise voice quality" setting is off. Distinct from `cameraOff`, which is the *user's*
  choice and is never set by the governor.

### `FlashCallState`, `FlashCallDirection`, `FlashCallEndReason`
- **Stability:** Experimental
- **Definition:**
  ```kotlin
  public enum class FlashCallState { DIALING, RINGING, CONNECTING, ACTIVE, ENDED }
  public enum class FlashCallDirection { OUTGOING, INCOMING }
  public enum class FlashCallEndReason { NORMAL, DECLINED, NO_ANSWER, DISCONNECTED, ERROR }
  ```
- One state machine per call. `DIALING` is outgoing-only, `RINGING` incoming-only; both converge
  on `CONNECTING` once accepted, then `ACTIVE` when media flows. `ENDED` is terminal and always
  carries an `endReason`.

### `FlashCallStats`
- **Stability:** Experimental
- **Purpose:** transport metrics sampled from `PeerConnection.getStats()` about once a second —
  the source of the call screen's latency badge.
- **Definition:**
  ```kotlin
  public data class FlashCallStats(
      public val rttMs: Int? = null,
      public val audioJitterMs: Int? = null,
      public val videoJitterMs: Int? = null,
      public val fps: Int? = null,
      public val remoteWidth: Int? = null,
      public val remoteHeight: Int? = null,
      public val inboundKbps: Int? = null,
      public val outboundKbps: Int? = null,
      public val sendWidth: Int? = null,
      public val sendHeight: Int? = null,
      public val packetLoss: Double? = null,   // fraction 0..1
  ) {
      public val hasData: Boolean
      public val remoteResolutionLabel: String?   // "1080p"-style, null before first frame
  }
  ```
- **Every field is nullable and `null` means "not measured yet", never zero.** WebRTC publishes
  each report only once it exists: RTT needs the first RTCP round trip on the selected candidate
  pair, framerate and resolution need a decoded frame, and bitrate needs two samples to
  difference. Gate the readout on `hasData` rather than rendering zeros.
- `sendWidth`/`sendHeight` are the encoder's current frame size, which is how adaptive
  downscaling becomes visible: capture is requested at 1080p and the encoder steps down under
  bandwidth or CPU constraint.

### `FlashCallLogEntry`
- **Stability:** Experimental
- **Purpose:** a finished call, handed to the host so it can write a chat row.
- **Definition:**
  ```kotlin
  public data class FlashCallLogEntry(
      public val callId: String,
      public val peerId: String,          // doubles as the conversation id
      public val peerName: String,
      public val direction: FlashCallDirection,
      public val video: Boolean,
      public val endReason: FlashCallEndReason,
      public val durationMs: Long,        // how long media flowed; 0 when never connected
      public val endedAt: Long,           // epoch ms
  ) {
      public val missed: Boolean          // durationMs <= 0 && direction == INCOMING
  }
  ```
- **Deliberately messaging-free.** `:core:calling` must not depend on `:core:messaging`
  (port/adapter inversion, ADR-024), so this is a plain record: the host receives it and writes
  the chat row itself.
- **No wire frame carries this.** Both devices already hold every field locally when a call
  ends, so each writes its own row — no protocol change was needed. Consequence: `missed`
  cannot distinguish "declined" from "the caller gave up", because the wire does not (both end
  `NORMAL`). They read the same way in a call log.

### Cost and dependency notes
- `api(libs.webrtc.kmp)` — webrtc-kmp is re-exported, so `VideoTrack` needs no extra
  declaration downstream.
- **~30 MB of native WebRTC** per supported ABI. A consumer that does not call should simply not
  depend on this module; nothing else in Flash pulls it in — `:core:engine` is the closest thing to
  an exception and it is `compileOnly`, which is why a `core-engine` consumer still has to add
  `core-calling` to place a call and still has no WebRTC on its runtime classpath without it.
- Ships **no** `AndroidManifest.xml`, like every other library module (§1.7) — the permissions
  and the foreground service are the app's to declare.

---

## 8. Core Security Module (`:core:security`)

Identity, trust, pairing and the cryptographic primitives underneath them (C2, D4).
`:core:engine` declares this module with `api()`, so `FlashEngine.trustStore` hands back a
`FlashTrustStore` without a consumer adding a dependency.

### `FlashCrypto`
- **Stability:** Stable
- **Purpose:** the P-256 primitives every other module signs and key-agrees with.
- **Definition:**
  ```kotlin
  public interface FlashCrypto {
      public val identityPublicKey: PublicKey
      public fun sign(data: ByteArray): ByteArray
      public fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean
      public fun generateEphemeralEcdhKeyPair(): KeyPair
      public fun ecdhSessionKey(
          selfEphemeralPrivateKey: PrivateKey,
          peerEphemeralPublicKey: PublicKey,
      ): ByteArray

      public companion object {
          public const val IDENTITY_KEY_ALIAS: String        // "flash_identity"
          public const val EC_CURVE: String                  // "secp256r1"
          public const val ECDSA_SIGNATURE_ALGORITHM: String // "SHA256withECDSA"
          public const val KEY_AGREEMENT_ALGORITHM: String   // "ECDH"
          public const val SESSION_KEY_SIZE_BYTES: Int       // 32 — AES-256
          public val EMPTY_SALT: ByteArray
          public val SESSION_INFO: ByteArray                 // "flash-e2e-v<VERSION>"
      }
  }
  ```
- **Two key classes, not one.** The identity key is an ECDSA signing key that lives in
  AndroidKeyStore and is non-exportable; session keys are ephemeral software ECDH keypairs,
  memory-only, one per pairing. The split is forced: AndroidKeyStore key agreement needs
  `PURPOSE_AGREE_KEY`, which only exists from API 31, and `minSdk` is 24 — so below 31 the
  identity key cannot do agreement at all.
- **`verify` never throws.** Malformed peer key material returns false, because it arrives off
  the wire.
- The raw ECDH secret is always run through HKDF-SHA256 bound to the protocol version before it
  becomes an AES key (RFC 5869 §3.3 — the extract step is not optional for DH output).
- Implementations (`KeystoreFlashCrypto`, `SoftwareFlashCrypto`) are wiring details, and the
  HKDF helper and the E2E frame codec are `internal`.

### `FlashFingerprint`
- **Stability:** Stable
- **Purpose:** the one way a public key becomes something a human can compare.
- **Definition:**
  ```kotlin
  public object FlashFingerprint {
      public fun fingerprint(publicKeyEncoded: ByteArray): ByteArray
      public fun formatHexGroups(bytes: ByteArray): String
      public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean
  }
  ```
- `constantTimeEquals` is exposed rather than kept internal so callers comparing fingerprints or
  code hashes are not tempted to use `contentEquals`.

### `FlashIdentity` / `FlashIdentityStore`
- **Stability:** Stable
- **Purpose:** the persistent local device identity used by discovery, pairing and transfer.
- **Definition:**
  ```kotlin
  public data class FlashIdentity(
      public val deviceId: FlashDeviceId,
      public val friendlyName: String,
  )

  public interface FlashIdentityStore {
      public fun getIdentity(): FlashIdentity
      public fun updateFriendlyName(name: String): FlashResult<Unit>
  }
  ```
- `getIdentity()` is generate-on-first-read: there is no create call, so a consumer cannot
  observe a device without an id.

### `FlashTrustStore`
- **Stability:** Stable
- **Purpose:** which peers this device has pinned. Reachable as `FlashEngine.trustStore`.
- **Definition:**
  ```kotlin
  public interface FlashTrustStore {
      public fun isTrusted(deviceId: FlashDeviceId): Boolean
      public fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit>
      public fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit>
      public fun getTrustedPeers(): Map<FlashDeviceId, String>

      // String overloads, defaulted — convenience for ids that arrive off the wire
      public fun isTrusted(deviceId: String): Boolean
      public fun trustPeer(deviceId: String, friendlyName: String): FlashResult<Unit>
      public fun revokeTrust(deviceId: String): FlashResult<Unit>
  }
  ```
- The `String` overloads have default bodies that wrap in `FlashDeviceId`, so an implementer only
  has to write the four typed members.

### `FlashPairingProtocol`
- **Stability:** Stable
- **Purpose:** the numeric-comparison pairing handshake (UI-032), driven entirely by its host.
- **Definition:**
  ```kotlin
  public interface FlashPairingProtocol {
      public val events: Flow<FlashPairingEvent>
      public val session: StateFlow<PairingSessionState>

      public fun beginRequest(
          peerDeviceId: String,
          peerName: String?,
          peerFingerprintHex: String,
      ): FlashResult<Unit>

      public suspend fun respondAccept(): FlashResult<Unit>
      public suspend fun respondDecline(): FlashResult<Unit>

      public suspend fun onFrame(frame: FlashPairingFrame)
      public fun onTick(nowMs: Long)
  }
  ```
- **Owns no coroutines, no scope and no clock.** `onFrame` is where the transport pushes decoded
  inbound frames; `onTick(nowMs)` is where the owning engine drives expiry from its own time
  source. That is what makes every transition path unit-testable without Android or virtual time.
- Outbound frames go to a `sendFrame` sink injected at construction. The sink must be
  enqueue-only — it is invoked from non-suspend contexts, so real socket I/O belongs on the
  transport's own queue.
- **`DefaultFlashPairingProtocol` is public** — the third documented exception to §1.1, alongside
  `FlashSettingsDataStore` and `DefaultFlashEngine`. It takes local identity strings, the
  ephemeral-key provider, the frame sink, a `FlashTimeSource` and `PairingTimeouts`; there is
  nothing to hide behind a factory because every one of those is a host decision.

### `FlashPairingEvent`
- **Stability:** Stable
- **Purpose:** what the UI listens to. `Expired` is deliberately separate from `Failed`:
  a timeout means nobody misbehaved and a retry is safe, while `Failed` means a protocol
  violation (a code-hash mismatch) that should not be retried silently.
- **Definition:**
  ```kotlin
  public sealed interface FlashPairingEvent {
      public data class RequestReceived(
          val requestId: String, val peerDeviceId: String, val peerName: String,
          val code6: String, val expiresAtMs: Long,
      ) : FlashPairingEvent
      public data class PeerAccepted(val requestId: String) : FlashPairingEvent
      public data class PeerDeclined(val requestId: String?) : FlashPairingEvent
      public data class Expired(val requestId: String?) : FlashPairingEvent
      public data class Confirmed(
          val requestId: String, val fingerprintHex: String, val ephemeralPubKey: ByteArray,
      ) : FlashPairingEvent
      public data class Failed(val requestId: String?, val reason: String) : FlashPairingEvent
  }
  ```

### `PairingPhase` / `PairingSessionState` / `PairingTimeouts`
- **Stability:** Stable
- **Definition:**
  ```kotlin
  public enum class PairingPhase {
      Idle, RequestReceived, AwaitingLocalDecision, AwaitingPeerConfirmation,
      Confirmed, DeclinedByPeer, Expired, Failed,
  }

  public data class PairingTimeouts(
      public val requestExpiryMs: Long = DEFAULT_REQUEST_EXPIRY_MS,   // 30_000
      public val decisionWindowMs: Long = DEFAULT_REQUEST_EXPIRY_MS,
  )

  public data class PairingSessionState(
      public val phase: PairingPhase = PairingPhase.Idle,
      public val requestId: String? = null,
      public val peerDeviceId: String? = null,
      public val peerName: String? = null,
      public val peerFingerprintHex: String? = null,
      public val peerEphemeralPublicKey: ByteArray? = null,
      public val code6: String? = null,
      public val expectedCodeHashHex: String? = null,
      public val expiresAtMs: Long? = null,
      public val decisionDeadlineMs: Long? = null,
      public val peerAccepted: Boolean = false,
      public val failureReason: String? = null,
  ) {
      public companion object { public val IDLE: PairingSessionState }
  }
  ```
- Two clocks, not one: `expiresAtMs` bounds the whole request, `decisionDeadlineMs` bounds how
  long the local user may deliberate once the dialog is actually on screen. They default to the
  same 30 s so both sides agree initially.

### `FlashPairingFrame`
- **Stability:** Stable
- **Purpose:** the four wire frames of the handshake, as decoded data.
- **Definition:**
  ```kotlin
  public sealed interface FlashPairingFrame {
      public val requestId: String

      public data class PairRequest(
          override val requestId: String, val senderDeviceId: String, val senderName: String,
          val senderModel: String, val senderFingerprintHex: String,
          val senderEphemeralPublicKey: ByteArray, val createdAt: Long,
      ) : FlashPairingFrame
      public data class PairAccept(override val requestId: String) : FlashPairingFrame
      public data class PairConfirm(
          override val requestId: String, val codeHashHex: String,
      ) : FlashPairingFrame
      public data class Paired(
          override val requestId: String, val peerFingerprintHex: String,
          val peerEphemeralPublicKey: ByteArray,
      ) : FlashPairingFrame
  }
  ```
- The frames carrying `ByteArray` override `equals`/`hashCode` with `contentEquals`, so they
  compare by value like every other frame — `data class` alone would not.
- `NumericComparisonCode` (the 6-digit derivation), `TofuPolicy` and `LegacyTrustMigration` are
  `internal`: the code is derived identically on both sides from material already in the frames,
  so there is nothing for a caller to configure.

---

## 9. Core Persistence Module (`:core:persistence`)

Room + SQLCipher storage, DataStore preferences, and a pure retention policy (C1, D2).

**This is the module the abstractions rule bends for.** Room's generated code needs a real
`abstract class`, and a DataStore is one file per instance, so those two are concrete by
construction. The schema leaks further than one would like: all 11 DAOs and all 11 `@Entity`
types are `public`, because `FlashDatabase` is public and under `explicitApi()` a public accessor
may not expose an `internal` return type. They are nonetheless **not** part of the intended
surface — persistence is reached through the ports in `:core:messaging` and `:core:transfer`
(ADR-024), and nothing outside `:core:engine` and `:app` depends on this module. Treat a DAO or
an entity appearing in consumer code as a layering bug, not as a supported call.

### `FlashDatabase`
- **Stability:** Internal-ish — reachable, but reserved for `:core:engine` wiring.
- **Definition:**
  ```kotlin
  @Database(entities = [ /* 11 entities */ ], version = 3, exportSchema = true)
  public abstract class FlashDatabase : RoomDatabase() {
      public abstract fun messageDao(): MessageDao
      // conversationDao, receiptDao, outboxDao, transferDao, transferChunkDao,
      // recentSearchDao, trustedPeerDao, reactionDao, draftDao, readCursorDao

      public companion object {
          public const val DATABASE_NAME: String = "flash.db"
          public const val DATABASE_VERSION: Int = 3
      }
  }
  ```
- The DAO accessors and their return types are public only because Room plus `explicitApi()`
  leaves no alternative. The schema is still an implementation detail by convention: it is bound
  once, in `:core:engine`, into the adapters that implement the messaging and transfer ports.
- One DAO member carries a behavioural contract worth naming, because it exists to protect an
  invariant a caller cannot see (ERROR-031):
  ```kotlin
  public suspend fun updateStatusIfUnacknowledged(localId: String, status: String)
  ```
  Since the outbox now keeps its row until the peer's `DeliveryReceipt` arrives, the send loop
  writes `SENT` on *every* successful socket write — including retries of a message the peer has
  already acknowledged. An unconditional `UPDATE … SET status = 'SENT'` would walk a double tick
  back to a single one. This variant is a no-op once the row reached `DELIVERED` or `READ`, so
  delivery status only ever moves forwards. Prefer it to `updateStatus` for anything on a retry
  path.
- v2 added the attachment columns to `MessageEntity`, v3 the reply columns.

### `FlashDatabaseOpener` / `PassphraseProvider`
- **Stability:** Stable
- **Definition:**
  ```kotlin
  public fun interface PassphraseProvider {
      public fun passphrase(): ByteArray
  }

  public object FlashDatabaseOpener {
      public fun openEncrypted(
          context: Context,
          passphraseProvider: PassphraseProvider,
          vararg migrations: Migration,
      ): FlashDatabase

      public fun openInMemory(context: Context): FlashDatabase   // JVM/Robolectric tests only
  }
  ```

- **The module stays Keystore-free (R2).** `PassphraseProvider` is a `fun interface` precisely so
  the AndroidKeyStore unwrapping lives in `:app`; this module only ever sees raw bytes, and never
  logs them.
- The factory retains the passphrase array until first open, so a caller must not zero or reuse
  the buffer immediately after returning from the provider.
- **`openEncrypted` has no destructive fallback.** An unknown schema version fails fast;
  migrations are passed in explicitly. `openInMemory` *does* allow destructive fallback, and that
  is test-only — the line is commented as such in source and must not be copied into the
  encrypted path.

### `FlashMigrations`
- **Stability:** Stable
- **Definition:**
  ```kotlin
  public object FlashMigrations {
      public val MIGRATION_1_2: Migration
      public val MIGRATION_2_3: Migration
      public val ALL: Array<Migration>
  }
  ```
- Pass `*FlashMigrations.ALL` to `openEncrypted`. Migrations are handed in rather than registered
  internally so a consumer can add its own without forking the opener.

### `FlashSettingsDataStore`
- **Stability:** Stable
- **Purpose:** every user preference Flash persists. Reachable as `FlashEngine.settings`.
- **Definition:**
  ```kotlin
  public class FlashSettingsDataStore(
      produceFile: () -> File,
      scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
  ) {
      public object Keys { /* 15 Preferences.Key<*> */ }

      public companion object {
          public const val THEME_MODE_SYSTEM: String      // "system" | "light" | "dark"
          public const val MOTION_OVERRIDE_SYSTEM: String  // "system" | "on" | "off"
          public const val DEFAULT_RETENTION_DAYS: Int = 365
      }

      // Reads — every one a Flow with a graceful default
      public val themeMode: Flow<String>
      public val dynamicAccent: Flow<Boolean>
      public val hapticsEnabled: Flow<Boolean>
      public val reduceMotionOverride: Flow<String>
      public val soundsEnabled: Flow<Boolean>
      public val autoAcceptTrusted: Flow<Boolean>
      public val backgroundTransfers: Flow<Boolean>
      public val autoDownloadVoice: Flow<Boolean>
      public val autoDownloadImage: Flow<Boolean>
      public val autoDownloadVideo: Flow<Boolean>
      public val autoDownloadFile: Flow<Boolean>
      public val prioritiseVoiceQuality: Flow<Boolean>   // default TRUE
      public val saveLocationUri: Flow<String?>
      public val retentionDays: Flow<Int>
      public val displayName: Flow<String>

      // Writes — one suspend setter per key (setThemeMode, setRetentionDays, …)
  }
  ```

- **`prioritiseVoiceQuality` is the only preference here that defaults to `true` on a
  feature-enabling key** (ERROR-031 / D8). The asymmetry is deliberate: a caller who cannot be
  understood has lost the call, whereas a caller whose picture went soft for a few seconds has not.
  It reaches `:core:calling` as a `() -> Boolean` lambda rather than as a dependency — see §7.
- **A read never fails.** An `IOException` on the DataStore file is caught and mapped to
  `emptyPreferences()`, so every flow falls back to its default rather than cancelling the
  collector.
- **Key strings are migration-sensitive.** They are stable snake_case with no prefix; renaming one
  silently resets the user's value (C1.5). `Keys` is public so a consumer can migrate or export
  them deliberately.
- `produceFile` is a lambda, and `scope` is injectable, so an app can point this at its own DI
  file location and application scope. The default scope exists for standalone and JVM use.

### `DiscoveryModeSetting`
- **Stability:** Stable
- **Purpose:** the discovery-mode selection, deliberately a separate class with its own key so it
  never contends with `FlashSettingsDataStore` for edits.
- **Definition:**
  ```kotlin
  public class DiscoveryModeSetting(private val dataStore: DataStore<Preferences>) {
      public companion object {
          public const val KEY_NAME: String = "flash_discovery_mode"
          public const val DEFAULT: String = "STANDARD"
          public val VALID: List<String> =
              listOf("STANDARD", "GHOST", "BOOST", "ECO", "RECEIVE_KIOSK")
      }

      public val discoveryMode: Flow<String>
      public suspend fun setDiscoveryMode(canonicalName: String)   // throws on unknown value
  }
  ```
- **It persists a canonical `String`, not `FlashDiscoveryMode`.** The enum lives in
  `:core:discovery`, and depending on it from here would couple two modules that must evolve
  independently. The app layer owns the `String` ↔ enum mapping.
- **Strict writer, lenient reader.** The setter validates against `VALID` and throws on anything
  else; the reader falls back to `DEFAULT` when it finds a value a future app version wrote.
  Downgrade-safety beats strictness — an older build must not crash on a newer preferences file.

### `RetentionPolicy` / `PrunableEntry` / `PrunableSource`
- **Stability:** Stable
- **Purpose:** decide what history is old enough to delete, as a pure function.
- **Definition:**
  ```kotlin
  public data class PrunableEntry(
      public val localId: String,
      public val createdAt: Long,
      public val protected: Boolean,
  )

  public object RetentionPolicy {
      public const val MILLIS_PER_DAY: Long = 86_400_000L
      public fun cutoffMsOrNull(nowMs: Long, retentionDays: Int): Long?
      public fun eligibleForDeletion(
          nowMs: Long, retentionDays: Int, entries: List<PrunableEntry>,
      ): List<String>
  }

  public interface PrunableSource {
      public suspend fun entriesOlderThan(cutoffMs: Long): List<PrunableEntry>
      public suspend fun delete(ids: List<String>)
  }
  ```

- **`retentionDays <= 0` disables pruning entirely** — it does not mean "prune everything". A
  zero window read literally would be a destructive default, and the product meaning of 0 is
  "keep history forever". A delete-all feature would have to be an explicit user action.
- **The comparison is strict** (`createdAt < cutoff`), so an entry created exactly at the cutoff
  instant survives one more sweep. With whole-day granularity that gives the intuitive reading:
  a record lives out its full retention day.
- `PrunableEntry` carries no Room types, which is what keeps `RetentionPolicy` unit-testable
  without a database. `protected` is the pinned-conversation flag (C1.6) — the adapter behind
  `PrunableSource` resolves it, the policy just honours it.
- `PrunableSource.delete` must be idempotent: the scheduler runs at-least-once, so redelivering
  the same id list has to be harmless.

---

## 10. Core Engine Module (`:core:engine`)

The facade that turns eight modules into one object (C7.0, ADR-010). This is the only module that
knows how the others wire together; it declares `:core:discovery`, `:core:network`,
`:core:transfer`, `:core:messaging`, `:core:security`, `:core:persistence` and `:core:common` with
`api()`, so a consumer of `core-engine` gets every published type transitively and adds one
dependency, not eight. Two more are declared on the **android** target rather than in commonMain,
because both are plain AGP Android libraries with no JVM variant and a commonMain entry breaks
`:core:engine`'s `jvm()` target at variant selection (ERROR-049): `:core:ptt` as `api`, and
`:core:calling` as **`compileOnly`** (ADR-033) — `FlashCalling` is part of the engine's public API,
but the dependency is not published, so `core-engine` never drags native WebRTC into a consumer that
does not call. Nothing outside `androidMain` names either module.

### `Flash` / `FlashConfig`
- **Stability:** Stable
- **Purpose:** one call that assembles and starts a working engine.
- **Definition:**
  ```kotlin
  public object Flash {
      public fun create(context: Context, config: FlashConfig = FlashConfig()): FlashEngine
  }

  public data class FlashConfig(
      public val displayName: String? = null,
      public val enableResume: Boolean = true,
      public val autoAcceptIncoming: Boolean = false,
      public val receivedFilesDir: File? = null,
  )
  ```
  ```kotlin
  val engine = Flash.create(context, FlashConfig(autoAcceptIncoming = true))
  // …observe engine.discovery.discoveredEndpoints, then:
  engine.transfers.sendFile(peerDevice, uri, "photo.jpg", sizeBytes)
  engine.close()   // onDestroy / ViewModel.onCleared
  ```
- **Every field defaults**, so `Flash.create(context)` is a complete engine. `displayName = null`
  falls back to the persisted identity name (`"Flash Device"` on first run);
  `receivedFilesDir = null` means `<externalFilesDir>/FlashReceived`.
- **`create` opens the encrypted database synchronously — call it off the main thread.** Everything
  else (network server, NSD advertise/browse, data-channel server, proactive auto-connect) starts
  asynchronously on the shared scope right after it returns.
- `enableResume = false` drops the `TransferStore` only; the chat/settings database is opened
  regardless, because chats and settings require it. Transfers still work in-session, they just
  restart rather than resume after a process death.
- `autoAcceptIncoming` is the inbound-offer gate: false (the default, matching the app) means every
  inbound file arrives as an OFFER and the sender parks until
  `FlashTransferRepository.acceptIncoming`; true is the zero-friction quick-start path.

### `FlashEngine`
- **Stability:** Stable
- **Purpose:** the aggregate accessor surface for ViewModels and UI.
- **Definition:**
  ```kotlin
  public interface FlashEngine : Closeable {
      public val chats: FlashChatRepository
      public val transfers: FlashTransferRepository
      public val discovery: FlashDiscovery
      public val network: FlashNetwork
      public val trustStore: FlashTrustStore
      public val settings: FlashSettingsDataStore

      public val ptt: FlashPtt?

      public fun attachPtt(
          hasMicPermission: () -> Boolean = { true },
          isCallActive: () -> Boolean = { false },
          audioRateHz: () -> Int = { 16_000 },
      ): FlashPtt?

      public fun attachPtt(engine: FlashPtt)
      public fun detachPtt()

      public val calls: FlashCalling?

      public fun attachCalling(engine: FlashCalling)
      public fun detachCalling()

      public suspend fun onInboundCallText(peerDeviceId: String, text: String): Boolean
      public fun onCallSignalingLost(peerDeviceId: String)
      public fun onCallSignalingRestored(peerDeviceId: String)
  }
  ```
- **Seven properties, every one an abstraction** from §3–§9 (`settings` being the documented
  concrete exception). The facade adds no behaviour of its own — it is composition, not a god object.
- **`ptt` is the one optional subsystem, and it is attached rather than created.** PTT needs a
  microphone grant and a foreground service that only an app can declare, so `Flash.create` wires a
  factory and waits: `attachPtt(hasMicPermission, isCallActive, audioRateHz)` builds a
  `PttSessionEngine` from the facade's own identity/trust/live-session state plus those three host
  policy lambdas, attaches it, and returns it; a second call returns the attached instance. It
  returns **null** for a hand-assembled `DefaultFlashEngine`, which has no wiring to attach to —
  that host calls the other overload, `attachPtt(engine)`, with an engine it built itself (ignored
  when one is already attached). `detachPtt()` shuts the attached engine down and is idempotent;
  `close()` calls it, so a host only needs it to stop PTT while keeping the engine alive.
- **Inbound PTT is routed for you, attached or not.** `FLASH_PTT` / `FLASH_PTSS` text frames go to
  `FlashPtt.onInboundText`, `PTT1` binary frames to `FlashPtt.onInboundBinary` **before** the
  transfer parser, and all four cases (`consumed`, `rejected-but-consumed`, `no engine attached`,
  `unrelated text`) end the same way: a recognized PTT frame never reaches the chat or transfer
  handlers. With no engine attached the frames are recognized and dropped with a warning.
- **There is one `calls` property, and it is attached rather than created — deliberately.**
  `attachCalling(engine)` takes a `FlashCalling` the host built; there is no lambda overload and no
  `callsFactory`, because a `CallCoordinator` is assembled from the host's `sendFrame` transport
  seam, its scope and its audio policy, and the permissions, audio route and foreground service are
  the host's. A factory could only pretend to build one. Attaching twice keeps the first engine
  (mirrors `attachPtt`); `detachCalling()` stops routing and is idempotent; `close()` calls it, and
  detaching is explicitly **not** hang-up (the call's lifetime is the host's).
- **Inbound calling is routed for you, attached or not.** `Flash.create` recognizes the
  `FLASH_CALL` prefix itself and hands the frame to `onInboundCallText` — the same routing the other
  families get, with one extra rule: a recognized call frame is consumed or dropped, never passed to
  the PTT, chat, group or transfer parsers, because `CallCoordinator.onInboundText` legitimately
  answers false for frames that belong to calling anyway (a stale call id, a `gquery` with no live
  call). With no engine attached the frame is dropped with a warning. The recognition is a plain
  prefix test, not `CallFrameCodec.decode`: `:core:calling` is `compileOnly`, so the codec's class is
  not guaranteed to exist at runtime and this runs on every inbound text frame.
- **`onCallSignalingLost` / `onCallSignalingRestored` are the facade's, not the host's.**
  `Flash.create` observes the live sessions it already tracks and forwards both edges, which is what
  closes ERROR-033's recovery window instead of leaving every roam to burn the full grace period.
  Both are no-ops when nothing is attached, as is `onInboundCallText` (it answers false) — the
  three are typed without `FlashCalling` precisely so an engine with no calling classes on the
  runtime classpath can take the call without resolving one.
- **`close()` is not optional and is idempotent.** An engine from `Flash.create` owns a shared
  `CoroutineScope` plus NSD, Wi-Fi, data-channel and database resources; `close()` cancels the scope
  and releases them. Call it from `onDestroy` or `ViewModel.onCleared`.

### `DefaultFlashEngine`
- **Stability:** Stable
- **Purpose:** the hand-assembly path, for a consumer that wants to substitute one subsystem.
- **Definition:**
  ```kotlin
  public class DefaultFlashEngine(
      override val chats: FlashChatRepository,
      override val transfers: FlashTransferRepository,
      override val discovery: FlashDiscovery,
      override val network: FlashNetwork,
      override val trustStore: FlashTrustStore,
      override val settings: FlashSettingsDataStore,
      private val onClose: () -> Unit = {},
      private val pttFactory: ((
          hasMicPermission: () -> Boolean,
          isCallActive: () -> Boolean,
          audioRateHz: () -> Int,
      ) -> FlashPtt)? = null,
  ) : FlashEngine
  ```
- **The second documented exception to §1.1.** It is public because substituting a subsystem is a
  supported use case, and a factory that took all six dependencies would be the same class with
  extra ceremony.
- **`onClose` defaults to a no-op.** `Flash.create` passes the coordinated teardown; a
  hand-assembled engine owns its own scopes and lifecycles, so it should not have someone else's
  teardown imposed on it. `close()` still runs at most once, and detaches the calling engine before
  shutting the attached PTT engine down.
- **`pttFactory` is null by default**, which is exactly what makes `attachPtt(lambdas)` return null
  on a hand-assembled engine instead of pretending it built something. `Flash.create` is the only
  caller that passes one. There is deliberately **no** `callsFactory` counterpart: calling has no
  lambda overload to serve, so `calls` is null on every engine until a host attaches its own.

### Also public
`KeystorePassphraseProvider(context)` — the AndroidKeyStore-wrapped implementation of
`PassphraseProvider` (§9), living here rather than in `:core:persistence` so that module stays
Keystore-free. `RoomTransferStore` — the Room-backed `TransferStore` adapter. Both are wiring
parts: `Flash.create` already installs them, and they are public only so hand-assembly can too.
`AutoConnectGate` is `internal`.

---

## 11. UI Theme Module (`:ui:theme`)

The design system every Flash composable draws from: colors, typography, motion, shapes, icons,
haptics and sounds. Publishes as `ui-theme`, depends only on `:core:common` and Compose.

**Compose modules are public-by-default.** `:ui:*` does not run `explicitApi()` (§1.7), so a great
deal is technically visible. The supported surface is what is listed in §11–§13; everything else —
`Color.kt`'s Material template palette, `Type.kt`'s default `Typography`, the internal `resolveAccent`
helper — is incidental.

### `FlashTheme` (the composable) and `FlashTheme` (the token accessor)
- **Stability:** Stable
- **Purpose:** wrap a subtree with Flash tokens, then read them back anywhere inside it.
- **Definition:**
  ```kotlin
  @Composable
  public fun FlashTheme(
      darkTheme: Boolean = isSystemInDarkTheme(),
      dynamicAccent: Boolean = false,
      hapticsEnabled: Boolean = true,
      colors: FlashColors = if (darkTheme) FlashColors.dark() else FlashColors.light(),
      typography: FlashTypography = FlashTypography.default(),
      motion: FlashMotion = rememberFlashMotion(),
      content: @Composable () -> Unit,
  )

  public object FlashTheme {
      public val colors: FlashColors          // @Composable @ReadOnlyComposable
      public val typography: FlashTypography
      public val motion: FlashMotion
  }
  ```
- **Read tokens from `FlashTheme`, not `MaterialTheme`.** Chat-visible styling that goes through
  `MaterialTheme.colorScheme` will drift from the Flash palette; the accessor object exists so
  there is never a reason to.
- **There is no `shapes` parameter and no `FlashTheme.shapes`.** Shapes are a stateless `object`
  (`FlashShapes`) referenced directly, because nothing about them varies per subtree.
- **`dynamicAccent` tints three slots and no more** (UI-036, ADR-005): `accentPrimary`,
  `accentSecondary` and `textLink`, only on API 31+. Surfaces, neutrals and bubbles stay
  Flash-owned — the brand identity does not become the wallpaper's.
- **`darkTheme` is an authored palette, not an inversion** (UI-035): graphite/void layered
  surfaces with brighter accents.
- `hapticsEnabled = false` silences every `rememberFlashHaptics()` call site in the subtree without
  touching the call sites (UI-039/UI-049).

### Token types
- **Stability:** Stable
- **Definition:**
  ```kotlin
  public data class FlashColors(/* ~91 semantic slots */) {
      public companion object {
          public fun light(): FlashColors
          public fun dark(): FlashColors
      }
  }

  public data class FlashTypography(/* 13 TextStyle slots */) {
      public companion object {
          public fun default(fontFamily: FontFamily? = null): FlashTypography
      }
  }

  public class FlashMotion internal constructor(public val reduceMotion: Boolean) {
      public val fastMillis: Int
      public val normalMillis: Int
      public val slowMillis: Int
      public val emphasisMillis: Int
      public fun statusCrossfade(): ContentTransform
      public fun messageEnter(): EnterTransition
      // …one factory per named chat animation
  }

  @Composable public fun rememberFlashMotion(): FlashMotion

  public object FlashShapes { /* radius2..radiusFull + named shapes: chip, sheet, composerBar, … */ }
  public object FlashSpacing
  public object FlashDimensions
  public object FlashElevation
  public class FlashBubbleShape(/* … */) : Shape
  ```
- **The slots are semantic, not literal.** `FlashColors` names roles (`accentPrimary`,
  `textLink`, bubble and surface slots) so `light()` and `dark()` are two authored palettes over
  one vocabulary rather than a colour list each component re-interprets.
- **`FlashMotion` has an `internal` constructor.** Obtain it from `rememberFlashMotion()`, which
  reads the platform reduce-motion setting. Every duration collapses to `0` when `reduceMotion`
  is true, and each animation factory returns `EnterTransition.None`/`ExitTransition.None` — so
  accessibility is honoured centrally and a component never needs its own branch. Reference
  `FlashTheme.motion` rather than writing an ad-hoc `tween` (UI-037).
- `FlashTypography.default(fontFamily)` takes an optional family so a consumer can substitute a
  font without restating all 13 styles.

### Interaction, feedback and icons
- **Stability:** Stable
- **Definition:**
  ```kotlin
  public enum class FlashHaptic { /* named haptic intents */ }
  public object FlashHapticPolicy
  @Composable public fun rememberFlashHaptics(): (FlashHaptic) -> Unit

  public enum class FlashSound(/* … */)
  public data class ToneSegment(public val freqHz: Double, public val durationMs: Int)
  public object FlashSoundPolicy
  public object FlashSoundSettings
  public object FlashSoundSynth
  @Composable public fun rememberFlashSounds(): (FlashSound) -> Unit

  public fun Modifier.flashPressScale(/* … */): Modifier

  public enum class FlashIconState { /* … */ }
  public data class FlashIconSpec(/* … */)
  public object FlashIcons
  @Composable public fun FlashIcon(/* … */)
  public fun FlashIconState.tint(colors: FlashColors, override: Color? = null): Color
  public val flashIconDefaultSize: Dp

  @Composable public fun FlashText(/* … */)
  @Composable public fun FlashAvatar(/* … */)
  public fun avatarSeed(name: String): String
  public fun flashAvatarColorsFor(seed: String): Pair<Color, Color>
  @Composable public fun FlashBrandAnimation(/* … */)
  @Composable public fun FlashThemeSwatches(modifier: Modifier = Modifier)
  @Composable public fun FlashMaterialTheme(/* … */)
  ```
- **Haptics and sounds are both returned as a `(Intent) -> Unit` lambda,** not as objects with
  methods. A call site names *what happened* (`FlashHaptic`, `FlashSound`) and the policy layer
  decides whether anything fires — which is what lets `hapticsEnabled` and the sound preference
  be a single switch at the theme boundary.
- `FlashSoundSynth` generates tones from `ToneSegment` lists, so the module ships no audio assets.
- `FlashMaterialTheme` exists for interop: it hands Material 3 a scheme derived from Flash tokens,
  for the stock Material components Flash does not re-skin. `FlashThemeSwatches` is a
  design-review surface, not a product screen.

---

## 12. UI Chat Module (`:ui:chat`)

The full messaging UI — chat list, conversation, transfers, nearby, settings, and the shell that
holds them. Publishes as `ui-chat`; depends on `:core:common`, `:core:messaging` and `:ui:theme`.

**Stateless by construction.** Every screen takes a `*UiState` from `:core:messaging` plus
callbacks, and holds no repository. That is what makes the module publishable on its own: a
consumer can render Flash's chat UI over its own data source, and the `@Preview`s work without an
engine.

> **Consumer note.** `:core:messaging` is declared `implementation`, not `api`, yet composable
> signatures here take `FlashMessageUi`, `FlashConversationUiState`, `FlashImageAttachmentUi` and
> friends. A third-party consumer of `ui-chat` therefore has to add `core-messaging` (or
> `core-engine`, which `api()`s it) to its own build to name those types. `:ui:callui` does the
> opposite and `api()`s `:core:calling` for exactly this reason (§13) — worth aligning.

### Screens
- **Stability:** Experimental — composable parameter lists are the least stable surface in Flash.
- **Definition:**
  ```kotlin
  @Composable public fun FlashChatListScreen(
      state: FlashChatListUiState,
      onConversationClick: (String) -> Unit,
      onSearchClick: () -> Unit,
      modifier: Modifier = Modifier,
      listState: LazyListState = rememberLazyListState(),
      bottomInset: Dp = 0.dp,      // the hanging shell bar; rows scroll under it (UI-046)
      // …selection-mode, archive and LAN callbacks, all defaulted
  )

  @Composable public fun FlashConversationScreen(
      state: FlashConversationUiState,
      onBack: () -> Unit,
      onSendText: (String) -> Unit,
      isPeerTrusted: Boolean = false,
      onRevokePeerTrust: (() -> Unit)? = null,
      // …attachment, reaction, reply, search and voice callbacks, all defaulted
  )

  @Composable public fun FlashTransfersScreen(/* TransfersUiState + callbacks */)
  @Composable public fun FlashNearbyScreen(/* NearbyUiState + callbacks */)
  @Composable public fun FlashSettingsScreen(/* FlashSettingsModel + callbacks */)
  @Composable public fun FlashStressTestScreen(/* … */)
  ```
- **Required parameters first, then defaults.** Only `state`, navigation and the primary action are
  required; every secondary hook defaults to a no-op, so a host adopts one capability at a time
  rather than stubbing thirty lambdas to compile.
- `FlashStressTestScreen` is a diagnostics surface (large synthetic threads, scroll behaviour), not
  a product screen.

### Components
- **Stability:** Experimental
- **Message thread:** `FlashMessageList`, `FlashMessageBubble`, `FlashGroupHeader`'s
  `FlashGroupAvatar`, `FlashQuotedReplyCard`, `FlashSwipeToReplyContainer`,
  `FlashDeliveryStatusIcon`, `FlashFloatingTimestampPill`, `FlashTypingIndicator` /
  `FlashTypingBubble` / `FlashHeaderTypingStatus`.
- **Reactions and actions:** `FlashReactionChip`, `FlashReactionsRow`, `FlashReactionsDock`,
  `FlashQuickReactionsBar`, `FlashMessageActionsSheet`, `FlashMessageFocusOverlay`,
  `FlashFocusedBubblePreview`, `FlashContextMenuCard`, `FlashSelectionToolbar`,
  `toggleMessageReaction`.
- **Attachments and media:** `FlashAttachmentButton`, `FlashAttachmentSheet`,
  `FlashAttachmentTile`, `FlashAttachmentGrid`, `FlashImageGrid`, `FlashImageTile`,
  `FlashFileMessageCard`, `FlashFileIconBadge`, `FlashMediaViewer`, `rememberFlashZoomState`,
  `FlashVoiceMessageCard`, `FlashVoiceRecordingBar`, `FlashMicButton`, `FlashAudioPlayer`,
  `FlashVoiceRecorder`, plus the formatters `formatFileSize` and `fileCategoryColorFor`.
- **Chrome, search and status:** `FlashChatHeader`, `FlashChatListRow`, `FlashChatListTopBar`,
  `FlashChatListSelectionBar`, `FlashChatListSearchBar`, `FlashRecentSearchChips`,
  `FlashChatSearchBar`, `buildHighlightedMessageText`, `FlashComposer`, `FlashSendButton`,
  `FlashReplyDock`, `FlashConnectionBanner`, `FlashTransportBadge`, `FlashEmptyState`,
  `FlashErrorState`, `FlashSkeletonChatList`, `FlashSkeletonConversation`.
- **Trust, peers and groups:** `FlashPairingDialog`, `FlashPeerDetailsSheet`,
  `FlashGroupMembersSheet`, `FlashEncryptionBadge`, `FlashEncryptionSheet`.
- **Shell and layout:** `FlashBottomNav`, `FlashAdaptiveTwoPane`, `rememberFlashWindowSize`,
  `FlashAnimatedScreen`, `rememberFlashNavigationState`.
- `FlashCallEventRow` — the in-thread call row — is `internal`. It is reached through
  `FlashMessageBubble`, which decides when a message is a call event, so a host never places it
  directly. Call rows are stored as a `cmsg:` prefix in the message text column (see
  `docs/protocol.md`), not as a distinct message type.
- `FlashMediaDecoder` — the single bitmap-decoding path behind `FlashImageTile` and
  `FlashMediaViewer` — is `internal`. Sample-size-bounded stills, `MediaMetadataRetriever` frames
  for video, EXIF rotation and an `LruCache`, all reached through those two composables. Keeping it
  internal is what lets the module stay dependency-free: a consumer that wants Coil or Glide
  replaces the tile, not the decoder (ERROR-029).
- `FlashNetworkSimSheet` / `rememberSimulatedHealth` are development affordances for exercising the
  degraded-network UI without a degraded network.

### State and pure-logic types
- **Stability:** Experimental
- **Definition:**
  ```kotlin
  public enum class FlashWindowSizeClass { /* Compact, Medium, Expanded */ }
  public enum class FlashAttachmentType(/* … */)
  public enum class FlashEncryptionBadgeState { /* … */ }
  public enum class FlashConnectionHealth { /* … */ }
  public enum class FlashNetworkBannerSeverity { Calm, Attention }
  public enum class FlashErrorSeverity { Failure, Environmental }
  public enum class FlashPairingPhase { /* … */ }
  public enum class FlashRecordingPhase { /* … */ }
  public enum class FlashHoldSlideTarget { /* … */ }
  public enum class FlashThemeMode { System, Light, Dark }
  public enum class FlashTransferState { Offered, Queued, Active, Paused, Completed, Failed }
  public enum class FlashTransferDirection { Send, Receive }
  public enum class FlashDestination(public val title: String) { /* … */ }
  public enum class FlashScreenTransition { None, Push, Pop, TabForward, TabBackward }

  public data class FlashPairingRequestUi(/* … */)
  public data class FlashMediaViewerItem(/* … */)
  public data class FlashSettingsModel(/* … */)
  public data class FlashBottomNavItem(/* … */)
  public data class FlashTransferItemUi(/* … */)
  public data class TransfersUiState(/* … */)
  public data class NearbyIdentityUi(/* … */)
  public data class NearbyPeerUi(/* … */)
  public data class NearbyTrustedPeerUi(/* … */)
  public data class NearbyUiState(/* … */)
  public data class FlashBackStackState(/* … */)
  ```
  `FlashSettingsModel` gained two fields for ERROR-031: `ignoringBatteryOptimizations: Boolean`
  (refreshed in the host's `onResume`, because the user can change it in system Settings while the
  app is backgrounded — the app can only ever *read* it) and `prioritiseVoiceQuality: Boolean`
  (default `true`, mirroring `FlashSettingsDataStore`). `FlashSettingsScreen` gained the matching
  callbacks `onOpenBatterySettings: () -> Unit` and
  `onPrioritiseVoiceQualityChanged: (Boolean) -> Unit`, both defaulted so previews stay inert.

### The `*Math` objects
- **Stability:** Experimental
- **Definition:** `FlashAdaptiveMath`, `FlashChatScrollMath`, `FlashChatListSearchMath`,
  `FlashChatSearchMath`, `FlashEncryptionMath`, `FlashGroupHeaderMath`, `FlashGroupMembersMath`,
  `FlashMediaViewerMath`, `FlashNetworkStatusMath`, `FlashNetworkSimMath`, `FlashPairingMath`,
  `FlashPeerDetailsMath`, `FlashStateMath`, `FlashStateCopy`, `FlashStressMath`, `FlashVoiceMath`,
  `FlashVoiceRecordingMath`, `FlashNearbyMath`, `FlashSettingsMath`, `FlashTransfersMath`,
  `FlashBottomNavMath`, `FlashBottomNavDefaults`, `FlashNavigationMath`.
- **The house pattern for testable UI.** Every non-obvious decision a composable makes — should the
  list auto-scroll, does this message animate in, which severity does this health map to, what is
  the unseen-message count after this update — is a pure function on one of these objects. The
  composable calls it; a JVM unit test calls the same function without Compose, Robolectric or a
  device. They are public because the tests live in the module's own `test/` source set and because
  a consumer re-skinning a component still wants the behaviour.
- `FlashStateCopy` holds the empty/error strings, so copy can be reviewed in one place.
- `FlashSettingsMath` gained three helpers in ERROR-031: `batteryExemptionSubtitle(exempt)`,
  `batteryExemptionValue(exempt)` and `prioritiseVoiceSubtitle(enabled)`. Copy in a pure function is
  copy a test can hold to a standard — the tests assert that the restricted battery text names the
  screen-off *consequence* and asks for a tap while the exempt text does neither, and that both
  halves of the voice-priority subtitle say which stream pays.

### `FlashNavigationState`
- **Stability:** Experimental
- **Definition:**
  ```kotlin
  public class FlashNavigationState internal constructor(initialEntries: List<FlashBackStackState>) {
      public constructor(initial: FlashDestination = FlashDestination.ChatList)

      public var entries: List<FlashBackStackState>   // private set
      public val current: FlashBackStackState
      public val stackSize: Int
      public val canGoBack: Boolean
      // navigate / back / tab switching
  }

  @Composable public fun rememberFlashNavigationState(): FlashNavigationState
  ```
- **The root entry always survives**, so `current` can never throw and a host never has to handle
  an empty stack. Invalid restored entries are filtered on construction rather than crashing —
  a saved back stack from an older build degrades to the root.
- `canGoBack` is true both when something sits above the root *and* when the user is parked on a
  non-home tab, so one property answers "will back be consumed?" for both cases.
- Flash navigation is a plain state object, not Navigation-Compose: the module has no
  `androidx.navigation` dependency, and a host is free to drive its own router from
  `FlashDestination` instead.

---

## 13. UI Calling Module (`:ui:callui`)

The in-call screen (UI-050, `docs/ui/calling-ui.md`). Publishes as `ui-callui`; namespace
`com.transfer.flash.ui.calling`.

**One public composable, and that is the whole module.** Everything else — the video surfaces, the
renderer, the control row, the stats badge, the duration ticker — is `private`. A host gets a
full-screen call UI and no internal seams to hold wrong.

### `FlashCallScreen`
- **Stability:** Experimental
- **Definition:**
  ```kotlin
  @Composable
  public fun FlashCallScreen(
      state: FlashCallUiState,
      session: FlashCallMedia?,
      onAccept: () -> Unit,
      onDecline: () -> Unit,
      onHangUp: () -> Unit,
      onToggleMute: () -> Unit,
      onToggleSpeaker: () -> Unit,
      onToggleCamera: () -> Unit,
      onSwitchCamera: () -> Unit,
      onDismiss: () -> Unit,
  )
  ```
- **It takes the two `:core:calling` abstractions and nothing else.** `state` drives every visual;
  `session` is the read-only `FlashCallMedia` view (§7). Controls are lambdas the host wires to
  `FlashCalling`, so the screen cannot mutate a call and never sees the concrete session type.
- **No parameter defaults, deliberately.** A silently-defaulted `onHangUp` would compile into a
  call the user cannot end.
- **`session` is nullable** because media starts asynchronously — roughly 130 ms after the screen
  appears. Tracks are *observed* from the flows, never sampled once, which is what lets a video
  tile fill in after the first frame instead of staying black.
- **Back behaviour:** decline while `RINGING`, otherwise `onDismiss`. A host with no minimize UI
  should pass an empty `onDismiss` for a live call — the call outlives this screen either way,
  because call state is owned by `FlashCalling`, not by composition.
- Audio calls render the peer avatar with the Flash pulse; video calls render remote-full with a
  local picture-in-picture, and fall back to the avatar block once the call has ENDED.

### Dependency shape
- **`api(project(":core:calling"))`, not `implementation`** — the signature above names
  `FlashCallUiState` and `FlashCallMedia`, so a consumer could not call it otherwise. This also
  re-exports webrtc-kmp transitively (§7), which the internal renderer needs for
  `SurfaceViewRenderer`.
- `:core:common` and `:ui:theme` stay `implementation`: nothing from either appears in the
  signature.
- **Standalone-publishable** (architecture invariant 3): this module compiles and publishes without
  `:app`, `:core:engine` or `:ui:chat`.


---

## 14. Core PTT Module (`:core:ptt`)

Push-to-talk (ADR-032): a strict half-duplex voice floor — one holder transmits, every other member
only receives. Publishes as `core-ptt`; namespace `com.transfer.flash.core.ptt`. The wire format is
in `docs/protocol.md` (PTT ping + PTT voice session sections); the floor rules live in
`:core:messaging` (`PttFloorMachine`) and this module is the driver that executes them.

**Inside the umbrella, but opt-in.** `:core:engine` declares `:core:ptt` with `api()`, so a
`core-engine` consumer already has the artifact — but nothing is created until the host attaches it
(§10): a live session needs a runtime `RECORD_AUDIO` grant and a foreground service that only an
app can declare, so `Flash.create` cannot supply either.

### `FlashPtt`
- **Stability:** Experimental
- **Purpose:** the entire PTT surface — press, live state, telemetry, inbound routing, microphone
  leases, teardown.
- **Definition:**
  ```kotlin
  public interface FlashPtt {
      public val state: StateFlow<PttFloorState>
      public val stats: StateFlow<PttSessionStats?>
      public val notices: SharedFlow<String>
      public val pings: Flow<PttPingEvent>

      public fun onPttButton(): PttPressOutcome
      public fun sendPing(): Boolean
      public fun postNotice(text: String)
      public fun stopLocal()
      public fun onCallStarted()
      public fun acquireVoiceNoteLease(): String?
      public fun releaseVoiceNoteLease(leaseId: String)

      public fun onInboundText(peerId: String, text: String): Boolean
      public fun onInboundBinary(peerId: String?, data: ByteArray): Boolean

      public fun shutdown()
  }
  ```
- **The two inbound seams return `true` for recognized-but-rejected frames.** `onInboundText`
  covers both PTT text families (ping and session control); a frame whose claimed `from` does not
  equal the authenticated transport peer, or that comes from an untrusted peer, is dropped **and
  still returns true**. That is the fail-closed rule the rest of the protocol uses, expressed as a
  return value: a host chains it ahead of its other text handlers
  (`if (ptt.onInboundText(id, text)) return`) and a forged PTT frame can never be parsed as a chat
  frame. `onInboundBinary` answers true for anything carrying the `PTT1` magic, including malformed
  or stale packets, for the same reason — it must not reach the transfer parser.
- **`pings` is the module's own flow**, deduplicated on `eventId` before emission. It is not a
  constructor-injected sink: exactly one component may own the inbound ping path, and a second
  decode/dedup/fan-out next to it is how a ping gets surfaced twice.
- **`stats` is sampled telemetry, not state.** `elapsedMs`/`depthMs` are monotonic-clock
  derivations, `lossPercent` is derived from the receiver's ready-vs-concealed counts, and `rttMs`
  is null until the first heartbeat ack — read it as a badge, never as a control input.
- **`onPttButton()` answers denials synchronously** (`PttPressOutcome`) and does the toggle
  asynchronously, so it is safe from a hardware broadcast receiver on the main thread. The
  accept-path checks run in this order: live call → `CALL_ACTIVE`, held voice-note lease →
  `VOICE_NOTE_ACTIVE`, no online member → `NO_PEERS`, no microphone grant → `NO_MIC`.
- **`acquireVoiceNoteLease()` is the microphone gate for everything else.** It returns null while a
  PTT press is pending, a PTT session is live, or a call is active; only the holder of the returned
  id can release it, so disposing of an unrelated voice recorder cannot clear someone else's gate.
- **`shutdown()` is terminal** (it cancels the engine's scope), which is why `FlashEngine.close()`
  and `detachPtt()` are the ones that call it: a later attach needs a new engine.
- **Threading:** every entry point is safe from any thread. Floor reductions and lifecycle effects
  serialize on one command lane; audio bytes take a lock-free state-snapshot path and never log per
  packet; session loops (tick, heartbeat, audio sender) exist only while the floor is non-idle —
  there is no perpetual PTT timer.

### `PttPressOutcome`
```kotlin
public enum class PttPressOutcome { ACCEPTED, NO_PEERS, NO_MIC, CALL_ACTIVE, VOICE_NOTE_ACTIVE }
```
`ACCEPTED` means the toggle was scheduled, not that audio is flowing yet — the state flow carries
that. The other four are refusals the host can turn into copy.

### `PttRole` / `PttSessionStats`
```kotlin
public enum class PttRole { TALKER, LISTENER }

public data class PttSessionStats(
    public val sessionId: String,
    public val role: PttRole,
    public val elapsedMs: Long,
    public val rttMs: Long?,
    public val lossPercent: Float,   // fraction 0..1, receiver-side
    public val depthMs: Long,        // jitter-buffer depth in ms, rounded to whole packets
    public val amplitude01: Float,   // 0..1 level for a meter (capture RMS or playout)
    public val members: Int,         // talker: listeners being sent to; listener: 1
)
```
Null `stats` means idle. `TALKER` stats come from the capture side (`amplitude01` = captured RMS,
`members` = legs the audio is fanned out to); `LISTENER` stats come from the jitter buffer
(`depthMs`, `lossPercent`, playout `amplitude01`).

### `PttPingEvent`
```kotlin
public data class PttPingEvent(
    public val eventId: String,
    public val fromDeviceId: String,
    public val senderName: String,
    public val sentAtMs: Long,
)
```
One accepted inbound press. `eventId` is the wire id the module already deduplicated on, so a host
that wants its own extra dedup can use it — and `fromDeviceId` is the authenticated transport peer,
never the frame's self-declared `from`. `sentAtMs` is the *sender's* wall clock, so never compare it
to local time.

### `PttSessionEngine`
- **Stability:** Experimental
- **Purpose:** the one implementation. A host constructs it directly **only** when it owns its own
  transport and identity — the sample app does, through `DiscoveryDeviceHolder`; a `Flash.create`
  consumer gets one from `FlashEngine.attachPtt(...)` (§10) instead.
- **Constructor shape** (every argument is identity, policy, or a transport sink — the module never
  opens a socket and never reads a permission itself):
  ```kotlin
  public class PttSessionEngine(
      private val localId: () -> String?,
      private val localName: () -> String?,
      private val isTrustedPeer: (String) -> Boolean,
      private val snapshotMembers: () -> List<String>,
      private val sendControl: (peerId: String, text: String) -> Boolean,
      private val sendAudio: (peerId: String, bytes: ByteArray) -> Unit,
      private val hasMicPermission: () -> Boolean,
      private val isCallActive: () -> Boolean,
      private val audioRateHz: () -> Int,
  ) : FlashPtt
  ```
- **`sendControl`/`sendAudio` are blocking by contract and are called only off the main thread**
  (the engine dispatches its own loops to `Dispatchers.IO`). `snapshotMembers` is read fresh before
  a session starts, so a stale snapshot cannot announce a peer that left.
- **`audioRateHz` picks the packetization**: 8 kHz captures at 60 ms packets, 16 kHz at 20 ms, and
  the capture probe may fall back from 16 kHz to 8 kHz if the HAL refuses the request — the
  actually-negotiated rate and packet size ride the `start` frame, so receivers always know the
  format (and reject a session whose `rate`/`pms` are outside the allowlists).
- **`PttSessionEngine.EXTRA_PTT_PRESS`** is the intent extra a host uses to hand a deferred press to
  its own activity (the sample app sets it when a backgrounded press surfaces the app, since a
  `microphone` foreground service cannot be promoted from the background on API 31+). It is the only
  host-integration constant the module declares.

### Dependency shape
- `api(project(":core:messaging"))` — `PttFloorState`, `PttFloorMachine`, `PttJitterBuffer`,
  `PttAudioLevel` and the PTT wire codecs all live there and appear in public signatures.
- `api(libs.kotlinx.coroutines.core)` — `StateFlow`/`SharedFlow`/`Flow` in the interface.
- `implementation(libs.androidx.core.ktx)`; `explicitApi()` is on, like the other `core-*` modules.
- **Android-only.** `PttCapture`/`PttPlayout` are built on `AudioRecord`/`AudioTrack`, and the
  engine reads `android.os.SystemClock`, so this is an AGP Android library with no JVM target — the
  reason `:core:engine` declares it on its android target (§10).
- Ships **no `AndroidManifest.xml`**, like every other library module (§1.7): the permissions and
  the session foreground service are the app's to declare (README → Permissions).
