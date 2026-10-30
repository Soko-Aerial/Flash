# Flash System Map — Security Architecture & Attack Surfaces

**Date:** 2026-10-22  
**Commit:** `75eda19b018948c00947bc08d7288c2d3a0ae6ce`  
**Working tree:** clean  

---

## 1. Modules and Dependencies

| Module | Purpose & Scope | Key Technologies / Security Relevance |
|---|---|---|
| `:app` | Android application shell | AndroidKeyStore, AndroidManifest, ForegroundTransferService, DaemonProbeService, FileProvider, SAF |
| `:desktop` | Compose Desktop application shell | Single-instance lock, DPAPI (Windows) / Keyring (Linux), `~/.flash` storage, tray |
| `:server` | Enterprise Hub daemon | Ktor / Netty, SQLite (WAL, transactions), REST API, Relay WebSocket wire v2, Admin Console & API, Step-up Auth, Audit Logger |
| `:core:common` | Foundation utilities | Dispatchers, FlashResult, Clock, Byte buffers |
| `:core:security` | Cryptographic primitives | FlashCrypto, FlashFingerprint, TofuPinVerifier, KeyFiles, SecretBox, FlashTrustStore, Pairing v2 |
| `:core:hub-protocol` | Wire contract for Hub | DTOs, X.509 cert chains, RevocationList v3, Relay frames, Hub file tickets (Pure KMP) |
| `:core:discovery` | LAN & Local Discovery | Android NSD, JmDNS (Desktop), Multicast beacon (`MulticastTransport`), Remembered routes |
| `:core:network` | Transport layer | WebSocket client/server (`WsTransferServer`, `WsTransferClient`, `WsFlashNetwork`), TLS 1.3, Frame codec, ByteArrayPool |
| `:core:enterprise` | Client Hub Integration | EnterpriseHub, HubSession, HubChatBridge, HubFileTransfers, HubFileSealing, OrgTrustGate, OrgPeerResolver |
| `:core:persistence` | Client Storage | Room DB, SQLCipher, Key wrapping, Room migrations |
| `:core:transfer` | 1:1 File Transfer | Chunk streaming, resume engine, DiskFileSink, FlashPathSanitizer, FSEC encryption |
| `:core:messaging` | Messaging & Groups | 1:1 chat, Groups (v1 legacy & v2 signed), group secrets & rotation, PeerTrustPolicy, RealFlashChatRepository |
| `:core:calling` | Calling & WebRTC | Signaling state machine, CallCoordinator, PeerTrustPolicy.mayRing |
| `:core:ptt` | Push-to-Talk | Opus audio, floor control, strictly PAIRED capability gate |
| `:core:radio` | Radio / Bluetooth Serial | RFCOMM (Android / Win32), TNC packet framing, RadioGatewayManager |
| `:core:swarm` | Swarm File Transfer | FSW1 protocol, Merkle piece hashing, manifest validation, GroupGate |
| `:core:engine` | System Facade | DesktopEngine, AndroidEngine / Flash.kt, lifecycle wiring |
| `:ui:platform-shims`| Platform Seams | Shims for clipboard, file picker, audio, notifications |
| `:ui:chat` / `:ui:callui` | User Interface | Compose UI, Hub UI, message bubbles, transfer UI |
| `third_party/webrtc-kmp` | Vendored WebRTC | Upstream fork for KMP WebRTC calling |

---

## 2. Listeners and Network Entry Points

| Host / Target | Component / Listener | Transport / Port | TLS / Auth Requirements | Reached by Attacker |
|---|---|---|---|---|
| Client (Android & Desktop) | `WsTransferServer` (`:core:network`) | TCP/WebSocket `0.0.0.0:45822` (or dynamic fallback) | TLS 1.3 self-signed EC P-256 cert, TOFU / Pairing pin verification | **A1** (LAN unauthenticated), **A2** (Paired) |
| Client (Android & Desktop) | `LanProbeServer` (`:core:network`) | TCP ServerSocket (advertised port) | Raw TCP ping/probe | **A1** (LAN unauthenticated) |
| Client (Android & Desktop) | `MulticastTransport` (`:core:discovery`) | UDP Multicast `239.255.45.22:45824` | Unauthenticated plaintext datagrams | **A1** (LAN unauthenticated) |
| Client (Android) | NSD / mDNS daemon | UDP `5353` | mDNS unauthenticated service announcements | **A1** (LAN unauthenticated) |
| Client (Desktop) | JmDNS (`:core:discovery`) | UDP `5353` | mDNS unauthenticated service announcements | **A1** (LAN unauthenticated) |
| Client (Android / Win32) | Bluetooth RFCOMM (`:core:radio`) | Insecure RFCOMM socket / Serial COM | Insecure RFCOMM channel (no Bluetooth PIN/auth) | **A1** (Nearby RF/Bluetooth) |
| Client (Android Debug) | `DaemonProbeService` (`:app`) | TCP `127.0.0.1:ephemeral` | Loopback only (debug build only) | **A6** (Local app on device) |
| Hub Server (`:server`) | Public Port (`FlashServerEngine`) | TCP `0.0.0.0:8443` (configurable) | TLS (`self` or `proxy`). Serves REST `/api/v1/*`, `/relay/v2`, `/enroll/*`, `/health` | **A3** (Org member), **A4** (Hub op), **A5** (Internet) |
| Hub Server (`:server`) | Admin / Management Port (`FlashServerEngine`) | TCP `127.0.0.1:8444` (configurable) | Loopback by default. Serves `/admin/*`, `/admin/api/v1/*`, `/health/detail`, `/metrics` | **A8** (Admin session thief), **A9** (Scoped admin) |

---

## 3. Parsers and Codecs (Untrusted Data Ingestion)

| Component | Codec / Parser | Wire Format | Critical Security Checks |
|---|---|---|---|
| Peer WebSocket | `WebSocketCodec` (`:core:network`) | Binary WS framing, leased byte buffers | Buffer pool bounds, frame length, opcode validation |
| Peer File Chunks | `SecureBinaryFrameCodec` (`:core:transfer`) | `FSEC` binary envelope (AES-256-GCM) | Magic `0x46534543`, version 2, 12-byte nonce, 16-byte tag, session key |
| Peer File Chunks | `ChunkEnvelope` (`:core:transfer`) | Binary chunk framing | Transfer ID, chunk index, chunk size, offset |
| Group Frames | Group frame codecs (`:core:messaging`) | JSON / binary frames `Gs*` | Roster signature, monotonic version, origin author binding |
| Swarm Frames | `SwarmFrameCodec` (`:core:swarm`) | `FSW1` binary frames | Merkle root, piece index, bitfield bounds, origin signature |
| Hub Relay Wire | `RelayFrameCodec` (`:core:hub-protocol`) | JSON WebSocket frames (`RelayFrame`) | Sender spoof prevention, frame type validation, payload size limit |
| Hub REST API | Kotlinx Serialization (`:server`) | JSON DTOs | Request body limit (64 KiB default, 2 MiB directory sync), field types |
| Hub File Chunks | `FileRoutes` (`:server`) | Raw binary chunks (`PUT /chunks/{i}`) | Range check `0 <= index < chunkCount`, chunk SHA-256 verification |
| Hub File Ticket | `HubFileFrameCodec` (`:core:hub-protocol`) | JSON envelope | 32-hex `fileId`, AES-GCM encrypted chunks, AAD binding |
| Radio Gateway | `RadioFrameCodec` (`:core:radio`) | KISS framing / APRS text packets | Buffer bounds, command escaping, callsign parsing |
| Deep Links | Deep link parser (`:core:messaging`, `:core:enterprise`) | `flash://g/1/...`, `flash://hub/enroll?...` | Token entropy, parameter sanitization, scheme validation |
| Directory Cursors | `DirectoryCursorCodec` (`:server`, `:core:enterprise`) | Length-prefixed string `v1|<len>:<name>|<deviceId>` | UTF-8 explicit, delimiter injection resistance |

---

## 4. Cryptographic Call Sites and Key Storage

| Purpose | Algorithm / Primitive | Implementation | Storage / Location |
|---|---|---|---|
| Device Identity Key | ECDSA P-256 (secp256r1), SHA-256 | AndroidKeyStore (API 28+ StrongBox, TEE fallback); Desktop: software key wrapped via DPAPI/Keyring | `AndroidKeyStore:flash_identity`; Desktop: `~/.flash/identity.key` |
| TLS Identity Certificate | Self-signed X.509 EC P-256 | Android: KeyStore self-signed; Desktop: BouncyCastle `FlashCertMaker` | In-memory PKCS#12 Keystore |
| Ephemeral Key Agreement | ECDH P-256 | JCA `KeyAgreement` ("ECDH") | In-memory ephemeral during Pairing v2 |
| Pairwise Session Keys | HKDF-SHA256 (`info="flash-e2e-v2"`) | JCA `Mac` ("HmacSHA256") | Stored in memory / `RoomTrustedStore` session cache |
| Pairwise Chunk Encryption | AES-256-GCM (`AAD="flash-chunk-v2"`) | JCA `Cipher` ("AES/GCM/NoPadding") | 12-byte random IV per chunk, session key |
| Local DB Encryption | SQLCipher / Room | AES-256-CBC with HMAC | Passphrase wrapped in AndroidKeyStore / Desktop DPAPI |
| Hub Organisation Root | ECDSA P-256 | `server keytool init-root` | Offline encrypted file (passphrase protected) |
| Hub Delegate Key | ECDSA P-256 | Root-delegated certificate | `data/server-identity.pk8`, owner-only ACL |
| Hub Data Master Key | 256-bit AES (`data.key`) | `SecretBox` (AES-256-GCM) | `data/data.key`, owner-only ACL |
| Hub Sealed Mailbox | AES-256-GCM | Encrypted under `data.key`, AAD binds `origin:target:msgId` | SQLite `mailbox` table |
| Hub Admin Password | PBKDF2-HMAC-SHA256 | 600,000 iterations, 16-byte salt | SQLite `admin_users` table |
| Hub TOTP Seeds | HMAC-SHA1 | Sealed with `data.key` (AES-256-GCM) | SQLite `admin_users.totp_seed_encrypted` |
| Hub Admin Step-Up Key | ECDSA P-256 | WebCrypto API (`extractable: false`) | Browser IndexedDB |
| Hub Sealing Key | ECDSA P-256 | Client software key, bound by Identity Key signature | Android KeyStore wrapped; Desktop DPAPI wrapped |
| Group Secrets | 256-bit random, HKDF-SHA256 | `GroupCrypto` | Room DB encrypted |

---

## 5. File and Database Writers

| Component | Target File / Database | Format / Permissions | Risks / Guards |
|---|---|---|---|
| Android Persistence | `/data/data/com.transfer.flash/databases/flash.db` | Room SQLite (SQLCipher encrypted) | Keystore passphrase wrapping, backup exclusion |
| Desktop Persistence | `~/.flash/database.db` / `settings.properties` | SQLite / Properties file | Windows DPAPI passphrase wrapping; Linux SecretService |
| Transfer Sink | Downloads / SAF target directory | Arbitrary binary files | `FlashPathSanitizer` (traversal `..`, NTFS streams, device names), `ReceivedFilesManifest` |
| Hub Database | `data/flash-hub.db` | SQLite 3 WAL mode | Posix 0600 / Windows ACL; transactions with `BEGIN IMMEDIATE` |
| Hub File Storage | `data/files/<fileId>/<index>.chunk` | Sealed binary chunks | 32-hex `fileId` path validation; sweepers for TTL and quota |
| Hub Keys & Tokens | `data/data.key`, `data/setup-token.txt` | Raw keys / tokens | Owner-only file modes; setup token deleted after onboarding |
| Evidence Probes & Logs | `logs/`, logcat, stdout | Text log lines | Secret redaction filters (`LogHygieneTest`, short IDs) |

---

## 6. Trust Boundaries and Gate Decisions

| Gate / Decision Point | Inputs | Trust Evaluation | Capabilities Granted / Denied |
|---|---|---|---|
| `PeerTrustPolicy` (`:core:messaging`) | `isPaired`, `isOrg` | Strict capability truth table | 1:1 chat/reaction/ringing allowed for ORG; PTT, group create/admin, swarm serve strictly require PAIRED |
| `TofuPinVerifier` (`:core:security`) | Presented cert SPKI fingerprint, `FlashTrustStore` | Pin lookup: empty => TOFU FirstConnect; match => OK; mismatch => fail-closed SSLException | Transport TLS connection establishment |
| `OrgTrustGate` / `OrgPeerResolver` (`:core:enterprise`) | Snapshot, Member statement, TLS leaf SPKI | Pinned org root chain, statement expiry, clock rollback check, SPKI match | Attestation as Tier 2 Colleague; allows 1:1 chat without pairing |
| `GroupGate` (`:core:messaging`, `:core:swarm`) | `groupId`, `peerDeviceId`, charter, rosters | Owner signature, member cert, live TLS key match | Group chat, call participation, swarm piece serving |
| `HubApiGate` / `BearerTokenStore` (`:server`) | Bearer token (32 random bytes) | Token SHA-256 hash lookup in memory/DB, device revocation check | Hub REST API route access; principal role/department derived |
| `RelayFrameRouter` (`:server`) | Relay handshake, challenge proof | Device cert chain to root, proof signature over server nonce | Relay WebSocket admission, mailbox delivery and queuing |
| `AdminGate` (`:server`) | `__Host-` session cookie, CSRF header, Step-up signature | Session validity (idle/absolute), CSRF token match, WebCrypto ECDSA signature verification | Admin API mutating operations; approver phone co-signing |
