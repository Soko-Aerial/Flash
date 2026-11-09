# Pairing and trust

On Android, `Flash.create(context)` exposes pairing through `FlashEngine.pairing`. Start with
[the facade integration](#shape-b----the-facade-flashcreate). Custom engine graphs and
standalone transports can use the shared coordinator below. The pairing API was checked against
source on 2026-11-07.

## 1. What "paired" Means Here (three layers)

Flash records three different things about a peer, and they are **not** interchangeable:

| Layer | Stored by | Written by | What it means |
|---|---|---|---|
| **TLS pin** | `FlashTrustStore.savePin(deviceId, fingerprintHex)` | outbound TOFU or the coordinator after verified inbound pairing | "this device id always presents this identity key on the wire" |
| **Paired** | `trustPeer(deviceId, friendlyName)` | the coordinator on success, or a directory one-tap | "the user agreed to talk to this device" |
| **Verified** | `markVerified(deviceId)` | the coordinator on a verified v2 pairing | "the code the user compared covered both TLS identities" |
| **E2E session key** | `saveSessionKey(deviceId, key)` | the coordinator after the ECDH exchange | "frames to this peer are AES-GCM sealed, not merely inside TLS" |

Two consequences that catch people out:

* **`isTrusted` is not `isEncrypted`.** The hub's directory one-tap pairing (`FlashChatRepository.pairColleagueFromDirectory`) marks a colleague paired and verified against the hub-signed statement, but its own KDoc says it *grants no encryption key, so frames stay plain inside TLS until a code pairing is done*. If your product promises end-to-end encryption, the six-digit code is what delivers it.
* **Compare the key from the live TLS session.** The driver uses the peer's stored pin or, on a first inbound connection, the observed TLS leaf. It promotes that observed key to a pin only after pairing succeeds. If neither source supplies the peer's key, pairing fails with `identity-mismatch`.

Format note: the fingerprint is `FlashFingerprint.formatHexGroups(FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded))` -- uppercase, colon-separated. The protocol normalizes on compare (`NumericComparisonCode.normalizeHex` lowercases and keeps hex digits only), so grouped and raw hex are both accepted; just be consistent in your own logs.

---

## 2. What the Library Gives You (and What It Does Not)

Everything below is `:core:security` unless stated otherwise.

| Piece | Kind | Where |
|---|---|---|
| `FlashPairingProtocol` | interface: `beginRequest`, `respondAccept`, `respondDecline`, `onFrame`, `onTick`, `events`, `session` | `commonMain` |
| `DefaultFlashPairingProtocol` | the implementation (owns no coroutines, no clock, no scope) | `commonMain` |
| `PairingWireCodec` | `FLASH_PAIR` text encoding/decoding, `PROTOCOL_VERSION = 2` | `commonMain`, marked `@FlashInternalApi` |
| `FlashPairingFrame` / `FlashPairingEvent` / `PairingPhase` / `PairingSessionState` / `PairingTimeouts` | wire frames, UI events, state | `commonMain` |
| `PairingV2` / `NumericComparisonCode` | commitment, code derivation, code-hash compare | `commonMain`, **`internal`** |
| `FlashPairingCoordinator` | the complete driver (hello cache, 3 s fingerprint wait, tick, trust writes, UI state) | `commonMain` (Android and JVM) |
| `FlashPairingDialog`, `FlashPairingPhase`, `FlashPairingRequestUi`, `FlashPairingMath` | the consent UI (UI-032) | `:ui:chat` `commonMain` |
| `AndroidPreferencesTrustStore(context)`, `AndroidPreferencesIdentityStore(context)`, `KeystoreFlashCrypto(context)` | Android trust/identity/crypto | `:core:security` `androidMain` |

A host assembling its own graph supplies identity material, an authenticated session and a
trust store. The shared coordinator writes trust and drives the handshake. Hosts using the
lower-level protocol alone own those writes as well. Use the coordinator or protocol to derive
the comparison code.

---

## 3. Which integration shape are you in?

### Shape B -- the facade (`Flash.create`)

The Android factory handles session HELLO, inbound `FLASH_PAIR`, timeouts and trust writes.
The host presents the code, asks for consent, and observes the result.

```kotlin
val pairing = requireNotNull(engine.pairing)
pairing.beginPair(peerId, peerName)

// Collect pairing.pairing for the current phase and numericCode.
// On each device, call acceptLocal() only when its user confirms the codes match.
pairing.acceptLocal()
```

Use `declineLocal()` when the user refuses, `trustedPeers` to display saved peers, `messages`
for short status updates, and `revoke(peerId)` to remove trust. The coordinator sends the
initiator's wire confirmation after both users accept. Pair over a live session. `FlashDesktop.create` delegates to
the registered desktop host, so check the nullable `pairing` property on that host.

Use [the host-graph recipe](#4-shape-a-the-host-graph-what-ships-today) when supplying custom
session, identity, or trust-store components.

### Shape C -- your own transport

If you use `:core:security` as a library without the engine, you own the socket and can pair over it (section 5). The trust you write is then *your* trust, not the engine's -- pairing a peer in this mode does not make `FlashEngine.chats` willing to talk to it unless you point both at the same `FlashTrustStore` instance.

---

## 4. Shape A: the Host Graph (what ships today)

### 4.1 The material you need

```kotlin
// 1. Identity: the same stores the facade uses, so ids and fingerprints agree with an install that already ran Flash.create.
val identity = AndroidPreferencesIdentityStore(context).getIdentity()   // deviceId + friendlyName
val crypto   = KeystoreFlashCrypto(context)                              // fixed alias "flash_identity"
val localFingerprintHex =
    FlashFingerprint.formatHexGroups(FlashFingerprint.fingerprint(crypto.identityPublicKeyEncoded))
val localModel = android.os.Build.MODEL ?: "unknown"

// 2. ONE ephemeral ECDH key for the engine lifetime (see trap 8 -- it must be stable).
val ephemeralKeyPair = crypto.generateEphemeralEcdhKeyPair()
val ephemeralPublicKey = ephemeralKeyPair.publicKeyEncoded

// 3. The trust store your transport pins into and your chat reads from.
val trustStore = AndroidPreferencesTrustStore(context)
```

The transport must be wired with the same trust store, or v2 pairing fails with `identity-mismatch`:

```kotlin
val tlsOptions = requireTransportSecurity {                        // TLS is mandatory; no plaintext fallback
    TlsOptions(
        pinVerifier = TofuPinVerifier(
            lookupPin    = { peerId -> trustStore.getPin(FlashDeviceId(peerId)) },
            recordPin    = { peerId, pin -> trustStore.savePin(FlashDeviceId(peerId), pin) },
            ownFingerprintHex = /* SHA-256 of your own certificate key, hex */,
        ),
        keyManagers = keyManagerFactory.keyManagers,
    )
}
```

`Flash.create` is the reference implementation of this whole graph: `core/engine/src/androidMain/.../Flash.kt` lines 239-300. Copy it rather than inventing it; every argument there is a decision someone already made for a reason.

### 4.2 Wiring point 1 -- a non-blocking send that reports reachability

```kotlin
val sendToPeer: (peerId: String, text: String) -> Boolean = { peerId, text ->
    val session = network.activeSessions.value[FlashDeviceId(peerId)] as? WsSession
    if (session != null) {
        session.connection.sendTextAsync(text)   // queues on the connection's IO scope
        true                                     // reachable
    } else {
        false                                    // no live session -- "Couldn't reach", not "no answer"
    }
}
```

This one lambda has two contracts, both learned the hard way:

* **It must not block.** `beginRequest` is called from a UI tap on the main dispatcher; a blocking write there throws `NetworkOnMainThreadException`, which the connection layer records as a write failure and **closes the session** -- tearing down the very link the handshake needs.
* **It must tell the truth about reachability.** The boolean is the only difference between "we never reached the peer" (`Couldn't reach X.`) and "the peer never answered" (`Still can't reach X...`), which is the user's entire diagnostic surface.

### 4.3 Wiring point 2 -- the hello on **every** session-up edge

```kotlin
// in your session collector, for each newly live session:
pairing.onSessionUp(session.peerDeviceId.value)
```

`onSessionUp` sends `FLASH_PAIR t=hello fp=<your fingerprint>` (see `PairingWireCodec.encodeHello`). The peer needs that fingerprint **before** it can offer Pair, because the code covers both identities. Send it on every session-up, not once per peer, and not only when the user opens the Nearby tab.

A hello carrying `hrq=1` asks for one back; the answer never echoes the flag, so the exchange cannot ping-pong. Never echo `hrq`.

### 4.4 Wiring point 3 -- inbound routing

Route `FLASH_PAIR` text to the coordinator **before** the encrypted-frame branch, and recognize it by prefix:

```kotlin
if (FlashInboundRouter.isPairingFrameText(text)) {   // parseFields(text, "FLASH_PAIR") != null
    pairing.onInbound(peerDeviceId, text)
    return
}
```

If you use the shared router, `routeInboundText(..., pairingHandler = { peer, text -> pairing.onInbound(peer, text) })` does the same after calling-presence-link-PTT, which is the shipped order.

`onInbound` does two different jobs: a `hello` updates the fingerprint/version cache (and is answered when it asked), a frame goes to `protocol.onFrame`. Do not bypass it to call `protocol.onFrame` directly -- you would lose the hello cache the initiator needs.

### 4.5 Wiring point 4 -- the 1-second tick

```kotlin
scope.launch {
    protocol.session.map { it.phase != PairingPhase.Idle }.distinctUntilChanged().collectLatest { active ->
        if (!active) return@collectLatest
        while (true) { delay(1_000); protocol.onTick(SystemTimeSource.nowMs()) }
    }
}
```

`DefaultFlashPairingProtocol` owns no clock. Without the tick, nothing ever expires: the dialog counts down forever, the peer waits forever, and your state machine leaks an active `requestId` that blocks the next attempt with `Pairing already in progress`.

### 4.6 The driver itself

Use the shared coordinator on Android or JVM.

```kotlin
val pairing = FlashPairingCoordinator(
    localFingerprintHex = localFingerprintHex,
    localDeviceId       = identity.deviceId.value,
    localName           = identity.friendlyName,
    localModel          = "Desktop",
    ephemeralPublicKey  = ephemeralPublicKey,
    trustStore          = trustStore,
    scope               = scope,
    sendToPeer          = sendToPeer,
    crypto              = crypto,            // needed to derive the E2E session key
    ephemeralKeyPair    = ephemeralKeyPair,  // needed to derive the E2E session key
)
```

It already implements the parts below that are easy to get wrong: the hello cache and its answer, the 3 s fingerprint wait with 400 ms re-asks, the v1 refusal, the tick, the `PAIR_CONFIRM` on accept, the three trust writes, `trustedPeers` as an observable flow, and user-facing failure sentences. You still call `onSessionUp`, feed `onInbound`, and supply `sendToPeer`. Read `core/security/src/jvmTest/.../FlashPairingCoordinatorTest.kt` -- it wires two coordinators straight into each other and is the executable specification.


### 4.7 The six rules the driver obeys (write them down before you write your own)

1. **Send `PAIR_CONFIRM` when `PeerAccepted` arrives, and only then.** `DefaultFlashPairingProtocol` deliberately does **not** send it: on `PeerAccepted` it reads `session.expectedCodeHashHex` and you send `PairingWireCodec.encode(FlashPairingFrame.PairConfirm(requestId, hash))`. Forget this and the responder never confirms -- both sides sit until `Expired` at 30 s while both users believe they tapped Accept.
2. **`beginRequest` needs the peer's fingerprint.** If a tap lands before the peer's hello, `beginRequest` fails with `Peer fingerprint missing`. Wait for the hello, bounded: ask with `encodeHello(fp, request = true)`, poll 100 ms, re-ask every 400 ms, give up at 3 s and tell the user to try again. (The app hit this on 2026-09-14; the coordinator's KDoc records it.)
3. **Refuse v1 peers.** If the peer's hello reports `protocolVersion < 2`, do not call `beginRequest`: its code is forgeable by a man-in-the-middle (ADR-042). The shipped sentence is: *"X is running an older Flash. Update Flash on that device, then pair again."*
4. **The responder sends `PAIRED` itself** (the protocol does it once `PAIR_CONFIRM` verifies). The initiator finishes on `Paired`. Do not send `Paired` by hand.
5. **Decline is silent.** `respondDecline()` resets locally and sends nothing; the peer learns by expiry (up to 30 s). Do not promise your users an instant "declined" on the other screen.
6. **One pairing at a time.** `beginRequest` refuses while the session is not `Idle`; reset to a fresh protocol after a terminal phase (the coordinator rebuilds it, with a short linger so the "Paired" card is readable).

### 4.8 The event loop -- and the trust writes everyone forgets

```kotlin
private fun onPairingEvent(peerId: String, event: FlashPairingEvent) {
    val state = protocol.session.value
    when (event) {
        is FlashPairingEvent.PeerAccepted -> {
            val hash = state.expectedCodeHashHex ?: return
            sendToPeer(peerId, PairingWireCodec.encode(FlashPairingFrame.PairConfirm(event.requestId, hash)))
        }

        is FlashPairingEvent.Confirmed -> {
            val peer = state.peerDeviceId ?: return
            val name = state.peerName?.ifBlank { null } ?: peer.take(8)

            trustStore.trustPeer(FlashDeviceId(peer), name)      // 1. paired
            trustStore.markVerified(FlashDeviceId(peer))         // 2. verified: the code covered both TLS keys

            val peerKey = event.ephemeralPubKey.takeIf { it.isNotEmpty() }
                ?: state.peerEphemeralPublicKey                    // 3. E2E key
            if (peerKey != null) {
                runCatching {
                    trustStore.saveSessionKey(
                        FlashDeviceId(peer),
                        crypto.ecdhSessionKey(ephemeralKeyPair, peerKey),
                    )
                }
            }
        }

        is FlashPairingEvent.PeerDeclined,
        is FlashPairingEvent.Expired,
        is FlashPairingEvent.Failed -> {
            // Expired is neutral (retry is safe). Failed means a security check failed:
            // identity-mismatch, commit-mismatch, code-hash-mismatch, paired-material-mismatch.
            // Do not auto-retry a Failed; show the sentence and let the user decide.
        }

        is FlashPairingEvent.RequestReceived -> Unit   // your dialog reads state/dialog model instead
    }
}
```

All three writes matter, and the trust store's **default bodies are silent no-ops**:

```kotlin
public fun markVerified(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)
public fun isVerified(deviceId: FlashDeviceId): Boolean = false
public fun getPin(deviceId: FlashDeviceId): String? = null
```

An implementation that forgets to override `markVerified` produces "paired" peers that display as *Not verified* forever, and one that forgets `getPin` makes every v2 pairing fail with `identity-mismatch`. Both failures look like a protocol bug and neither throws.

`revokeTrust(deviceId)` clears trust, session key **and** the verified mark. `revoke()` on the coordinator is the unfriend path; it does not touch the TLS pin (the pin is the transport's, and a re-pair with the same key is expected).

### 4.9 Showing it (UI-032)

`FlashPairingDialog` takes a plain snapshot plus a phase, so any host can render it:

```kotlin
// Ported from :app's PairingUiMapper (pure, no dependencies):
fun corePhaseToUi(phase: PairingPhase): FlashPairingPhase = when (phase) {
    PairingPhase.Idle -> FlashPairingPhase.Idle
    PairingPhase.AwaitingPeerReveal -> FlashPairingPhase.Idle        // no code exists yet
    PairingPhase.AwaitingPeerNonce -> FlashPairingPhase.AwaitingPeerConfirmation
    PairingPhase.RequestReceived,
    PairingPhase.AwaitingLocalDecision -> FlashPairingPhase.RequestReceived
    PairingPhase.AwaitingPeerConfirmation -> FlashPairingPhase.AwaitingPeerConfirmation
    PairingPhase.Confirmed -> FlashPairingPhase.Paired
    PairingPhase.DeclinedByPeer -> FlashPairingPhase.Declined
    PairingPhase.Expired -> FlashPairingPhase.Expired
    PairingPhase.Failed -> FlashPairingPhase.Declined                 // the dialog has no failure card
}

val peerName = state.peerName?.ifBlank { null } ?: state.peerDeviceId.orEmpty().take(8)

FlashPairingDialog(
    request = state.code6?.let { code ->
        FlashPairingRequestUi(
            peerName = peerName,
            peerInitials = FlashPairingMath.initialsFor(peerName),
            numericCode = code,
            transport = FlashNetworkTransport.Lan,                // your transport, for the icon
            expiresInSeconds = 30,                               // the live countdown is the parameter below
        )
    },
    phase = corePhaseToUi(state.phase),
    secondsLeft = secondsLeft(state.expiresAtMs, SystemTimeSource.nowMs()),
    onAccept = ::acceptLocal,                            // pairing.acceptLocal() / protocol.respondAccept()
    onDecline = ::declineLocal,
    onDismiss = ::declineLocal,
)
```

Note the conflation the dialog imposes: `Failed` renders as `Declined`, and `AwaitingPeerReveal` as `Idle`. If you need a distinct "security check failed" screen, build one; do not try to express it through this dialog.

---

## 5. Shape C -- Your Own Transport (standalone modules)

```kotlin
@OptIn(FlashInternalApi::class)                       // PairingWireCodec is annotated
val protocol = DefaultFlashPairingProtocol(
    localFingerprintHex = myFingerprintHex,
    localDeviceId       = myDeviceId,                 // the id your peers know you by
    localName           = "My device",
    localModel          = "JVM",
    ephemeralPublicKeyProvider = { myEphemeralPublicKey },   // must return the SAME bytes every call
    sendFrame = { frame -> myOutbound.trySend(PairingWireCodec.encode(frame)) },  // enqueue only, never block
    // The TLS pin, NOT the fingerprint a hello claims: a hello is peer-controlled.
    peerIdentityPin = { peerId -> myTlsPins[peerId] },       // null => pairing fails closed
    timeSource = SystemTimeSource,
    timeouts = PairingTimeouts(),                     // 30 s request expiry, 30 s decision window
)

// inbound:
when (val inbound = PairingWireCodec.decode(text)) {
    is PairingWireCodec.Inbound.Hello -> {
        // Learned-from-the-wire cache for beginRequest only. Never a pin.
        hellos[peerId] = inbound.fingerprintHex
        if (inbound.request) sendHelloBack(peerId)    // a plain hello; never echo the request flag
    }
    is PairingWireCodec.Inbound.Frame -> protocol.onFrame(inbound.frame)
    null -> Unit                                      // not a pairing line
}
```

You then implement the same six rules from section 4.7 yourself, including the `PAIR_CONFIRM` on `PeerAccepted`. A test that reproduces the two-coordinator harness in `FlashPairingCoordinatorTest` is the cheapest way to prove it.

---

## 6. The Wire Sequence (v2)

All frames are one UTF-8 text line under `FLASH_PAIR`, `t=<type> key=value ...`, byte arrays standard Base64 (`PairingWireCodec` is the only codec; do not hand-roll one). They travel **outside** `FLASH_SEC` -- pairing runs before a session key exists, and that is by design.

```
I (initiator)                                          R (responder)
      ---- hello  fp_I, v=2 ------------------------------>
      <--- hello  fp_R, v=2 -------------------------------
Pair tapped: beginRequest(peerId, name, fp_R)
      ---- req   rid, did, name, model, fp_I, epk_I, ts, v=2, cm=H(fp_I, epk_I, N_I) --->
      <--- nonce rid, fp_R, epk_R, N_R ---------------------
      ---- reveal rid, N_I --------------------------------> R opens cm; both now show code6
Both users compare the code and accept locally.
      <--- acc   rid ---------------------------------------
      ---- con   rid, ch=H(code6) -------------------------> I has accepted too; R verifies ch
      <--- paired rid, fp_R, epk_R -------------------------  I verifies fp_R/epk_R match the nonce frame
   both Confirmed: trustPeer + markVerified + saveSessionKey(ecdh(epk, peer epk))
```

* `code6 = H("flash-pair-v2-code" || fp_I || fp_R || epk_I || epk_R || N_I || N_R) mod 10^6` -- **role-ordered**, so a reflected session derives a different code.
* The commitment is what stops a man-in-the-middle steering the code: `R` must reveal `N_R` before seeing `N_I`; `I` is committed to `N_I` before it sees `N_R`. A match therefore survives with probability 10^-6.
* Both sides require the claimed fingerprint to match the authenticated TLS key for the session (ADR-042). A first inbound connection can use its observed TLS leaf until the coordinator persists the pin after pairing.
* Timeouts: `requestExpiryMs = 30_000`, `decisionWindowMs = 30_000` (`PairingTimeouts`). The wire itself has no timeout field; expiry is local and neutral (`Expired`, not `Failed`).

Field naming trap: `FlashPairingFrame.Paired.peerFingerprintHex` is filled by the **sender with its own** fingerprint, even though the name reads from the receiver's side. The initiator compares it against what the responder announced in `nonce`, which is what makes the check meaningful.

---

## 7. Failure, Decline and Refusal

| Phase / reason | Wire signal | What the user should see |
|---|---|---|
| `Expired` | none (local timer) | "Pairing timed out. Try again." -- safe to retry |
| `DeclinedByPeer` | **no frame exists** -- nothing on the wire encodes a decline, and `DefaultFlashPairingProtocol` never emits `PeerDeclined` | in practice a decline is indistinguishable from a timeout: your screen reaches `Expired` |
| `peer-update-required` | peer reported `v=1` | "That device is running an older Flash. Update it, then pair again." |
| `invalid-fingerprint` | malformed/empty `fp` | "Pairing stopped: the security check failed." |
| `identity-mismatch` | claimed `fp` != TLS pin (or no pin) | same sentence; check the peer is connected and its key has not changed |
| `commit-mismatch` | `reveal` does not open `cm` | same sentence |
| `code-hash-mismatch` | `con` disagrees with local code hash | same sentence |
| `paired-material-mismatch` | `paired` repeats different `fp`/`epk` | same sentence -- usually an unstable ephemeral-key provider (trap 8) |
| `send-failed` / `paired-send-failed` | local write failed | "Couldn't reach X." -- the session died |

Do not auto-retry a `Failed`. `Expired` may be retried immediately.

---

## 8. Eleven Ways to Get This Wrong

| # | Mistake | Symptom | Fix |
|---|---|---|---|
| 1 | Trust store leaves `markVerified`/`isVerified` at the interface defaults | paired peers show "Not verified" forever | override all three; they are no-ops by default |
| 2 | `getPin` not backed by the transport's pins | every v2 pairing fails `identity-mismatch` | point `peerIdentityPin` at the same store the `TofuPinVerifier` writes |
| 3 | Blocking socket write in `sendToPeer` | `NetworkOnMainThreadException`, session closes mid-handshake | enqueue (`sendTextAsync`) and return reachability |
| 4 | Hello sent once, never re-asked | initiator can never pair: "Still can't reach ... then Pair again" | re-ask every 400 ms inside a 3 s window |
| 5 | No `onTick` | dialog counts down forever; next attempt says "already in progress" | 1 s tick while the phase is not `Idle` |
| 6 | No `PAIR_CONFIRM` on `PeerAccepted` | both users tap Accept, both sides `Expired` at 30 s | send `PairConfirm` with `session.expectedCodeHashHex` |
| 7 | Calling `beginRequest` before the hello | `Peer fingerprint missing` | wait for the hello, then begin |
| 8 | Ephemeral provider returns a **new** key per call | initiator fails `paired-material-mismatch` (the responder re-reads it for `paired`) | one stable key; the shipped hosts use one per engine lifetime |
| 9 | Using a device id the peer does not know (your TLS key hash, a fresh UUID) | pairing "works", then chat refuses: trust is stored under an id nobody dials | use the advertised identity id (`identity.deviceId.value`) |
| 10 | Expecting pairing over the hub relay | no pairing frames are relayed; nothing happens | pair over a direct session; the hub offers a directory one-tap instead (no session key) |
| 11 | Re-implementing the code or the codec | silent interop break on the next protocol change | use `DefaultFlashPairingProtocol` + `PairingWireCodec` (opt in with `@OptIn(FlashInternalApi::class)`) |

Also: `PairingWireCodec` and the `:core:security` pairing package are **evaluation-grade** today. `public-api.md` lists `FlashPairingProtocol` as *Stable* and `DefaultFlashPairingProtocol` as one of three documented exceptions to the "internal by default" rule, but the codec is annotated `@FlashInternalApi` and the Android driver is app code. Pin your version and re-read this page on upgrade.

---

## 9. Testing Your Integration

**In a JVM test (fastest, no devices):** copy the shape of `FlashPairingCoordinatorTest` -- two coordinators, each one's `sendToPeer` delivering straight into the other's `onInbound`, a recording `FlashTrustStore` that keeps pins and verified marks. Assert:

* both sides derive the **same** `code6` (the whole point);
* both stores end with `isTrusted == true` **and** `isVerified == true`;
* `beginPair` issued *before* the peer's hello still completes (the 2026-09-14 race);
* the two failure sentences stay distinct (`Couldn't reach` vs `Still can't reach`);
* expiry is *not* tested here -- it needs the real 30 s clock, and `PairingSessionStateMachineTest` owns that.

**On two devices** (this is the part no unit test replaces):

1. Both on the same Wi-Fi, discovery shows the peer, tap Pair on A.
2. Both show the **same six digits**. Compare them, then Accept on B first and on A.
3. Both return to "Paired"; A's list shows B as paired and **verified** (not "Not verified").
4. Kill and relaunch both apps: the pairing survives, and a 1:1 message is delivered.
5. Revoke on A: A shows B unpaired, and A can no longer message B.
6. Repeat with B initiating (both roles must work; the code is role-ordered).
7. Let one side sit on the dialog for 31 s: it expires cleanly and a second attempt succeeds.
8. Pair a device running an older build: you get the "older Flash" refusal, not a hang.

---

## 10. Definition of Done for a Pairing Integration

- [ ] Hello is sent on **every** session-up edge, and answered when `hrq=1` (never echoed).
- [ ] Inbound `FLASH_PAIR` reaches the driver before the encrypted-frame branch.
- [ ] `beginRequest` only runs with a known peer fingerprint (bounded wait, re-asking).
- [ ] v1 peers are refused with the update sentence.
- [ ] `PAIR_CONFIRM` is sent on `PeerAccepted`; nothing is sent by hand after that.
- [ ] 1 s tick runs while a session is in flight.
- [ ] On `Confirmed`: `trustPeer` + `markVerified` + `saveSessionKey(ecdh)` -- all three.
- [ ] Revoke clears trust and the verified mark, and the peer list is observable (a plain trust store is not).
- [ ] `Expired` is retryable, `Failed` is not, and the dialog never shows a failure as a decline unless you chose that.
- [ ] The two-device matrix in section 9 passes in **both** directions.

---

## 11. Integration boundaries

`FlashConfig` configures the platform factory's name, transfer resume, incoming acceptance
and receive path. Use an explicitly assembled host graph for custom identity, trust storage
or transport. The library's Android pairing flow still has the physical-device acceptance
check `PAIRLIB-01` in the test backlog.

## 12. Where These Facts Come From

| Fact | Source |
|---|---|
| Protocol, events, phases, timeouts | `core/security/src/commonMain/.../pairing/FlashPairingProtocol.kt`, `PairingSessionStateMachine.kt`, `FlashPairingFrames.kt` |
| Wire lines and field names | `core/security/src/commonMain/.../pairing/PairingWireCodec.kt` |
| v2 commitment / code derivation | `core/security/src/commonMain/.../pairing/PairingV2.kt` (section 6 quotes it) |
| Shared driver | `core/security/src/commonMain/.../pairing/FlashPairingCoordinator.kt` |
| Executable specification | `core/security/src/jvmTest/.../pairing/FlashPairingCoordinatorTest.kt` |
| Android twin + UI mapper | `app/src/main/java/com/transfer/flash/pairing/PairingCoordinator.kt`, `PairingUiMapper.kt` |
| Reference graph assembly (identity, TLS pins, router, session-up) | `core/engine/src/androidMain/.../Flash.kt` lines 239-300; `app/.../debug/DiscoveryEngineHolder.kt` lines ~700-800, 1560-1600, 1880-1900, 2400-2470 |
| Inbound routing order and the pairing seam | `core/engine/src/commonMain/.../FlashInboundRouter.kt` (`routeInboundText`, `PAIR_PREFIX`) |
| Trust store defaults and semantics | `core/security/src/commonMain/.../trust/FlashTrustStore.kt`, `androidMain/.../AndroidPreferencesTrustStore.kt` |
| "Trusted but not encrypted" (hub one-tap) | `core/messaging/src/commonMain/.../FlashChatRepository.kt` (`pairColleagueFromDirectory` KDoc) |
| Stability claims | `docs/architecture/public-api.md` (`FlashPairingProtocol`, and the pairing types under `/core/security`) |
| The gate that device-tests it | `docs/migration/PAIRING-GATE-RUNBOOK.md` |
