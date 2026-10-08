# Enterprise Hybrid Plan — organisation realm, directory, relay hub and bridges

**Date:** 2026-10-07
**Status:** PROPOSED, DESIGN ONLY. No code, no ADR accepted, nothing device-verified. Several decisions below belong to the owner (section 10).
**Supersedes (in part, nothing deleted per AGENTS.md section 27):**
- [`HYBRID-ENTERPRISE-SERVER-RELAY-PLAN.md`](HYBRID-ENTERPRISE-SERVER-RELAY-PLAN.md) — the server-centred draft. Its corrected facts (H1-H15) remain valid; where it disagrees with this plan, this plan wins.
- `docs/FUTURE-OPTIMIZATION.md` **FO-09** (organisation realms) — this plan is its staged design (FO-09's R0-R3 become E0-E3 here).
- Absorbs FO-06 option 1 (relay through the hotspot phone) as one *bridge* (E5).

**Related:** [`network/BLUETOOTH-AND-RADIO-TNC-PLAN.md`](network/BLUETOOTH-AND-RADIO-TNC-PLAN.md) (radio and Bluetooth bridges), [`LINUX-PORT-PLAN.md`](LINUX-PORT-PLAN.md) (Linux clients and the Linux server host), ADR-035, ADR-042, ADR-044, ADR-056, ADR-057, ADR-070..076 (group secret, swarm), `docs/security.md` sections 4, 6, 10.

**Deployment context stated by the owner (2026-10-07):** the software is for the **Ghana military**. Everything here therefore assumes a closed, administered fleet that may have **no internet at all**, so the design must work *without* the hub first and treat the hub as an optional accelerator.

---

## 1. Why one plan

The two source plans answered the same question from two directions: *how does an organisation's set of devices find, trust and talk to each other, and who administers them?*

| Concern | FO-09 (serverless realm) | Hybrid server draft | Decision in this plan |
|---|---|---|---|
| Who is "inside" | Realm secret proof, bound to the TLS session | Server enrolment token / SSO | **Realm secret + admin-signed device certs** (E1, E2). Tokens/SSO only as an optional hub login. |
| Enrol / revoke one device | R2: admin key signs device certs | Server registers devices and signs group certs | **Admin key signs; the hub never issues trust** (H7). |
| Directory across subnets | R3: unicast hints or a directory | The server *is* the directory | **Admin-signed roster** that any node can carry; the hub is one carrier (E3). |
| Auto-trust for members | Open question (needs ADR) | Assumed "no prompts" | **Owner decision D-E1.** Recommended: a new, scoped `REALM` trust level, not silent full pairing. |
| Relay / mailbox | none | Required hub | **Optional** hub that stores and forwards ciphertext (E4). |
| Other relays | FO-06 hotspot relay | none | Same envelope and routing (E5). |
| Radio / Bluetooth | none | none | Same envelope, different transport class (E5). |

**Invariants (unchanged from the project):** local LAN stays the primary path; the hub is never required to chat inside one network; a client build contains zero server code; no unauthenticated writes; no secrets in logs (AGENTS.md sections 19, 24).

---

## 2. Verified building blocks (so nothing is invented)

| Block | Where | Use here |
|---|---|---|
| Identity = ECDSA P-256, `SHA256withECDSA`; TLS self-signed X.509 over EC P-256 | `FlashCrypto.kt:73-74`, `FlashCertMaker.kt` | Device identity, enrolment certs, challenge signatures |
| `MemberCert` | `core/messaging/.../protocol/MemberCert.kt` | Template for a *device cert* signed by the realm admin |
| `GroupSecret`, `GroupProof` (TLS-session-bound mutual proof), `GroupProofSessions`, feature token `gs1` | `core/security/.../group/`, `core/messaging/.../group/` | Realm-secret proof (E1) with a new `rm1` token |
| `PinSource` / vouch rules | `core/security/.../trust/VouchRules.kt` | Where a `REALM` pin source would be added |
| Pairing-derived pairwise key (`FSEC`, `E2eFrameCodec`), HKDF-SHA-256 + AES-256-GCM | `docs/security.md` sections 4, 6 | Pairwise confidentiality; **only for peers that paired** |
| `FlashTransportType` = `LAN, WIFI_DIRECT, WEBSOCKET, RELAY, MESH, UNKNOWN` | `core/common/.../FlashTransportType.kt` | Gains `BLUETOOTH`, `RADIO`; `RELAY`/`MESH` already rank 3 in `SessionHardeningPolicy` ("post-v1 paths") |
| Discovery identifiers are constants: `_flash-transfer._tcp`, multicast `224.0.0.168`, TXT keys, `PROTOCOL_VERSION=1`; WS port 45822, probe 45821 | `JmdnsTransport.DEFAULT_SERVICE_TYPE`, `TxtCodec`, `WsTransferServer.PREFERRED_PORT` | E0 makes them configurable per realm |
| Durable outbox with dedup by message id | `RealFlashChatRepository` | Reused for every transport (no second queue) |
| Android 15 `dataSync` limit 6 h / 24 h | [Android docs](https://developer.android.com/develop/background-work/services/fg-service-timeout) | Hub connection is best-effort on phones (H9) |

**Not present:** any server, relay client, realm code, `REALM` pin source, `BLUETOOTH`/`RADIO` transport types, sealed-envelope crypto.

---

## 3. Stages (each independently useful; each stops at its own exit test)

```text
E0 configurable discovery ─► E1 realm gate ─► E2 admin-signed enrolment + revocation ─► E3 signed roster / directory
                                                                                              │
                                              ┌───────────────────────────────────────────────┤
                                              ▼                                               ▼
                                  E4 relay + mailbox hub (optional)                  E5 bridges (hotspot relay, Bluetooth, radio)
                                              └──────────── share the SAME signed envelope (section 4) ────────────┘
```

### E0 — Configurable discovery identifiers (FO-09 R0)
- Service type, multicast group/port, TXT prefix and WS port become runtime configuration with today's values as defaults.
- Purpose: stop accidental mixing between organisations. **Not security** (anyone with the APK can read the constants; FO-09 meaning 1).
- Exit: two realms on one LAN never list each other; default install unchanged (regression test on the existing discovery tests).

### E1 — Realm gate (FO-09 R1)
- A realm secret (256 random bits, set by managed config, QR, or admin tool; never typed) is proven by the existing `GroupProof` machinery, bound to the live TLS session fingerprints, before any pairing or chat frame.
- Feature token `rm1` in the HELLO so old builds are refused cleanly. **Strict mode** refuses everyone else, including inbound dials.
- Discovery advertises only a hashed, **rotating** realm tag (a static tag would be trackable; compare FO-02/audit S9).
- Residual: a shared secret cannot revoke one lost device (that is E2), and a leaked secret admits its holder until rotated.
- Exit: strict-mode device ignores a stranger on the same LAN, unit-tested with the sans-IO proof sessions; `ENT-01`.

### E2 — Admin-signed enrolment and revocation (FO-09 R2) — **required before any military use**
- A **realm admin key** (ECDSA P-256) signs a *device cert* binding `(deviceId, identity public key, role, validFrom/To, serial)`. Modelled on `MemberCert`.
- Admin key custody is the whole security story: keep it **offline** (a dedicated laptop or hardware token), never on the hub. Decision D-E2.
- **Revocation list** signed by the admin, versioned and monotonic, spread by gossip between devices on every session and, when present, via the hub. A revoked device is refused at the handshake and its sessions are dropped. Offline devices learn at next contact (eventual consistency, stated honestly, same class as ADR-044 removal).
- Lost device: admin signs a revocation; a stolen device that never meets another node stays usable locally until it does. Document this limit.
- What a valid device cert grants is **D-E1** (below). The default must not be "silent full pairing".
- Exit: enrol, revoke, and a revoked device refused on reconnect; `ENT-02`, `ENT-03`.

### E2b — Delegated limited keys and the enrolment link (owner decision D-E8, 2026-10-08)
Added because an offline root key alone makes the admin a single point of failure: nobody can join or be removed while the key holder is away (the same weakness as the v2 group owner, see `docs/audit/2026-10-01-chat-edge-case-audit.md`). Existing members never needed the admin online (they verify certs and the cached roster against the root *public* key); only **enrol** and **revoke** did.

- **Root key (offline, rare):** signs delegations, adds or revokes admins, rotates the root. Revoking or adding an **admin** needs the root, or **2 of N admins** (threshold), so one stolen admin device cannot remove the others.
- **Delegation cert:** the root signs `(delegateKey, scope, validFrom/To, maxDevices, serial)`. Scope examples: `enrol:member`, `revoke:member`. It may **not** enrol admins, grant `REALM` roles above member, or sign delegations. Short expiry (renewed by the root); revocable by the root list.
- **Server holds a delegation, never the root.** A compromised server can enrol and revoke members within its quota until the delegation expires or is revoked; it cannot create an admin or change the root. This **amends H7**: the hub may issue *limited* trust, never admin-level trust.
- **Enrolment link / QR:** `flash://enroll?server=<url>&token=<single-use, minutes>&root=<root key fingerprint>`. Each organisation hosts its own server; **Flash ships no default server, key, token or trust anchor** (the repository is public; security must not depend on the source being secret). The phone pins the root fingerprint from the link on first use (no TOFU on whoever answers), proves possession of its own identity key, and receives a device cert signed by the delegated key together with the delegation chain.
- **Server API rule:** the only unauthenticated endpoint is enrolment, and it needs a valid token plus rate limits. Everything else requires a device cert chained to the root. Authority comes from the cert, not from "being the Flash app" (anyone can build a client).
- **Revocation freshness:** device certs carry a short life (default proposal 7 days, **D-E9**) and renew from the server or any node holding a fresh renewal. A revoked device fails to renew, which bounds the offline window. Cost: a unit with no contact longer than the lifetime locks itself out, so the value is per-organisation.
- **Server is never required for chat.** Devices keep working on LAN with cached certs and roster when the server is down.
- Exit: enrol through a link with the root offline; the server revokes a member; a server-issued admin cert is refused; an expired delegation stops enrolment; `ENT-09`...`ENT-12`.

### E2c — Organisation directory, departments and the admin dashboard (owner request 2026-10-08)
Owner direction: the server also does **discovery**. An organisation's employees, departments and groups (with their admins) live on the server; a new employee enrols, sees only the groups and people the admin allowed, and joins one; the enrolment link shows only what that employee may see; the organisation manages all of it from a dashboard.

- **Model:** Organisation → Departments → Groups. Roles: *root* (offline), *org admin*, *department admin*, *group admin*, *member*. Each role is a **scope inside a delegation cert** (E2b), e.g. `admit:dept=Finance`, `admin:group=G7`. A role cannot grant more than it holds.
- **Visibility per group:** `listed` or `hidden`; join mode `open within my scope` | `request (a group admin approves)` | `admin adds only`. Per employee: the **scope** set at invite time (departments and groups they may see).
- **Invite from the dashboard:** the admin picks name, department, the groups the person may see and any auto-join groups, plus an expiry. The server stores this as the token's scope. The **link carries only** `server`, a single-use `token` and the `root` fingerprint; it never contains the group list. The list is fetched after enrolment, filtered server-side by the scope.
- **Employee discovery:** after enrolment the device calls the directory (device-cert authenticated). It sees only listed groups inside its scope and members it may see (same department or shared group). Choosing a group sends a join (instant if open, otherwise a request). Joining yields a **membership cert signed by a group admin or by the server's scoped key** (`admit:group=G`); the group secret is **not stored on the server** (open for the ADR: org-managed groups may rely on the signed cert chain for membership proof).
- **Offline:** the device caches the signed roster slice (E3) and its certs; a server outage never stops existing chats. A new join during an outage needs the server or an online group admin.
- **Dashboard with a web login (owner decision 2026-10-08, part of the server plan):** a web UI served by `:server` on a separate listener, LAN/VPN only by default.
  - **Login:** username + password (Argon2id hashes) **plus a required second factor** (TOTP; a hardware key later). Sessions use short-lived, HttpOnly, SameSite cookies; login is rate limited with lock-out; no password ever appears in a log.
  - **No default credentials in the public repo.** First run prints a **one-time setup token** on the server console; whoever holds it creates the first organisation admin. After that the token is dead.
  - **Step-up for dangerous actions:** creating or revoking an *admin*, changing the root, or issuing a delegation needs a **signature from the admin's own enrolled device** (approve on the phone/desktop). A stolen web password therefore cannot make or remove an admin. Ordinary actions (invite an employee, create a group, remove a member within the admin's scope) work from the web session through the server's delegated key.
  - Every admin action goes into a signed, append-only audit log. The admin's device can also sign in to the dashboard by scanning a QR (optional convenience, same second-factor strength).
- **What the server learns:** the organisation graph (who is in which department/group) and connection metadata. The organisation owns the server, so this is accepted, but it is **not** message content (E4 seals that). A compromised server leaks the directory and can enrol members within its quota; it cannot make an admin or read sealed messages.
- Exit: invite with a restricted scope; the new device lists exactly the allowed groups and nothing hidden; an open join works; a request join waits for a group admin; a department admin cannot touch another department; `ENT-13`...`ENT-18`.

### E3 — Signed roster and directory (FO-09 R3)
- The admin signs a **roster** (device certs + display names + group charters). Any node can carry and forward it, so an **air-gapped** fleet distributes it by QR/USB/sneakernet, and a hub is just one more carrier.
- Solves "mDNS does not cross VLANs": a roster entry may carry unicast address hints; this reuses the DR3 sweep/hint machinery. It conflicts with ADR-056's session-cap reasoning above 24 sessions: keep the cap (ADR-057) and measure before raising it.
- Exit: a device on another subnet with only a roster and a hint connects; `ENT-04`.

### E4 — Relay and mailbox hub (`:server`, optional)
Only build after E2, because the hub needs enrolled identities to authenticate.

- **Role:** store-and-forward and rendezvous for devices that cannot reach each other directly. It carries **sealed ciphertext it cannot read** and never issues trust.
- **Auth:** the device proves possession of its identity key (signed server-nonce challenge) and presents its admin-signed device cert. No shared bearer token (H12). Revoked certs are refused.
- **Confidentiality gap (H8) — design required:** pair keys exist only after pairing; vouched/enrolled peers and groups have no shared key, and ADR-044 / `security.md` 10.2 record "no per-sender keys, accepted limit". For a hub that is not acceptable. Proposal (needs its own ADR and an independent crypto review before any code):
  - **1:1:** sealed envelope to the recipient's *static identity key*: ephemeral-static ECDH P-256, HKDF-SHA-256 (`info` binds both device ids and the envelope id), AES-256-GCM; the envelope is also signed by the sender's identity key.
  - **Groups (N ≤ 20):** per-recipient sealed copies, or a message key derived from the group secret epoch (`GroupSecret`, rotated on removal, ADR-076). Choose after a cost review; per-recipient sealing is simplest and keeps removal clean.
  - Residual metadata the hub sees: who talks to whom, when, sizes. State it.
- **Mailbox:** TTL configurable (default 7 days), size caps per device, deduplicated by `(originDeviceId, msgId)`. Duplicates (LAN first, hub later) are ACKed and dropped by the existing id check.
- **Files:** small files through the mailbox, large files stay peer-to-peer or an explicit "request relay" within a quota. One boundary value, configurable (H14).
- **Calls:** not relayed (ADR-025: remote calls need STUN/TURN). Out of scope.
- **Server module rules (corrected from the hybrid draft):** `:server` is a JVM application depending on `:core:common` and `:core:security`; `:app` and `:desktop` have no edge to it; versions come from `gradle/libs.versions.toml` with a reason and licence note per dependency (H13); TLS terminates in exactly one place (H11); runs on Linux (the Docker image is the only Linux requirement the server adds, see `LINUX-PORT-PLAN.md`).
- **Client side:** write the relay client **once in commonMain** behind a port; today's network classes are platform-specific (H4). Settings live in `FlashSettingsDataStore` (Android) and `DesktopSettingsStore` (desktop).
- **Phones:** best-effort connection (H9); optional wake-up push is a *later* additive (FCM is unsuitable for a closed military fleet; UnifiedPush needs its own server).
- Exit: two enrolled devices exchange a sealed message through a hub while never on the same network; a duplicate arrives once; hub compromise test shows only ciphertext; `ENT-05`...`ENT-08`.

### E5 — Bridges: hotspot relay, Bluetooth, radio
A **bridge** is a node that holds two transports and forwards **signed/sealed envelopes** between them under the rules in section 4. Same engine for all:

| Bridge | Source | Notes |
|---|---|---|
| Hotspot phone relays for isolated clients | FO-06 option 1 | Only for chat/files; calls too heavy (3-4 video limit, EXP) |
| Bluetooth direct | `BLUETOOTH-AND-RADIO-TNC-PLAN.md` Pillar A | Needs the session-layer ADR from that plan; ADR-056 postponement applies |
| VHF/UHF radio via KISS TNC | same plan, Pillar B | Profile M (military): encrypted by default, no plaintext identity or position; hardware spike `BT-00` first |
| Hub gateway | E4 | A phone/PC with LAN + hub is a bridge too |

- A radio bridge for a **military** fleet carries only what the realm permits; the amateur-band restrictions in the radio plan (Profile A) do not apply and are not assumed for Ghana.

---

## 4. The shared envelope and transport capability model (the cross-cutting piece)

This is the one design that all three original plans lacked and all three needed.

**Envelope (logical fields):** `version`, `originDeviceId`, `msgId`, `hops`/`ttl`, `counter` (monotonic per origin+recipient, for replay protection), `kind`, `recipient(s)`, `payload` (sealed), `auth` (AEAD tag for pairwise, or ECDSA signature for broadcast). Two encodings: the normal JSON/binary form for IP transports, and a **compact binary form** for radio (220-byte budget, see the radio plan section 6.0).

**Rules (apply to every bridge and the hub):**
1. **Dedup** on `(originDeviceId, msgId)`; the durable outbox already dedups by message id on the receiving side.
2. **TTL** decremented per relay; zero is dropped. **Never send back out the interface it came in on.**
3. **Replay:** reject a counter at or below the last accepted for that origin+recipient.
4. **Authenticate before forwarding:** a bridge forwards only envelopes whose auth verifies and whose origin cert is not revoked.
5. **Capability filter:** a transport carries only the kinds it can carry.

| Transport class | Examples | Chat | Receipts/presence | Small files | Large files / swarm | Calls / PTT |
|---|---|---|---|---|---|---|
| Bulk | LAN, hotspot, Wi-Fi Direct (future) | yes | yes | yes | yes | yes |
| Mid | Bluetooth | yes | yes | yes (64 KB chunks) | slow, opt-in | PTT clips only |
| Async/WAN | Hub mailbox | yes | coarse | yes (cap) | quota only | no |
| Tiny | Radio TNC | yes (short) | minimal | no | no | store-and-forward voice clips only; real-time voice only if `BT-00` proves 9600 baud |

`FlashTransportType` gains `BLUETOOTH` and `RADIO`; `SessionHardeningPolicy.transportRank` gets explicit ranks (LAN best, radio worst). A session over a "Tiny" class must never be chosen for a kind it cannot carry.

---

## 5. Security requirements (military profile checklist)

1. Root key offline; the hub holds **no root key and no admin-level trust**, only a scoped, expiring delegation (E2b, D-E2, D-E8).
2. Revocation reaches every reachable node by gossip, and the hub, within one session of contact. State the offline window.
3. Device identity key custody tier stated: Android hardware key > desktop DPAPI (ADR-035) > Linux file/keyring (see Linux plan C7).
4. No plaintext identity, position or beacons on any radio bridge; AEAD by default; replay counter.
5. No secrets, keys, tokens or message content in logs or notifications (AGENTS.md section 24); realm secret excluded from backups like the group secret (`docs/security.md` 10.2).
6. Residual risks written into `docs/security.md` when each stage lands: hub metadata, eventual-consistency revocation, shared realm secret leak until rotated, traffic analysis on radio.
7. An independent cryptographic review of the sealed-envelope scheme before E4 code.

---

## 6. What this does **not** change

- The pairing protocol (ADR-042) stays. Pairing remains available inside a realm.
- Group membership by secret (ADR-070..076) stays; a realm is the same machinery one level up, so group invites inside a realm keep working.
- Wi-Fi Direct, BLE discovery and QR pairing stay postponed (ADR-056, FO-01..03).
- No server code in client builds.

---

## 7. Roadmap and ordering

| Order | Stage | Why here | Gate |
|---|---|---|---|
| 1 | E0, E1 | Cheap, serverless, uses existing proof code | Owner decision D-E3 (is the realm the first enterprise step?) |
| 2 | E2 | Without revocation the realm is not usable by a military fleet | D-E1, D-E2 answered; ADR for `REALM` trust |
| 3 | E3 | Air-gapped distribution and cross-subnet | E2 done |
| 4 | E5 radio hardware spike `BT-00` (parallel, no code) | De-risks the radio claims | Owner has a radio and a free afternoon |
| 5 | E4 hub | Only if the fleet needs WAN or offline mailbox; sealed envelope designed first | D-E4, crypto review |
| 6 | E5 bridges | After the envelope exists | ADR-056 postponement lifted for Bluetooth |

Nothing here may displace the owner's **device verification backlog** (`docs/testing/TEST-BACKLOG.md`); it is design work.

---

## 8. Tests owed (added to `docs/testing/TEST-BACKLOG.md`, section 4zl)

`ENT-01` realm gate refuses a stranger; `ENT-02` enrol; `ENT-03` revoke and refuse on reconnect; `ENT-04` cross-subnet by roster hint; `ENT-05` hub auth by key challenge; `ENT-06` sealed 1:1 through a hub, hub sees ciphertext only; `ENT-07` duplicate across LAN and hub shows once; `ENT-08` mailbox TTL and quotas; `ENT-09` enrol by link with the root offline; `ENT-10` server revokes a member, device refused; `ENT-11` server-issued admin cert refused; `ENT-12` expired delegation stops enrolment and a lapsed cert fails to renew; `ENT-13` restricted invite lists only allowed groups; `ENT-14` hidden group never listed; `ENT-15` open join works; `ENT-16` request join waits for a group admin; `ENT-17` department admin cannot act in another department; `ENT-18` web login with second factor, no default credentials, one-time setup token, device-signature step-up for admin actions and a signed audit log; `BT-00`...`BT-08` in the radio plan; `LNX-01`...`LNX-10` in the Linux plan. All `TODO`, none runnable until the matching stage is built.

---

## 9. Facts that were checked for this plan

Checked in the repository on 2026-10-07: ECDSA P-256 identity; `FlashTransportType` values; `SessionHardeningPolicy` ranks; service type `_flash-transfer._tcp`; ports 45822 and 45821; `MemberCert`, `GroupProof*`, `GroupSecret`, `PinSource`, `gs1`; androidMain-only `WsFlashNetwork` and `FlashSettingsDataStore`; ADR-044 decision 4 (vouched trust never grants 1:1); `docs/security.md` sections 4, 6, 10. Checked on the web: Android 15 `dataSync` limit; AX.25 PIDs; APRS length; BLE advertisement size; KISS TNC on the UV-Pro, VR-N76 and GA-5WB. **Not checked:** Ghana military spectrum rules (not public), Ktor/Exposed current versions, crypto scheme soundness (needs review).

---

## 10. Decisions needed from the owner

| ID | Question | Recommendation |
|---|---|---|
| **D-E1** | Do realm members skip the pairing code? | A new `REALM` trust level: chat, receipts and files allowed, scoped by the device cert's role; **calls and pairing-grade actions still need a code** unless the admin grants them. Needs an ADR amending ADR-044 decision 4 and AGENTS.md section 19. |
| **D-E2** | Who holds the admin key and where? | An offline admin laptop or hardware token; a hub never holds it. A second admin (threshold) later. |
| **D-E3** | Is the realm (E0-E2) the first enterprise deliverable, before any hub? | Yes. It delivers the closed-fleet security without a server. |
| **D-E4** | Is there any link between sites (internet or private WAN) that needs a hub? | Decide from the real deployment; if every site is one LAN plus radio, no hub is needed. |
| **D-E5** | Is the fleet fully air-gapped (no internet, no push)? | Assume yes until told otherwise; drives E3's sneakernet roster. |
| **D-E6** | Is the radio link (Profile M) a deliverable? | After `BT-00`. The hardware spike costs one radio and a phone. |
| **D-E7** | Linux desktop/server as a committed target (Linux plan D-L1)? | Decide with D-E4: a hub needs a Linux host, clients may be Windows only. |
| **D-E8** | May the server act without the root key online? | **ANSWERED 2026-10-08 (owner): yes, through a delegated, scoped, expiring key (option a).** Root stays offline; admin changes need root or 2-of-N admins. Needs an ADR before code. |
| **D-E9** | Device cert lifetime / renewal window (offline lock-out versus revocation delay) | Open. Proposal: 7 days default, set per organisation. |
| **D-E10** | Server-side directory with departments, group visibility and a dashboard (E2c) | **ANSWERED in direction 2026-10-08 (owner request)**; details below open. |
| **D-E11** | Can a hidden group be requested by id, or is it admin-add only? | **ANSWERED 2026-10-08: admin-add only**, never listed to others. |
| **D-E12** | How do dashboard admins sign in? | **ANSWERED 2026-10-08: a web login** (password + required second factor), with device-signature step-up for admin-level actions (E2c). |
| **D-E13** | Who creates departments and groups? | **ANSWERED 2026-10-08: the organisation admin creates departments; department admins create groups inside theirs.** |

Record each answer in `docs/decisions.md` as an ADR (numbers assigned on acceptance; the Linux plan already proposes ADR-092..094) and update the stage table above.
