# Domain D10: Discovery and Presence — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D10 — Discovery and Presence (`:core:discovery`, `:core:network:presence`, `:core:network:remembered`, `:core:network:sweep`)  
**Auditor:** Flash Security Audit Team (Domain D10 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash provides local peer discovery and presence management across multiple radio layers and transports:
- **DNS-SD / mDNS:** Android NSD (`NsdTransport.kt`) and Desktop JmDNS (`JmdnsTransport.kt`).
- **UDP Multicast & Directed Broadcast:** `MulticastTransport.kt` (`239.255.45.82:45824`).
- **Multi-Radio Aggregator:** `CompositeDiscovery.kt` with cross-transport deduplication, priority ranking (`LAN > WIFI_DIRECT > WIFI_AWARE > BLE`), and presence sweeping.
- **Connection Planner & Auto-Connector:** `ConnectionPlanner.kt` and `AutoConnector.kt`, which orchestrate connection establishment via discovery sightings, presence tips, remembered routes, hotspot gateway auto-probes, and DR3 subnet sweeps (`SubnetSweeper.kt`).
- **Presence Exchange:** `PresenceExchange.kt` and `PresenceState.kt`, exchanging salted hash queries (`FLASH_PRES`) to detect mutual contact availability.
- **Remembered Routes:** `RememberedRoutes.kt` (DR1, ADR-047), persisting authenticated endpoints for paired contacts.

The audit verified that `RememberedRoutes` is strictly protected against unauthenticated route injection, mDNS identity hijacking is blocked by TLS pinned keys on paired sessions, and TXT parsing strictly enforces length and memory bounds. However, severe security defects were discovered in subnet sweeping, presence sharing, group invite address hints, and unencrypted local announcements:
- **High Severity (`SEC-D10-01`):** Subnet sweep and gateway auto-probes dial responsive IP addresses with null expected device identity, permitting rogue hosts to poison TOFU pins for arbitrary unpaired peers.
- **Medium Severity (`SEC-D10-02`):** `PresenceState.mayShare` lacks contact authorization checks, acting as an unrestricted presence and network endpoint oracle for arbitrary device IDs.
- **Medium Severity (`SEC-D10-03`):** Group invite address hints are dialed without destination IP sanitization, enabling blind intranet port probing and SSRF.
- **Low Severity (`SEC-D10-04`):** Unencrypted UDP multicast and mDNS announcements broadcast persistent UUIDs, friendly names, device models, and key prefixes to the entire LAN.
- **Low Severity (`SEC-D10-05`):** Unconditional gateway probing on enterprise Wi-Fi networks triggers intrusion detection alerts and exposes clients to rogue hotspot APs.

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | mDNS/NSD/JmDNS spoofing and poisoning (rogue service claiming paired id) | **Not vulnerable** | When dialing an endpoint claiming a paired peer's ID, `connectManual` enforces pinned key verification in `TofuX509TrustManager.kt` and `WsFlashNetwork.kt:435`. Rogue certs fail TLS handshake immediately (`REJ-D10-03`). |
| 2 | TXT record parsing: bounds, memory, crash safety | **Not vulnerable** | `TxtCodec.kt` and `MulticastProtocol.kt` enforce strict field bounds (`MAX_TEXT_FIELD_CHARS = 64`, `MAX_CAPS_VALUE_LENGTH = 120`, `MAX_DATAGRAM_BYTES = 1400`). Input is trimmed and decoders never throw on malformed data (`REJ-D10-02`). |
| 3 | Information disclosure in announcements (names, IDs, features) | **Vulnerable** (`SEC-D10-04`) | Plaintext UDP multicast and mDNS packets broadcast persistent `device_id`, friendly names, hardware models, and `fp8` key prefixes every 20s in default STANDARD mode. |
| 4 | Enumeration of devices on a network | **Vulnerable** (`SEC-D10-04`, `SEC-D10-05`) | Passive listeners can map all Flash devices and user identities on a subnet. Active gateway probing exposes Flash clients to gateway operators. |
| 5 | Remembered-routes poisoning | **Not vulnerable** | `RememberedRoutes.onAuthenticated` is strictly called after successful TLS authentication of pinned keys and requires `isPaired(deviceId) == true`. Unauthenticated discovery sightings cannot touch stored routes (`REJ-D10-01`). |
| 6 | Manual/hint dial of attacker-chosen addresses (SSRF-like dial) | **Vulnerable** (`SEC-D10-03`) | Group invite `addressHints` are dialed directly via `connectManual` without verifying `PresenceCodec.isDialableHost`, allowing probes to localhost, cloud metadata, or intranet IPs. |
| 7 | Group address hints in invites | **Vulnerable** (`SEC-D10-03`) | In addition to lack of IP filtering, `hint.split(":")` in host wiring fails completely on IPv6 address hints. |
| 8 | Presence/online-dot oracles | **Vulnerable** (`SEC-D10-02`) | `PresenceState.mayShare` answers salted hash queries for any device ID whose `shareFlags` is true, allowing any paired peer to track physical presence and endpoints of third parties. |
| 9 | Hotspot assumptions | **Vulnerable** (`SEC-D10-01`, `SEC-D10-05`) | Gateway probing runs unconditionally on all LAN networks; rogue hosts answering gateway probes or subnet sweeps can claim any unpaired device ID and poison its pin. |

---

## 3. Detailed Audit Findings

### SEC-D10-01 — Automatic Unauthenticated Subnet Sweep & Gateway Auto-Probe Dials Pin Rogue Hosts and Poison TOFU Trust Store for Unpaired Peers
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** **A1** (LAN attacker, Rogue AP / Gateway operator)
- **Component:** `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt:136-165`, `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt:451-475`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:2210-2224`, `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1951`
- **CWE / standard:** CWE-295: Improper Certificate Validation; CWE-306: Missing Authentication for Critical Function; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H (8.1 High)
- **What is wrong:** 
  Flash features automatic discovery mechanisms that dial IP addresses without a known device identity:
  1. Hotspot Gateway Auto-Probe: `gatewayHosts` automatically dials port 45822 on the default gateway (`192.168.1.1:45822`).
  2. Subnet Sweep (DR3): `sweepHosts` sweeps the `/24` subnet on port 45822 and automatically dials every host that answers.
  
  In `ConnectionPlanner.kt:168, 179`, these dials are generated with `peerDeviceId = null`. When `AutoConnector.runDial` executes, it invokes `dial(d)`, which falls through to `else -> networkImpl.connectManual(d.host, d.port)` without specifying a peer device ID.
  
  In `WsFlashNetwork.connectManual(host, actualPort, null)`, because `resolvedPeerDeviceId` is null, `client.connect` accepts ANY certificate presented by the remote host. After the TLS handshake completes, the remote host transmits its `FLASH_WS_HELLO` frame specifying an arbitrary `deviceId`. In `WsFlashNetwork.kt:456-475`, Flash calls `verifier.isPinned(peerDevice.id.value, deferredLeaf)`. For any device ID that has not yet been pinned in `trustStore`, `TofuPinVerifier.isPinned` executes:
  ```kotlin
  recordPin?.invoke(deviceId, presented)
  ```
  This immediately commits the rogue host's TLS key fingerprint into `trustStore` under the claimed `deviceId`, and registers the session as an active connection for that device ID.
- **Impact:** 
  1. **TOFU Pin Poisoning / Denial of Service:** A rogue host on the LAN can listen on port 45822, wait for Flash's automatic gateway probe or subnet sweep to connect, and claim the device ID of any targeted peer (e.g. Alice). Flash automatically binds and persists the attacker's key under Alice's ID. When the real Alice later attempts to connect, her TLS handshake is rejected with `PeerUnavailable` ("This device's security key does not match the one Flash trusted before"), permanently locking Alice out until the user clears app storage.
  2. **Unsolicited Message Ingestion:** By registering an active session under the claimed ID, the rogue host can exploit `SEC-D05-01` (inbound chat text ingestion ungated by pairing) to inject spam or phishing messages directly into the victim's chat list without user interaction.
- **Evidence:** 
  In [AutoConnector.kt:136-165](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt#L136-L165):
  ```kotlin
  planner.plan(... sweepHosts = sweepHosts() ...)
  ```
  In [DiscoveryEngineHolder.kt:2210](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L2210):
  ```kotlin
  else -> networkImpl.connectManual(d.host, d.port)
  ```
  In [WsFlashNetwork.kt:456-460](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt#L456-L460):
  ```kotlin
  val deferredLeaf = connection.deferredPeerLeafFingerprintHex
  if (deferredLeaf != null) {
      val verifier = tlsOptions?.pinVerifier
      val bound = verifier != null && verifier.isPinned(peerDevice.id.value, deferredLeaf)
  ```
  In [TofuPinVerifier.kt:47-50](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/tls/TofuPinVerifier.kt#L47-L50):
  ```kotlin
  return if (known.isNullOrBlank()) {
      recordPin?.invoke(deviceId, presented)
      true
  }
  ```
- **Why existing guards do not stop it:** 
  `DialedIdBindingTest` only tests dials where `peerDeviceId` is explicitly specified. For gateway probes and sweep hits, `peerDeviceId` is intentionally null, and Flash erroneously treats first-contact on these unsolicited probes as legitimate TOFU pinning.
- **Verifier result:** survived
- **Recommended fix:** 
  Never invoke `recordPin` on dials originating from gateway probes or subnet sweep hits (`d.peerDeviceId == null`). Sessions established from unnamed probes must remain in an unpinned, ephemeral state and must never persist pins into `trustStore` until explicit user confirmation or mutual pairing v2 completes.
- **Related:** SEC-D02-02, SEC-D05-01, ADR-040, ADR-047.

---

### SEC-D10-02 — Presence State Machine Acts as Unrestricted Third-Party Presence and Network Endpoint Oracle
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Paired peer), **A1** (LAN peer)
- **Component:** `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/presence/PresenceState.kt:393-399`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information to an Unauthorized Actor; CWE-203: Observable Discrepancy; CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:N/A:N (6.5 Medium)
- **What is wrong:** 
  Flash exchanges presence via `FLASH_PRES` frames. A peer sends a `Want` frame containing truncated hashes `H(salt, targetDeviceId)`. In `PresenceState.mayShare`:
  ```kotlin
  private fun mayShare(receiver: String, peer: Peer, subject: String, direct: Boolean, view: PresenceLocalView): Boolean {
      if (subject == receiver || subject == localDeviceId) return false
      val flag = shareFlags[subject]
      if (if (direct) flag != true else flag == false) return false
      if (view.rosters.any { receiver in it && subject in it }) return true
      return peer.theirWant.isNotEmpty() && peer.hashOf(subject) in peer.theirWant
  }
  ```
  Line 398 unconditionally returns `true` if `peer.hashOf(subject) in peer.theirWant`. The code fails to verify that `subject` has authorized `receiver`, or that `receiver` is a contact/paired peer of `subject`.
- **Impact:** 
  Any paired contact of device B (e.g. colleague Charlie) can query device B about any third-party device ID (e.g. Alice). If Alice is on device B's LAN or connected to device B, device B returns a `PresenceFrame.Report` revealing Alice's online presence, liveness state (`Connected` vs `Seen`), and exact network endpoint (`hostAddress:port`). This allows malicious peers to turn mutual contacts into presence and physical network location trackers.
- **Evidence:** 
  In [PresenceState.kt:393-399](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/presence/PresenceState.kt#L393-L399):
  Line 398 does not inspect `view.trusted` or any relationship between `receiver` and `subject`.
- **Why existing guards do not stop it:** 
  The developers assumed that salt-hashing `H(salt, id)` was sufficient privacy protection because an attacker cannot invert the hash. However, device IDs are public static UUIDs broadcast in cleartext across the LAN (see `SEC-D10-04`), allowing attackers to pre-compute `H(salt, id)` for any target.
- **Verifier result:** survived
- **Recommended fix:** 
  Enforce contact mutuality in `mayShare`: only share presence about `subject` if `receiver` and `subject` are in a common active group roster (`view.rosters.any { receiver in it && subject in it }`), or if `receiver` is in `subject`'s explicit contact list.
- **Related:** `docs/network/PRESENCE-COORDINATION-PLAN.md`, PC4.

---

### SEC-D10-03 — Unfiltered Address Hints in Group Invites Enable Blind Intranet SSRF and Port Probing
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A2** (Malicious group inviter), **A1** (Invite distributor)
- **Component:** `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInvite.kt:40-48`, `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt:2062-2096`, `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:1500-1509`
- **CWE / standard:** CWE-918: Server-Side Request Forgery (SSRF); CWE-20: Improper Input Validation; CVSS:3.1/AV:N/AC:L/PR:N/UI:R/S:C/C:L/I:L/A:N (6.1 Medium)
- **What is wrong:** 
  v2 Group invites (`flash://g/1/...`) can carry up to 3 `addressHints` (`GroupInvite.kt:40`). In `GroupInvite.kt`, validation is limited strictly to `hint.encodeToByteArray().size in 1..64`.
  
  When an invite is accepted in `RealFlashChatRepository.kt:2051`:
  ```kotlin
  dialHintsInOrder(invite.groupId, invite.inviterDeviceId, invite.addressHints)
  ```
  `dialHintsInOrder` invokes `onConnectPeerWithHints`. In `DiscoveryEngineHolder.kt:1500-1509`:
  ```kotlin
  val parts = hint.split(":")
  val host = parts[0]
  val port = parts.getOrNull(1)?.toIntOrNull() ?: WsTransferServer.PREFERRED_PORT
  networkImpl.connectManual(host, port, peerId)
  ```
  Neither the repository nor the host engine performs any destination filtering. Unlike presence tips (which strictly enforce `PresenceCodec.isDialableHost`), address hints permit loopback addresses (`127.0.0.1`), link-local / cloud metadata services (`169.254.169.254`), and arbitrary internal intranet hosts and ports (`10.0.0.1:22`, `192.168.1.1:80`).
  
  Additionally, `hint.split(":")` breaks IPv6 literals (e.g. `[::1]:45822` or `fe80::1`), parsing invalid hostnames.
- **Impact:** 
  An attacker distributing a malicious `flash://g/1/...` invite link can induce victim devices to send TCP SYN packets and TLS ClientHellos to arbitrary internal ports and loopback services when the user clicks "Join". This enables intranet port mapping, internal service probing, and potential denial-of-service on fragile embedded TCP devices.
- **Evidence:** 
  In [GroupInvite.kt:40-48](file:///C:/Users/KaliOxygen/Downloads/Flash/core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInvite.kt#L40-L48):
  ```kotlin
  require(addressHints.size <= 3)
  for (hint in addressHints) {
      require(hint.encodeToByteArray().size in 1..64)
  }
  ```
  In [DiscoveryEngineHolder.kt:1500-1509](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L1500-L1509):
  No IP validation; `parts = hint.split(":")`.
- **Why existing guards do not stop it:** 
  Developers relied on `PresenceCodec.isDialableHost` for presence tips, but forgot to apply the same validator to `GroupInvite` address hints.
- **Verifier result:** survived
- **Recommended fix:** 
  Validate each hint using `PresenceCodec.isDialableHost` in `GroupInvite.init` and `GroupInviteCodec.decode`. Use robust URI/endpoint parsing that handles IPv6 brackets properly (`host.substringBeforeLast(':')` / `host.substringAfterLast(':')`).
- **Related:** GM-4, GM-8, ADR-044, ADR-073.

---

### SEC-D10-04 — Unencrypted Local Multicast and mDNS Advertisements Disclose Hardware Identifiers, User Friendly Names, and Cryptographic Key Prefixes
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Passive network eavesdropper on LAN / Wi-Fi)
- **Component:** `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/TxtCodec.kt:55-68`, `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransport.kt:373-399`
- **CWE / standard:** CWE-200: Exposure of Sensitive Information; CWE-359: Exposure of Private Personal Information; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N (3.5 Low)
- **What is wrong:** 
  In default `STANDARD` mode, Flash continuously broadcasts unencrypted UDP datagrams to `239.255.45.82:45824` (and directed broadcast addresses) and DNS-SD mDNS TXT records every 20 seconds.
  
  In `TxtCodec.encode`:
  ```kotlin
  KEY_DEVICE_ID to identity.deviceId.value.trim(),
  KEY_NAME to identity.friendlyName.trim(),
  KEY_MODEL to identity.deviceModel.trim(),
  KEY_PROTO to identity.protocolVersion.toString(),
  KEY_FP8 to it
  ```
- **Impact:** 
  Any eavesdropper on the local network (e.g. coffee shop, hotel, airport Wi-Fi) can observe the static `deviceId`, friendly name (often containing user full names), exact hardware model, and key fingerprint prefix `fp8`. This allows persistent device tracking across physical locations and user identity profiling without establishing a connection.
- **Evidence:** 
  In [TxtCodec.kt:55-68](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/TxtCodec.kt#L55-L68) and [MulticastTransport.kt:373-399](file:///C:/Users/KaliOxygen/Downloads/Flash/core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransport.kt#L373-L399).
- **Why existing guards do not stop it:** 
  `GHOST` mode disables advertising, but `STANDARD` mode is enabled by default on app launch.
- **Verifier result:** survived
- **Recommended fix:** 
  Do not include `deviceModel` in discovery broadcasts. Allow users to suppress friendly name broadcasting or default to randomized or pseudonymous friendly names on untrusted public Wi-Fi networks.
- **Related:** ADR-047, plan C3.2.

---

### SEC-D10-05 — Unconditional Gateway Probing on Enterprise Networks Triggers IDS/IPS Port Scan Alerts and Exposes Clients to Rogue Hotspot APs
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Network monitor, Rogue AP operator)
- **Component:** `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt:2213-2220`, `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:470`
- **CWE / standard:** CWE-200: Information Exposure; CWE-400: Uncontrolled Resource Consumption; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N (3.5 Low)
- **What is wrong:** 
  To support tethering on Android mobile hotspots (where SoftAP drops mDNS), Flash wires `gatewayHosts` to `LocalNetworkAddresses(context).ipv4Gateways()`.
  
  However, this probe is not restricted to mobile hotspot interfaces. On ANY Wi-Fi network (including enterprise office Wi-Fi, university networks, and home routers), `AutoConnector` automatically dispatches TCP connections to port 45822 of the network's default gateway router.
- **Impact:** 
  1. Monitored enterprise networks (corporate networks with IDS/IPS, Cisco/Aruba/Palo Alto equipment) log persistent port scan / probe alerts against the default gateway on port 45822, flagging Flash user devices as compromised or conducting network reconnaissance.
  2. On untrusted public Wi-Fi networks, connecting to the gateway immediately alerts the gateway operator that a Flash client is active.
- **Evidence:** 
  In [DiscoveryEngineHolder.kt:2213-2220](file:///C:/Users/KaliOxygen/Downloads/Flash/app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt#L2213-L2220).
- **Why existing guards do not stop it:** 
  Code contains no heuristic to distinguish a true mobile hotspot from a standard router gateway.
- **Verifier result:** survived
- **Recommended fix:** 
  Only probe gateway hosts if the active network interface has characteristics of a Wi-Fi tether (e.g. `192.168.43.1` or specific Android tethering subnets), or require the user to toggle "Hotspot Mode" before probing gateways.
- **Related:** `LocalNetworkAddresses.kt:39`, `AutoConnector.kt:139`.

---

## 4. Candidate Findings Refuted During Audit

| Candidate | Hypothesis | Refutation Evidence |
|---|---|---|
| REJ-D10-01: Remembered-Routes Poisoning via Unsolicited Discovery Sightings | An attacker can send spoofed mDNS or multicast announcements to inject or overwrite routes in `RememberedRoutes`. | **Refuted.** `RememberedRoutes.kt:207` only records routes via `onAuthenticated(deviceId, host, port)`. `onAuthenticated` is strictly called from `WsFlashNetwork.kt:484` and `JvmWsFlashNetwork.kt:403` after a successful TLS handshake that matches the pinned identity key of an already-paired device (`isPaired(deviceId) == true`). Discovery sightings are never written to `RememberedRoutes`. |
| REJ-D10-02: TXT Record Parsing Buffer Overflow / Memory Exhaustion | An attacker sending oversized TXT records or invalid UTF-8 strings can crash the discovery parser or trigger OOM. | **Refuted.** `TxtCodec.decode()` and `MulticastProtocol.decode()` enforce strict bounds. `MulticastProtocol.MAX_DATAGRAM_BYTES` limits UDP packets to 1400 bytes, `MAX_TEXT_FIELD_CHARS` caps strings to 64 chars, and `TxtCodec.decode` trims and safely parses values without throwing exceptions. Oversized capability sets are truncated at flag boundaries (`MAX_CAPS_VALUE_LENGTH = 120`). |
| REJ-D10-03: DNS-SD / mDNS Spoofed Identity Hijacking on Paired Connections | A rogue mDNS advertiser claiming a paired peer's `device_id` can impersonate the paired peer and receive confidential messages. | **Refuted.** When dialing a discovered endpoint claiming `deviceId = Alice`, Flash passes `Alice` to `connectManual(host, port, peerDeviceId)`. In `WsFlashNetwork.kt:374-383` and `TofuX509TrustManager.kt:103`, Flash requires the answering TLS certificate to match Alice's pinned fingerprint. The rogue advertiser lacks Alice's private identity key, so the TLS handshake fails immediately with `SSLPeerUnverifiedException` before any protocol frames or chat messages are exchanged. |

---

## 5. Files Inspected

- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/TxtCodec.kt`
- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/CompositeDiscovery.kt`
- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/core/DiscoveryModePolicy.kt`
- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastTransport.kt`
- `core/discovery/src/commonMain/kotlin/com/transfer/flash/core/discovery/multicast/MulticastProtocol.kt`
- `core/discovery/src/androidMain/kotlin/com/transfer/flash/core/discovery/nsd/NsdTransport.kt`
- `core/discovery/src/jvmMain/kotlin/com/transfer/flash/core/discovery/jmdns/JmdnsTransport.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/ConnectionPlanner.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/planner/AutoConnector.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/presence/PresenceState.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/presence/PresenceCodec.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/presence/PresenceExchange.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/remembered/RememberedRoutes.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/sweep/SubnetSweeper.kt`
- `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/ws/WsFlashNetwork.kt`
- `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt`
- `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/tls/TofuPinVerifier.kt`
- `core/security/src/commonMain/kotlin/com/transfer/flash/core/security/group/GroupInvite.kt`
- `core/messaging/src/commonMain/kotlin/com/transfer/flash/core/messaging/RealFlashChatRepository.kt`
- `app/src/main/java/com/transfer/flash/debug/DiscoveryEngineHolder.kt`
- `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt`
