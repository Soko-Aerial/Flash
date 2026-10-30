# Domain D02: Transport Security (TLS, Pinning, TOFU) — White-Box Security Audit

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Domain:** D02 — Transport Security (TLS, Pinning, and TOFU)  
**Auditor:** Flash Security Audit Team (Domain D02 Specialist)  
**Status:** COMPLETE  

---

## 1. Executive Summary

Flash relies on an end-to-end authenticated and encrypted transport layer across all direct peer-to-peer (LAN, Wi-Fi Direct) and client-to-hub network communications. 

For **Peer-to-Peer (P2P)** networking:
- Flash uses standard TLS 1.3 / TLS 1.2 encapsulation over TCP/IP, terminating in a custom WebSocket framing layer (`WebSocketCodec`, `WsTransferServer`, `WsTransferClient`).
- Peer authentication bypasses the traditional Hierarchical Public Key Infrastructure (Web PKI / Certificate Authorities) in favor of Trust On First Use (TOFU) and out-of-band identity pinning based on SHA-256 fingerprints of SubjectPublicKeyInfo (`TofuX509TrustManager`, `FlashPinVerifier`, `TofuPinVerifier`).
- Mutual TLS (mTLS) is enforced: servers demand client certificates (`needClientAuth = true`), and clients demand server certificates.
- Cross-layer bindings exist to tie TLS public key fingerprints directly to Flash device identifiers, Pairing v2 commitments, and Group Proof transcripts.

For **Enterprise Hub** networking:
- Hub communications use HTTP/1.1 and WebSockets via Ktor CIO (`EnterpriseEnrollmentClient`, `KtorRelayTransport`, `EnterpriseHub`).
- Hub TLS is terminated either by a reverse proxy (`tlsMode=proxy`), a custom keystore (`tlsMode=cert`), or a self-signed P-256 certificate (`tlsMode=self`).

This white-box audit evaluated all 10 domain checklist items and discovered **5 confirmed security findings**:
- **1 Critical:** Trust manager validates pin against *any* certificate in the presented chain rather than strictly the leaf certificate (`SEC-D02-01`), allowing an active network attacker to impersonate any pinned device by appending the victim's certificate to an attacker-controlled leaf.
- **1 High:** Unsolicited inbound peer connections trigger automatic and unverified TOFU pin recording (`SEC-D02-02`), allowing an unauthenticated LAN attacker to poison peer pins and permanently deny service to legitimate contacts.
- **1 Medium:** Client-to-Hub REST and WebSocket connections lack TLS certificate and public key pinning (`SEC-D02-03`), exposing enrollment and relay credentials to interception by corporate or rogue system-trusted CAs.
- **1 Low:** WebSocket endpoints omit `Origin` header validation (`SEC-D02-04`), permitting Cross-Site WebSocket Hijacking (CSWSH) reconnaissance against the Hub relay endpoint.
- **1 Low:** Hub default configuration (`tlsMode=proxy`) binds cleartext HTTP across all network interfaces (`0.0.0.0:8443`) without enforcing `allowInsecure=true` (`SEC-D02-05`).

---

## 2. Checklist Item Evaluation

| # | Checklist Item | Evaluation | Summary / Finding ID |
|---|---|---|---|
| 1 | Supported TLS versions, cipher suite restrictions, TLS 1.3 / 1.2 downgrade protection | **Not vulnerable** | Restricted to `TLSv1.3` and `TLSv1.2` (`FlashTlsContextFactory.kt:37, 90`). Deprecated versions (SSLv3, TLS 1.0, TLS 1.1) are strictly rejected. Cipher suites rely on platform TLS provider baselines (Conscrypt on Android, SunJSSE on Desktop). |
| 2 | Certificate generation: self-signed X.509 validity periods, SANs, serial numbers, issuer/subject DNs | **Not vulnerable** | Generated via BouncyCastle (`FlashCertMaker.kt:31-75`). Uses EC P-256 with SHA256withECDSA, 128-bit random serials, 10-year validity, `CN=flash-<deviceIdHex>`, `isCA=false`, and SAN DNS/URI attributes. 24-hour backdating protects against minor initial clock skew. |
| 3 | Pre-handshake and pre-HELLO state: can an attacker send data before the TLS handshake completes; max pre-HELLO message sizes | **Not vulnerable** | TLS handshake must complete before any WebSocket or application data is read or processed. Handshake timeouts are bounded (`6_000` to `8_000` ms). Pre-HELLO WebSocket frame size is capped at 64 KiB (`PRE_HANDSHAKE_MAX_MESSAGE_BYTES`), preventing unauthenticated memory exhaustion. Non-HELLO early frames cause immediate connection abort. |
| 4 | Downgrade protection: can an attacker force fallback to plaintext, cleartext TCP, or an older protocol version | **Not vulnerable** | Protocol requires transport security (`requireTransportSecurity`); dial attempts without TLS fail immediately with `TransportSecurityUnavailableException` (`JvmWsFlashNetwork.kt:294`). Cleartext fallback is impossible in production. Handshake protocol version is strictly checked (`PROTOCOL_VERSION = 2`). |
| 5 | Pinning implementation: how are peer certs pinned, TOFU vs pre-shared, TOFU first-use confirmation, mismatch handling | **Vulnerable** (SEC-D02-01, SEC-D02-02) | Pinning is implemented in `TofuX509TrustManager` and `TofuPinVerifier`. Validating against `chain.any` allows chain-appending impersonation (SEC-D02-01). Inbound connections automatically record TOFU pins without user pairing or consent, enabling denial-of-service via pin poisoning (SEC-D02-02). |
| 6 | Cross-layer identity binding: is the TLS session cert cryptographically bound to the Flash device ID, pairing transcript, or group proof | **Vulnerable** (SEC-D02-01) | Handshake binds TLS fingerprint in `PairingV2.kt` (SAS derivation) and `GroupProof.kt` (HMAC transcript). However, `JvmWsFlashNetwork.kt:375-395` skips leaf binding when `expectedDeviceId` is set because `deferredPeerLeafFingerprintHex` is left null, delegating entirely to the flawed `chain.any` trust manager check. |
| 7 | Validity and clock skew: what happens with skewed system clocks, expired certs, or certs from the far future | **Not vulnerable** | P2P TLS trust managers deliberately bypass standard X.509 validity period checks, relying exclusively on cryptographic public key pinning; this prevents clock desynchronization on offline/air-gapped networks from bricking transfers. Hub certificates enforce 5-minute skew tolerance (`OrgPeerResolver.SKEW_TOLERANCE_MS`). |
| 8 | SNI / ALPN: are SNI or ALPN headers used or leaked; do they expose user identity on the wire | **Not vulnerable** | P2P TLS handshakes operate over raw IP addresses without configuring SNI (`SSLParameters.serverNames` is empty); no device IDs or user metadata are exposed in TLS ClientHello/ServerHello. Hub HTTPS connections use standard host SNI without custom identifiers. |
| 9 | Hub / relay transport: TLS termination at the hub, hub cert validation by clients, client certs for hub auth | **Vulnerable** (SEC-D02-03, SEC-D02-05) | Hub clients use Ktor CIO with system trust managers and no certificate/SPKI pinning; clients cannot safely validate self-signed hub certs and are vulnerable to corporate/mitm CAs (SEC-D02-03). Hub default `tlsMode=proxy` binds cleartext HTTP on `0.0.0.0:8443` without `allowInsecure=true` (SEC-D02-05). |
| 10 | WebSocket framing: maximum frame size, fragmentation attacks, masking on client frames, buffer reuse safety | **Not vulnerable** | Max message size strictly capped at 4 MiB (`MAX_MESSAGE_BYTES`), pre-handshake capped at 64 KiB. Client frames must be masked per RFC 6455. Android buffer pooling (`ByteArrayPool`) properly releases pooled arrays in `try/finally` blocks (audit finding F3 resolved). |

---

## 3. Detailed Audit Findings

### SEC-D02-01 — Trust Manager Verifies Fingerprint on Any Chain Element Rather than Leaf, Enabling Impersonation via Chain Appending
- **Severity:** Critical        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Active network attacker / MITM on LAN or Wi-Fi Direct)
- **Component:** 
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/tls/TofuX509TrustManager.kt:103-109`
  - `core/network/src/androidMain/kotlin/com/transfer/flash/core/network/tls/TofuX509TrustManager.kt:99-105`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt:375-395, 464-471`
- **CWE / standard:** CWE-295: Improper Certificate Validation; CWE-297: Improper Validation of Certificate with Host Mismatch; MASVS-AUTH-1; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H (8.8 High / Critical)
- **What is wrong:** In `TofuX509TrustManager.verify()`, when verifying a peer during a connection where `expectedDeviceId` is known (such as an outbound dial to a discovered or paired contact), the trust manager iterates over every certificate in the presented chain:
  ```kotlin
  val pinned = chain.any { cert ->
      val fp = fingerprintHexOrNull(cert)
      fp != null && pinVerifier.isPinned(deviceId, fp)
  }
  if (!pinned) {
      throw rejected(role, leafFp, "no chain certificate matches the pin for device '$deviceId'")
  }
  ```
  In TLS, the peer must prove possession of the private key corresponding to `chain[0]` (the leaf certificate) during the `CertificateVerify` handshake exchange. However, Flash uses self-signed peer certificates without Certificate Authority (CA) validation (`getAcceptedIssuers()` returns an empty array).
  
  Because `chain.any` is satisfied if *any* certificate in the chain matches the pin, an active network attacker A1 can generate their own ephemeral key pair and leaf certificate (`cert_attacker`), append the victim's genuine public certificate (`cert_victim`, which is publicly broadcast in mDNS or discoverable over the network) as `chain[1]`, and present `[cert_attacker, cert_victim]`.
  
  `TofuX509TrustManager` tests `cert_victim` against `pinVerifier.isPinned(deviceId, fp)`, which returns `true`. The handshake succeeds because the attacker proves possession of `cert_attacker`'s private key.
  
  Furthermore, when `expectedDeviceId` is supplied, `deferPinWhenDeviceIdUnknown` is `false`, so `onLeafObserved(leafFp)` is never called. Consequently, `deferredPeerLeafFingerprintHex` remains `null`. In `JvmWsFlashNetwork:375-395`, when `leaf == null`, the application performs no post-handshake leaf binding check, believing the trust manager already validated the leaf! The TLS session is established under the attacker's private key, granting the attacker full impersonation and decryption capabilities.
- **Impact:** An active attacker on the local network or Wi-Fi Direct path can completely impersonate any paired contact when the victim dials that contact. The attacker intercepts the TLS connection, establishes an encrypted session with their own keys, receives all sent files or messages, and can inject arbitrary payloads without triggering a pin mismatch error.
- **Evidence:**
  In [TofuX509TrustManager.kt (jvmMain)](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/tls/TofuX509TrustManager.kt#L103-L109):
  ```kotlin
  val pinned = chain.any { cert ->
      val fp = fingerprintHexOrNull(cert)
      fp != null && pinVerifier.isPinned(deviceId, fp)
  }
  if (!pinned) {
      throw rejected(role, leafFp, "no chain certificate matches the pin for device '$deviceId'")
  }
  ```
  In [JvmWsFlashNetwork.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt#L375-L382):
  ```kotlin
  val leaf = connection.deferredPeerLeafFingerprintHex
  if (leaf != null) {
      val expected = tls.expectedDeviceId
      if (expected != null && !tls.pinVerifier.isPinned(expected, leaf)) {
          connection.close("Pinned identity check failed")
          return@launch
      }
  }
  ```
- **Why existing guards do not stop it:** `TofuX509TrustManager` computes `leafFp = fingerprintHexOrNull(chain[0])` on line 91, but only uses it for exception messages; on lines 103-106, it evaluates `chain.any` instead of strictly checking `chain[0]`. Because Flash does not build or verify CA trust chains, intermediate/chained certificates must never be evaluated for identity pinning.
- **Verifier result:** survived
- **Recommended fix:** In `TofuX509TrustManager.kt`, strictly verify the leaf certificate (`chain[0]`) and reject any connection where `chain[0]` does not match the pinned fingerprint for `deviceId`:
  ```kotlin
  val isLeafPinned = pinVerifier.isPinned(deviceId, leafFp)
  if (!isLeafPinned) {
      throw rejected(role, leafFp, "leaf certificate does not match the pin for device '$deviceId'")
  }
  ```
  Additionally, disallow multi-certificate chains in P2P mode by enforcing `require(chain.size == 1)`.
- **Related:** ADR-040, ADR-045, `docs/protocol.md`.

---

### SEC-D02-02 — Unsolicited Inbound Connections Force Unverified TOFU Pin Persistence, Enabling Pre-Emptive Key Poisoning and DoS
- **Severity:** High        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Attacker on LAN or Wi-Fi Direct), **A4** (Unpaired malicious neighbor)
- **Component:**
  - `core/network/src/commonMain/kotlin/com/transfer/flash/core/network/tls/TofuPinVerifier.kt:47-51`
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt:461-474, 499-507`
  - `core/engine/src/androidMain/kotlin/com/transfer/flash/core/engine/Flash.kt:279`
  - `desktop/src/jvmMain/kotlin/com/transfer/flash/desktop/DesktopEngine.kt:1224`
- **CWE / standard:** CWE-322: Key Exchange without Entity Authentication; CWE-347: Improper Verification of Cryptographic Signature; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:N/I:H/A:H (8.1 High)
- **What is wrong:** For inbound connections, the server listener accepts connections without a pre-configured `expectedDeviceId` (`expectedDeviceId = null`, `deferPinWhenDeviceIdUnknown = true`). Once the TLS handshake finishes, the inbound peer sends a `HELLO` message declaring its `claimedDeviceId` (`JvmWsFlashNetwork.kt:498`).
  
  To validate the inbound peer, `inboundIdentityFailure` calls:
  `tls.pinVerifier.isPinned(claimedDeviceId, leaf)`
  
  In both Android (`Flash.kt:279`) and Desktop (`DesktopEngine.kt:1224`), `tls.pinVerifier` is constructed as:
  ```kotlin
  TofuPinVerifier(
      lookupPin = { id -> trustStore.get(id)?.pinnedFingerprintHex },
      recordPin = { id, fp -> trustStore.trust(id, fp) },
      ownFingerprintHex = ownFingerprintHex,
  )
  ```
  Inside `TofuPinVerifier.isPinned()`:
  ```kotlin
  val known = lookupPin(deviceId)?.let { FlashPinVerifier.normalize(it) } ...
  return if (known.isNullOrBlank()) {
      // First connect: trust on first use and record the pin
      recordPin?.invoke(deviceId, presented)
      true
  } else {
      constantTimeEquals(known, presented)
  }
  ```
  Because `lookupPin(claimedDeviceId)` is `null` for any device not yet paired or contacted, `recordPin` is **immediately invoked**, writing the attacker's presented leaf certificate fingerprint directly into the persistent `trustStore`!
  
  This occurs **before** any user pairing dialog, consent prompt, out-of-band verification, or registration completes. Flash device IDs are 16-character hex strings broadcast in cleartext over mDNS/NSD service announcements and Wi-Fi Direct device names. An attacker on the local network can observe a victim's device ID, connect to the victim's open listening port (45820-45829), present an arbitrary ephemeral certificate, and send `HELLO` claiming any target peer ID. Flash permanently records the attacker's key for that target ID.
- **Impact:**
  1. **Denial of Service:** When the legitimate peer later attempts to connect or be contacted, `lookupPin` returns the attacker's poisoned pin. The legitimate connection is immediately rejected with a hard `KEY_CHANGED` / `Mismatch` security exception.
  2. **Irreversible Lockout:** Flash's trust store provides no user-facing UI to unpin or delete unpaired TOFU pins without wiping the entire application database.
  3. **Pairing Interception:** An attacker can pre-seed their own key for an intended contact before pairing occurs.
- **Evidence:**
  In [TofuPinVerifier.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/commonMain/kotlin/com/transfer/flash/core/network/tls/TofuPinVerifier.kt#L47-L51):
  ```kotlin
  return if (known.isNullOrBlank()) {
      // First connect: trust on first use and record the pin
      recordPin?.invoke(deviceId, presented)
      true
  } else {
      constantTimeEquals(known, presented)
  }
  ```
  In [JvmWsFlashNetwork.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/JvmWsFlashNetwork.kt#L472-L474):
  ```kotlin
  return if (tls.pinVerifier.isPinned(claimedDeviceId, leaf)) null
  else "client certificate does not match the pin for the claimed id"
  ```
- **Why existing guards do not stop it:** The post-handshake check in `inboundIdentityFailure` was introduced to prevent an unauthenticated peer from claiming a *known* contact's ID. However, because it relies on `TofuPinVerifier.isPinned()`, which has side-effecting `recordPin` behavior on first use, unsolicited inbound connections weaponize the check to poison unknown IDs.
- **Verifier result:** survived
- **Recommended fix:** Separate *pin verification* from *pin recording*. For inbound connections, unknown peers must not be automatically recorded into persistent trust storage. Instead, inbound connections from unpinned devices must operate in an uncommitted/tentative state until explicit user confirmation or successful Pairing v2 / Group Proof completion occurs.
- **Related:** ADR-040, ADR-045, ADR-085.

---

### SEC-D02-03 — Missing TLS Certificate Pinning on Client-to-Hub Transport Enables MITM by Local/Corporate CAs
- **Severity:** Medium        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Network attacker with rogue CA), **A2** (Compromised / coercive network operator, corporate proxy)
- **Component:**
  - `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseEnrollmentClient.kt:151-157`
  - `core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/KtorRelayTransport.kt:336-340`
  - `server/src/main/kotlin/com/transfer/flash/server/config/TlsSupport.kt:29-33`
- **CWE / standard:** CWE-295: Improper Certificate Validation; MASVS-NETWORK-2; CVSS:3.1/AV:N/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:N (6.8 Medium)
- **What is wrong:** When connecting to an Enterprise Hub for device enrollment, directory lookups, mailbox fetching, and relay forwarding, the client instantiates Ktor `HttpClient(CIO)` without configuring custom certificate pins or public key pinning:
  ```kotlin
  public fun createDefaultClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) { ... }
  }
  ```
  The Ktor CIO engine relies entirely on the platform's default X.509 TrustManager (system CA root store).
  
  Furthermore, `server` provides a built-in `tlsMode=self` mode where it generates a self-signed P-256 certificate and logs its SHA-256 fingerprint:
  `The certificate's SHA-256 [fingerprint] is logged at start-up so a client can pin it.` (`TlsSupport.kt:31-33`).
  However, no client-side pinning mechanism exists in `EnterpriseEnrollmentClient` or `KtorRelayTransport`. Clients connecting to a self-signed hub immediately fail with `PKIX path building failed`, while clients connecting to a hub backed by a public/enterprise CA will implicitly trust any certificate issued by any root CA in the device trust store.
- **Impact:** Any adversary in possession of a trusted CA certificate (e.g. corporate TLS inspection proxies, malicious root certificates installed on managed devices, compromised public CAs) can transparently intercept, decrypt, and modify all client-hub communications. This exposes:
  - 15-minute Bearer auth tokens exchanged during `/auth/challenge` and `/auth/verify`.
  - Organization member directories and relay metadata.
  - Device enrollment requests and invites.
- **Evidence:**
  In [EnterpriseEnrollmentClient.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/EnterpriseEnrollmentClient.kt#L151-L157):
  ```kotlin
  public fun createDefaultClient(): HttpClient = HttpClient(CIO) {
      install(HttpTimeout) {
          connectTimeoutMillis = 10_000L
          requestTimeoutMillis = 20_000L
          socketTimeoutMillis = 20_000L
      }
  }
  ```
  In [KtorRelayTransport.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/enterprise/src/commonMain/kotlin/com/transfer/flash/core/enterprise/relay/KtorRelayTransport.kt#L336-L340):
  ```kotlin
  public fun createDefaultHttpClient(): HttpClient = HttpClient(CIO) {
      install(WebSockets) {
          maxFrameSize = HubContract.MAX_FRAME_BYTES.toLong()
      }
  }
  ```
- **Why existing guards do not stop it:** End-to-end payload encryption protects message contents between paired peers, but hub authentication tokens, mailbox envelopes, online presence, and enrollment credentials rely directly on transport-layer TLS. Without certificate or SPKI pinning, transport security is subordinate to the integrity of the entire platform CA store.
- **Verifier result:** survived
- **Recommended fix:** 
  1. Add an optional `expectedHubFingerprintHex` parameter to `EnterpriseHub` configuration and enrollment invites.
  2. Configure Ktor CIO's `https` block with a custom `X509TrustManager` that enforces SPKI SHA-256 fingerprint matching against the configured hub fingerprint.
- **Related:** SEC-06, ADR-222, `docs/deploy/HUB-SERVER-DEPLOYMENT.md`.

---

### SEC-D02-04 — Absence of WebSocket `Origin` Header Validation on Peer and Hub Listeners
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A3** (Malicious website visited in a local browser / CSWSH attacker)
- **Component:**
  - `core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt:170-176`
  - `server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt:567-570`
- **CWE / standard:** CWE-346: Origin Validation Error; CWE-1385: Missing Origin Validation in WebSockets; CVSS:3.1/AV:N/AC:H/PR:N/UI:R/S:U/C:L/I:L/A:N (4.2 Medium / Low)
- **What is wrong:** RFC 6455 requires WebSocket servers accessible to web browsers to validate the `Origin` header during the HTTP upgrade handshake to prevent Cross-Site WebSocket Hijacking (CSWSH).
  
  In `WsTransferServer.handshake()`, only the HTTP verb, `Upgrade`, and `Sec-WebSocket-Key` headers are parsed:
  ```kotlin
  val upgrade = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true
  val key = headers["sec-websocket-key"]
  if (!requestLine.startsWith("GET ") || !upgrade || key.isNullOrBlank()) {
      throw IOException("Not a WebSocket upgrade request")
  }
  ```
  In `RelayFrameRouter.kt`, Ktor's `route.webSocket(HubContract.PATH_RELAY)` is installed without origin restrictions or CORS rules.
  
  While P2P endpoints require client-side TLS certificates (which standard web browsers do not supply), Hub WebSocket endpoints (`wss://<hub>:8443/flash-relay`) terminate TLS with standard one-way server authentication. Any website visited by a user can initiate a WebSocket connection to the hub relay from the browser context; the hub accepts the WebSocket upgrade regardless of the `Origin` header.
- **Impact:** An attacker hosting a malicious web page can initiate WebSocket connections to Flash Hub instances, probe internal hub network reachability, and consume connection slots. While the malicious page cannot complete application-layer frame authentication without possessing the device's private key, accepting unvalidated origins violates defense-in-depth and exposes internal endpoints to browser-driven reconnaissance.
- **Evidence:**
  In [WsTransferServer.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/core/network/src/jvmMain/kotlin/com/transfer/flash/core/network/ws/WsTransferServer.kt#L170-L176):
  ```kotlin
  val (requestLine, headers) = WebSocketCodec.parseHeaders(WebSocketCodec.readHttpHeaderBlock(input))
  val upgrade = headers["upgrade"]?.contains("websocket", ignoreCase = true) == true
  val key = headers["sec-websocket-key"]
  if (!requestLine.startsWith("GET ") || !upgrade || key.isNullOrBlank()) {
      throw IOException("Not a WebSocket upgrade request")
  }
  ```
  In [RelayFrameRouter.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/server/src/main/kotlin/com/transfer/flash/server/relay/RelayFrameRouter.kt#L567-L570):
  ```kotlin
  public fun registerRoute(route: Route) {
      route.webSocket(HubContract.PATH_RELAY) {
          handleSession(this, call)
      }
  }
  ```
- **Why existing guards do not stop it:** Ktor does not validate the `Origin` header by default for WebSocket routes. No filter or interceptor rejects requests where `Origin` is present and untrusted.
- **Verifier result:** survived
- **Recommended fix:** Reject incoming WebSocket upgrade requests if an `Origin` header is present and does not match the configured external domain, or reject any browser origin on `/flash-relay` since Flash clients are non-browser native applications.
- **Related:** RFC 6455 §10.2.

---

### SEC-D02-05 — Hub Default `tlsMode=proxy` Binds Cleartext Port on `0.0.0.0` Without Insecure Warning Guard
- **Severity:** Low        **Confidence:** CONFIRMED
- **Attacker:** **A1** (Network attacker on local network or adjacent interface)
- **Component:**
  - `server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt:39-50, 94-96`
- **CWE / standard:** CWE-319: Cleartext Transmission of Sensitive Information; CWE-1188: Insecure Default Initialization of Resource; CVSS:3.1/AV:A/AC:L/PR:N/UI:N/S:U/C:L/I:N/A:N (4.3 Medium / Low)
- **What is wrong:** In `ServerConfig.kt`:
  ```kotlin
  val port: Int = DEFAULT_PORT, // 8443
  val host: String = DEFAULT_HOST, // 0.0.0.0
  val tlsMode: String = DEFAULT_TLS_MODE, // "proxy"
  ```
  The validation rules enforce:
  ```kotlin
  require(tlsMode != TLS_NONE || allowInsecure) {
      "tlsMode 'none' sends everything in clear text. Set FLASH_ALLOW_INSECURE=true to run it anyway..."
  }
  ```
  When the server runs with default options, `tlsMode` is `"proxy"`. Under `tlsMode="proxy"`, the Ktor server binds directly to HTTP (cleartext) on port 8443, expecting an external reverse proxy (e.g. Nginx, Caddy) to handle TLS termination.
  
  However:
  1. The server binds by default to `0.0.0.0` (all network interfaces), rather than `127.0.0.1` (localhost).
  2. Because `tlsMode` is `"proxy"` rather than `"none"`, the `allowInsecure` check is bypassed.
  If an administrator starts the hub directly or deploys it in a container without a co-located reverse proxy, the server immediately exposes unencrypted REST and WebSocket endpoints directly on `0.0.0.0:8443`.
- **Impact:** An operator who starts the hub without a reverse proxy inadvertently exposes cleartext communications on all network interfaces. Network observers can capture authorization tokens and metadata without triggering the server's cleartext warning guard.
- **Evidence:**
  In [ServerConfig.kt](file:///C:/Users/KaliOxygen/Downloads/Flash/server/src/main/kotlin/com/transfer/flash/server/config/ServerConfig.kt#L39-L43):
  ```kotlin
  val port: Int = DEFAULT_PORT,
  val adminPort: Int = DEFAULT_ADMIN_PORT,
  val host: String = DEFAULT_HOST,
  val dataDir: String = DEFAULT_DATA_DIR,
  val tlsMode: String = DEFAULT_TLS_MODE,
  ```
  Lines 94-96:
  ```kotlin
  require(tlsMode != TLS_NONE || allowInsecure) {
      "tlsMode 'none' sends everything in clear text. Set FLASH_ALLOW_INSECURE=true to run it anyway (development only)."
  }
  ```
- **Why existing guards do not stop it:** The validation check only checks for `tlsMode == TLS_NONE`. It assumes `tlsMode == "proxy"` is always safely sequestered behind a loopback-bound reverse proxy, but does not enforce that `host == "127.0.0.1"` or that trusted proxy headers are present.
- **Verifier result:** survived
- **Recommended fix:** When `tlsMode == "proxy"`, either enforce that `host` is a loopback address (`127.0.0.1` / `::1`), or require `allowInsecure=true` if binding to wildcard `0.0.0.0`.
- **Related:** `docs/deploy/HUB-SERVER-DEPLOYMENT.md`, ADR-222.
