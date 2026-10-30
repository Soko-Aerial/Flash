# Domain D09: Calls, WebRTC, Push-to-Talk, Screen Share — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D09 — Calls, WebRTC, Push-to-Talk, Screen Share (`:core:calling`, `:core:ptt`)  
**Auditor:** Flash Security Audit Team (Domain D09 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash provides real-time voice, video, and screen sharing via WebRTC (vendored `webrtc-kmp` fork) in `:core:calling`, and half-duplex low-latency Push-to-Talk in `:core:ptt`. 

Signaling for 1:1 and group calls travels over Flash's WebSocket mesh. Media transport utilizes WebRTC DTLS-SRTP with host ICE candidates (`iceServers = emptyList()`, LAN-only). PTT utilizes a custom low-overhead protocol with Opus audio over WebSocket binary frames (`PttAudioFrame`) and a state-machine driven floor arbiter (`PttFloorMachine`).

The audit verified that incoming calls cannot force camera/mic activation, PTT floor control strictly requires bilateral pairing, and STUN/TURN credentials do not leak. However, vulnerabilities exist in media-to-identity binding, ICE candidate topology exposure, and mid-call member revocation:
- **Medium Severity (`SEC-D09-01`):** WebRTC DTLS-SRTP ephemeral certificate fingerprints are not bound to the peer's permanent cryptographic identity key.
- **Low Severity (`SEC-D09-02`):** ICE candidate gathering exposes all private network interface IP addresses to other participants.
- **Low Severity (`SEC-D09-03`):** Mid-call group member removals do not terminate live WebRTC call legs.

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | Signalling authenticity: `PeerTrustPolicy.mayRing` gating | **Not vulnerable** | Both `DiscoveryEngineHolder.kt:1640` and `DesktopEngine.kt:1736` wire `isTrustedPeer` to `PeerTrustPolicy.mayRing(isPaired, isOrg)`. Untrusted peers are auto-declined without ringing. |
| 2 | Call-ID & session spoofing: origin validation | **Not vulnerable** | `CallCoordinator.kt:359` enforces `if (frame !is CallWireFrame.GroupJoin && frame.from != peerId) return false`, preventing caller ID spoofing across connections. |
| 3 | Forced answer or camera/mic activation | **Not vulnerable** | Inbound calls transition to `RINGING`. Media acquisition (`MediaDevices.getUserMedia`) is strictly deferred to explicit user invocation of `accept()` (`FlashCallSession.kt:1381`). |
| 4 | ICE candidate exposure: local IP leakage | **Vulnerable** (`SEC-D09-02`) | Host ICE candidates expose internal IP addresses, docker networks, and VPN endpoints to all group participants. |
| 5 | TURN/STUN configuration and credentials | **Not applicable** | Flash calling is LAN/hotspot-only; `iceServers = emptyList()` across all sessions (`FlashCallSession.kt:64`). |
| 6 | DTLS-SRTP fingerprint verification against identity | **Vulnerable** (`SEC-D09-01`) | Ephemeral self-signed DTLS certificates are not cryptographically bound to the peer's pinned identity key. |
| 7 | Group-call roster & join authorization | **Not vulnerable** | `FlashGroupCallSession.kt:994` validates that joining peers are active members of the group roster (`isGroupMember`). |
| 8 | PTT floor control abuse | **Not vulnerable** | `PttSessionEngine.kt:255, 283` requires `isTrustedPeer(peerId)` (bilateral pairing). Audio playback is restricted to `listening.holderId == peerId`. |
| 9 | Screen-share consent and control | **Not vulnerable** | Screen capture (`startScreenShare`) can only be triggered by local user action; remote peers receive only informational announcements. |
| 10 | Vendored `webrtc-kmp` fork patches | **Not vulnerable** | Patches add JVM desktop support (`dev.onvoid.webrtc`), lazy factory initialization, and thread safety. |
| 11 | Permissions prompts & background execution | **Not vulnerable** | Android requires `RECORD_AUDIO` and `CAMERA` runtime permissions before acquisition. Calls use foreground services. |
| 12 | Call-end races & mid-call revocation | **Vulnerable** (`SEC-D09-03`) | Live WebRTC legs are not torn down when a participant is removed from the group roster mid-call. |

---

## 3. Detailed Audit Findings

### SEC-D09-01 — WebRTC DTLS-SRTP Media Transport Lacks Certificate Fingerprint Binding Against Authenticated Identity Key
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A1** (LAN on-path attacker), **A2** (Malicious group relay participant)
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:1465`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt:1168`
- **CWE / standard:** CWE-353: Missing Support for Integrity Check; CWE-295: Improper Certificate Validation; CVSS:3.1/AV:A/AC:H/PR:L/UI:N/S:U/C:H/I:H/A:N (6.8 Medium)
- **What is wrong:** WebRTC establishes media encryption using DTLS-SRTP. The underlying `PeerConnection` generates ephemeral self-signed certificates on the fly. In standard WebRTC, the certificate fingerprint is exchanged via SDP (`a=fingerprint:sha-256 ...`). However, Flash does not sign the SDP or bind the DTLS certificate fingerprint to the peer's pinned ECDSA identity public key.
- **Impact:** In group calls where signaling frames are forwarded across intermediate peers (mesh forwarding via `GroupJoin` and `GroupAccept`), an on-path or relay peer can tamper with SDP offer/answers or substitute the media transport endpoint without breaking signaling authentication, intercepting or injecting media streams.
- **Evidence:** 
  In [FlashCallSession.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt#L1465):
  ```kotlin
  iceServers = emptyList(), // LAN-only: host candidates (ADR-025).
  ```
  `CallSdp.kt` and `CallCoordinator.kt` process SDP text without verifying that `a=fingerprint` matches any cryptographic attestation signed by the peer's identity key.
- **Why existing guards do not stop it:** Flash assumed that TLS transport on the WebSocket signaling link was sufficient, omitting signaling-to-media DTLS binding.
- **Verifier result:** survived
- **Recommended fix:** Include the DTLS certificate fingerprint in the call signaling frame, signed with the device's private identity key, and verify the signature before applying the remote SDP.
- **Related:** ADR-025, ADR-044 V2.

---

### SEC-D09-02 — WebRTC Host Candidate Gathering Leaks Internal Network Topology and Private IP Addresses
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Paired peer), **A1** (Vouched group call member)
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt:1465`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallWireFrame.kt:60-70`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N (3.5 Low)
- **What is wrong:** During call setup, WebRTC gathers host candidates across all network interfaces on the device (Ethernet, Wi-Fi, VPN tunnels, Docker/virtual interfaces). These candidates (`candidate:1 1 udp 2130706431 10.x.x.x ...`) are serialized into `CallWireFrame.Candidate` frames and transmitted to the remote peer.
- **Impact:** In group calls with vouched members (unpaired colleagues or contacts), internal IP addresses, subnet structures, and active VPN interface configurations are exposed to remote participants who should only know the local LAN address.
- **Evidence:** [FlashCallSession.kt:1465](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt#L1465), [CallWireFrame.kt:60-70](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallWireFrame.kt#L60-L70).
- **Why existing guards do not stop it:** `LocalInterfacePicker` selects the preferred interface for dialing, but WebRTC's default ICE gatherer enumerates all active local interfaces.
- **Verifier result:** survived
- **Recommended fix:** Filter ICE candidates before transmission, discarding candidates belonging to non-active or private VPN subnets, or configure WebRTC interface filtering.
- **Related:** ADR-025.

---

### SEC-D09-03 — Mid-Call Group Member Removal Does Not Terminate Live WebRTC Call Legs
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Removed group member)
- **Component:** `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt:994-1035`, `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt:398`
- **CWE / standard:** CWE-613: Insufficient Session Expiration; CVSS:3.1/AV:A/AC:L/PR:L/UI:N/S:U/C:L/I:N/A:N (3.5 Low)
- **What is wrong:** When an administrator removes a member from a v2 group, `RosterGroupGate` updates its internal state and emits a change event. However, `CallCoordinator` does not observe `groupGate.changes`, and `FlashGroupCallSession` contains no periodic membership re-check for established legs.
- **Impact:** A member removed from a group during an active group call remains in the WebRTC call mesh, continuing to send and receive real-time audio and video until the call terminates naturally.
- **Evidence:** In [FlashGroupCallSession.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt#L994), `isGroupMember` is checked only upon arrival of `GroupAccept` or `GroupJoin`. Established legs in `legs` are never re-evaluated against the roster.
- **Why existing guards do not stop it:** Documented in `docs/security.md` §9 as an accepted limit: *"An already-established group call leg is not re-checked: a member removed mid-call stays connected until the call ends"*. However, code lacks an eviction hook on roster mutation.
- **Verifier result:** survived
- **Recommended fix:** Wire `groupGate.changes` into `CallCoordinator` to trigger `groupSession.evictNonMembers()`, closing the PeerConnection leg for any removed member.
- **Related:** `docs/security.md` §9, ADR-044 V2.

---

## 4. Candidate Findings Refuted During Audit

| Candidate | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D09-01: Forced Camera / Microphone Activation | An incoming call can force the receiver's camera or microphone to turn on without user interaction. | **Refuted.** `CallCoordinator.kt:348` places incoming calls in `RINGING`. In `FlashCallSession.kt:1381`, `startMedia()` (which invokes `MediaDevices.getUserMedia`) is called only from `accept()`, which is triggered strictly by user UI button clicks. |
| REJ-D09-02: PTT Floor Hijacking by Strangers | An unauthenticated LAN device can send PTT frames to broadcast audio through the speaker. | **Refuted.** `PttSessionEngine.kt:255, 283` strictly verifies `isTrustedPeer(peerId)` for all incoming control frames. In `onAudioBytes` (line 366), audio packets are rejected unless the session is in `Listening` state and `peerId == listening.holderId`. |
| REJ-D09-03: STUN/TURN Credential Leakage | Flash leaks network credentials or contacts external STUN/TURN servers. | **Refuted.** `FlashCallSession.kt:1465` configures `iceServers = emptyList()`. Flash calling is host/LAN-only and never makes external DNS queries or STUN requests. |

---

## 5. Files Inspected

- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallCoordinator.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashCallSession.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/FlashGroupCallSession.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallWireFrame.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallFrameCodec.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/CallSdp.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/ScreenCapture.kt`
- `core/calling/src/commonMain/kotlin/com/transfer/flash/core/calling/ScreenShare.kt`
- `core/calling/src/androidMain/kotlin/com/transfer/flash/core/calling/FlashWebRtcEngine.kt`
- `core/calling/src/androidMain/kotlin/com/transfer/flash/core/calling/FlashLocalNetworkDetector.kt`
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/PttSessionEngine.kt`
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/floor/PttFloorMachine.kt`
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/protocol/PttAudioFrame.kt`
- `core/ptt/src/commonMain/kotlin/com/transfer/flash/core/ptt/protocol/PttSessionFrame.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
- `docs/security.md` §9
