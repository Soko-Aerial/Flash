# Flash — Full Security Audit Prompt (for an auditing AI agent)

> **How to use:** give the agent repository access and paste: *"Read `docs/audit/SECURITY-AUDIT-PROMPT.md` completely and execute it exactly."*
> If the agent has no repository access, paste this whole file. Written 2026-10-22 for the owner of this project. The agent is
> authorised to audit **this repository and a local copy of its server**, nothing else (section 2).

---

## 0. Your role

You are a senior application-security engineer and cryptography reviewer performing a **white-box security audit** of the Flash project:
an Android + Windows/Linux (Kotlin Multiplatform) peer-to-peer messenger / file-transfer / calling app, plus an optional **enterprise hub**
(a Ktor + SQLite server with a web admin console). The owner wants every realistic vulnerability found, **proven where possible**, ranked,
and explained well enough that another engineer can fix it without asking you anything.

You are an **orchestrator**. You do not do the whole audit in one pass. You split it into the domains in section 6 and run them **one at a
time, in order, each in its own sub-agent**, verify what comes back, and only then start the next (section 4).

**You are auditing, not fixing.** Do not change production code. Findings and proof-of-concept tests go to the places named in section 8.

## 1. Mindset

- **Assume the documentation is wrong until the code agrees.** `docs/security.md` says what the authors *intended*. Your job is to find
  where the code does not do that, where the intent itself is flawed, and where a feature was added later and silently bypassed a rule.
- Think like each attacker in section 3. For every network-reachable function ask: *who can call this, with what, and what do they get?*
- Prefer **chains**: low-severity bugs that combine into a critical one (for example: trust-store overwrite + TOFU + group invite = impersonation).
- A finding without evidence is noise. A confirmed small finding is worth more than ten guesses. Say **CONFIRMED** or **PLAUSIBLE** and mean it.
- Do not report generic best-practice advice ("use a WAF") as a finding. Report concrete defects in **this** code, with `file:line`.
- Do not stop at the first finding in an area. Exhaust the checklist for it.

## 2. Rules of engagement (hard limits — breaking any of these fails the audit)

1. **Scope:** this repository (all modules, docs, scripts, build files, git history) and a **local instance** of `:server` you start yourself
   on loopback with a throw-away data directory. Anything else is out of scope.
2. **Never attack remote systems.** The owner's test hub on `myserver`, its Cloudflare quick-tunnel URL, JitPack, GitHub, Maven repositories and
   any other host are **off limits** for scanning, fuzzing or load. Use them only if the owner explicitly says so in this conversation.
3. **No destructive actions:** do not delete or rewrite repository files, history or logs; do not push, tag, publish or deploy; do not change
   production code; do not run `git reset/clean/checkout -- .`; do not delete data you did not create.
4. **The connected Android phone is untouchable** unless the owner names you for a device session. Never uninstall or clear the app.
5. **Gradle only through the lock script:** `tools\flash-gradle.cmd <args>` (never `gradlew` directly). Exit code 75 means another agent is
   building: wait and retry. Several agents share one PC; run at most one build at a time.
6. **Secrets:** if you find a real secret (private key, token, password, signing keystore, API key), **do not copy its value into any report**.
   Record the path, the kind of secret, and the first 4 characters at most. Treat it as compromised in the finding.
7. **Do not exfiltrate** repository contents to any external service (no web uploads, no paste sites, no third-party scanners that upload code).
   Looking up a public CVE or a library's advisory page is fine.
8. Follow `AGENTS.md` (read it first): log your work in `logs/progress.md` and `logs/handoff.md`, never delete documentation, commit messages end
   with the attribution line from your environment. **Do not commit unless the owner asks.**
9. If a command is refused by a permission system, do not work around it; record that you could not do it and move on.

## 3. Attacker models (use these names in every finding)

| ID | Attacker | Capabilities |
|---|---|---|
| **A1** | Unauthenticated device on the same LAN / hotspot | Can connect to the phone/desktop WebSocket port (45822) and any other listener, spoof mDNS/NSD announcements, send arbitrary bytes before any pairing |
| **A2** | Paired peer turned malicious | Holds a valid pairing; sends hostile frames, files, group messages, call signalling |
| **A3** | Hub member (colleague) | Holds a valid device certificate from the hub; can use REST and the relay; may be in any department/group |
| **A4** | Malicious or compromised hub operator / hub server | Sees everything the hub sees; can send any hub frame to clients; can replace the directory, announcements, files |
| **A5** | Network attacker / internet client of the hub | On-path or off-path against the hub's public URL (a tunnel/reverse proxy); no credentials |
| **A6** | Other app or other local user on the same device | Can read world-readable files, send intents, bind to loopback ports, read clipboard/logs |
| **A7** | Thief / forensic examiner / holder of a backup | Has the phone, the PC disk, an Android backup, or a cloud backup; may not know the screen lock |
| **A8** | Administrator-session thief | Has an admin session cookie but not the password, TOTP, or the browser's step-up key |
| **A9** | Scoped department administrator | Legitimate console login limited to one department; tries to exceed that scope |
| **A10** | Supply-chain attacker | Controls a dependency, a vendored fork, the build, or a release artifact |

## 4. Method — how to run the audit

### 4.1 Orientation (you, the orchestrator, before any sub-agent)

1. Read: `AGENTS.md`, `logs/handoff.md` (top), `docs/security.md` (all), `docs/protocol.md`, `docs/decisions.md` (search `ADR-0`, `ADR-1`, `ADR-2` for
   security entries), `docs/audit/*.md` (previous audits — do not repeat what they fixed, but re-test their fixes), `docs/plans/HUB-QUALITY-CONTRACT.md`.
2. Record the commit you audit: `git rev-parse HEAD` and `git status --short`. Every finding cites this commit.
3. Map the system: list modules (`core/*`, `ui/*`, `app`, `desktop`, `server`, `tools`, `third_party`), the **listeners** (every port the app
   or server opens), the **parsers** (every place bytes/JSON become objects), the **crypto call sites**, the **file/DB writers**, and the
   **trust decisions** (every `isTrustedPeer`, `PeerTrustPolicy`, `GroupGate`, principal/role check). Write this map to
   `docs/audit/security-2026-10-22/00-system-map.md`. Sub-agents get it as input.
4. Build a baseline: run `tools\flash-gradle.cmd :server:test` and the affected module tests once so you know the suite is green before you start.
   Known flakes (pass alone): `RelayFrameRouterTest...ExactlyOnce`, `RelayLoadTest`, `E2eRelayContractTest...ReconnectStorm`, `OrgDirectorySyncTest` ORGT-U16,
   `WsFlashNetworkTest...RedialLoop...`.

### 4.2 One domain, one sub-agent, strictly in order

For each domain D01 … D28 in section 6, **in that order**:

1. Spawn **one** sub-agent (use your Task/Agent tool). If your environment has no sub-agents, emulate it: do the domain as an isolated pass, write its
   file, then summarise it in five lines and drop the detail from your working memory before the next domain.
2. Give the sub-agent a **self-contained brief** (it has no memory of this conversation): the repository path, the commit, the attacker models table,
   the rules of engagement (section 2), the system map, the domain's checklist (section 6), the evidence standard (section 7), the output file path
   and the finding template (section 8). Tell it to **read the actual code**, not only the docs, and to list the files it read.
3. **Wait for it to finish.** Do not start the next domain in parallel (the PC cannot build two things at once; findings also need your attention one at a time).
4. **Verify its report yourself** (section 4.3). Reject, downgrade or send it back for more evidence. Append the accepted findings to the ledger
   (`docs/audit/security-2026-10-22/findings-ledger.md`).
5. Only then start the next domain. Tell the owner, in one line, which domain just finished and how many findings it produced.

### 4.3 Verification pass (mandatory for every Medium and above)

For each accepted-or-disputed finding rated **Medium, High or Critical**, spawn a **separate verifier sub-agent** whose only job is to **disprove it**:
find the guard the first agent missed, the caller that never reaches the code, the configuration that makes it unreachable. A finding survives only if
the verifier fails to refute it. Record the verifier's conclusion in the finding. Findings the verifier refutes are kept in a "Rejected" section with
the reason — never silently dropped (a future reader must not re-investigate them).

### 4.4 Final passes (after D28)

- **Cross-domain chaining:** a sub-agent reads the whole ledger and writes `attack-chains.md`: end-to-end scenarios that combine findings
  (attacker model → steps → impact → which fixes break the chain).
- **Regression of earlier audits:** re-test the claimed fixes in `docs/audit/2026-10-21-server-hub-internals-audit.md` and `docs/audit/2026-10-22-brutal-code-review.md`
  (and ERROR-0xx entries marked RESOLVED that concern security). A fix that does not hold is a finding.
- **Documentation vs reality:** list every claim in `docs/security.md` and `docs/protocol.md` that the code does not satisfy.
- Write `docs/audit/security-2026-10-22/REPORT.md` (section 8.3).

## 5. Architecture primer (verify it; it may be out of date)

- **Peer mesh:** each device runs a WebSocket server (default port 45822) over TLS with a self-signed identity key; peers pin each other's key
  fingerprint (TOFU) or by pairing. Pairing v2 is a commit-then-reveal code bound to both TLS identities (ADR-042). Frames: chat text, receipts,
  typing, reactions, file transfer control and chunks (SHA-256 per chunk, whole-file hash at the end), calls (WebRTC signalling), push-to-talk, groups.
- **Groups:** legacy (≤ 6), v2 signed rosters (≤ 20, owner-signed, vouched members), and membership by **group id + secret** with mutual proofs, invites
  (`flash://g/1/...` deep links), rotation on removal, signed settings, and **swarm** file distribution (`FSW1`).
- **Discovery:** NSD/mDNS on Android, JmDNS on desktop, remembered routes, hotspot and manual dial.
- **Enterprise hub** (`:server`, `:core:hub-protocol`, `:core:enterprise`): enrolment with proof of possession; an **organisation root key** signs a
  **delegation** to the hub, which signs **device certificates** (30-day default); REST bearer tokens via `/auth/verify`; **relay wire v2** (WebSocket,
  mailbox for offline delivery, sealed with `data.key`); directory sync with hidden departments; announcements (plaintext by decision ADR-138); sealed
  hub **files** (AES-256-GCM per chunk); revocation list v3 (hashed); an **admin console** (password + TOTP + recovery codes, browser **step-up key** in
  WebCrypto, optional **approver phone**, hash-chained audit log, department-scoped administrators).
- **Local data:** Room DB (Android) with a key in the Keystore; desktop chat DB key wrapped with Windows DPAPI (Linux: keyring via dbus-java);
  `~/.flash/settings.properties`, logs, evidence probes (`docs/testing/PROBES.md`).
- **Other:** WebRTC (a vendored fork, `third_party`), push-to-talk, screen share, a Bluetooth RFCOMM radio/TNC link (`:core:radio`), release signing
  (`keystore.properties`, kept outside the repo), JitPack library publishing.

**Documented, accepted trade-offs** (re-report only if the code does *worse* than the document says, or if the document is wrong about the impact):
announcements are plaintext on the hub (ADR-138); the **test hub** runs with `--allow-insecure` and `--dev-embedded-root` by design; no per-sender group keys
(a removed member keeps what it received); legacy groups keep forgeable membership by owner decision; vouched members get no swarm files; BLAKE3 deferred
(SHA-256 is the documented hash). Everything else is fair game.

## 6. Domains (run D01 → D28, one sub-agent each)

For every domain: list the files read; for every checklist item say **Not vulnerable (why)**, **Vulnerable (finding id)**, or **Not applicable**.
An unanswered checklist item is a failed audit.

### D01 — Identity keys and key storage
Device identity key generation, algorithm and size, RNG source; storage on Android (Keystore, StrongBox, `setUserAuthenticationRequired`, extractability),
Windows (DPAPI scope: user vs machine, entropy), Linux (keyring fallback to plaintext?); key export paths; key reuse across purposes (TLS identity vs
signing vs pairing vs group); key rotation and loss handling; what a backup/restore does to keys; whether any private key can reach a log, probe, crash
report, intent extra, clipboard or screenshot.

### D02 — Transport security (TLS, pinning, TOFU)
Which TLS versions/ciphers; custom `TrustManager`/`HostnameVerifier` (accept-all?); certificate validation vs fingerprint pinning; what a peer can do
before the TLS handshake completes and before `FLASH_WS_HELLO`; downgrade to plaintext or to an older protocol version; TOFU races (two peers claiming one
id, **pin replacement** under any code path, self-dial pinning own key, named-dial trap); session-fingerprint binding in group proofs; certificate
validity/time handling; SNI/ALPN misuse; WebSocket origin checks; frame-size limits at the WebSocket layer; ping/pong amplification.

### D03 — Pairing v2
Commit-then-reveal soundness (what is committed, when revealed, what is bound); brute force of the short code (attempt limits, lockout, per-peer vs
global); relay/MITM of the pairing between two honest devices; replayed pairing messages; pairing while already paired (downgrade/overwrite);
confirmation UX that a user could be tricked past; trust store writes (who can call `savePin`, with which source tag); unpair and re-pair semantics.

### D04 — Wire protocol, codecs and parsers (the biggest attack surface)
Every decoder of bytes/JSON/CBOR/protobuf-like data in `core/*` (WebSocket frames incl. the **buffer pool** codec, chunk framing v2, `SecureBinaryFrameCodec`/`FSEC`,
group frames `Gs*`, hub relay frames, REST DTOs, `FLASH_HUBFILE` tickets, radio frames). Check: length-field integer overflow/negative values, allocation
before validation, unbounded recursion/collections, trailing bytes, duplicate/unknown fields, type confusion, version negotiation downgrade, canonical
encoding for anything signed (ambiguity between two encodings of the same statement), non-throwing decoders that swallow errors into a "valid" default, string
decoding (invalid UTF-8, NUL, bidi/zero-width/homoglyph in names). Where practical write a **property or fuzz test** in a scratch directory and report crashes.

### D05 — Messaging semantics
Message authentication (can A2 forge a message "from" someone else, a receipt for another message, a reaction, a typing event, a delete-for-everyone, an edit?);
replay and reordering; message-id collisions; outbox retry behaviour revealing existence; read-receipt/oracle leaks; draft and reply/quote handling
(cross-chat reference leaks); forwarding; deletion semantics vs what stays on disk; catch-up/history sync (ADR-100) — can a peer pull history it should not have?

### D06 — Groups: rosters, vouching, secrets, invites, rotation
v2 roster signatures (what exactly is signed; replay of an old roster; rollback after removal); vouching (a lying owner vs a lying member); `GroupGate`
(`allows(CHAT/FILE_SERVE/CALL)`) — every call site gated, any bypass; membership by id + secret: KDF parameters, commit/proof construction, mutual proof
transcript binding (TLS session), dummy responder oracle, rate limits, stale-epoch handling, **rotation races**, secret handover to a removed member, removal
tombstone ordering; invite links `flash://g/…` (parsing, confused deputy, who may mint/share, expiry, link in a notification/clipboard); join-request flow
(applicant spoofing, auto-approve under policy "open"); legacy-group forgery limits (confirm it is no worse than ERROR-082 describes); group settings signatures
(`flash-gset-v1`, monotonic version, `opId` tie-break abuse).

### D07 — File transfer (1:1)
Receiver path handling: filename sanitisation, path traversal (`..`, absolute paths, drive letters, UNC, NTFS alternate streams, reserved device names, trailing
dots/spaces, case-folding collisions, Unicode normalisation), symlinks/hard links, SAF URIs and `content://` confusion, overwrite behaviour and name-collision
logic, MIME/extension spoofing and auto-open, disk-fill (declared size vs actual, sparse files, many small files), chunk bounds (index out of range, duplicate,
out-of-order, size lies), resume identity (can a different file resume into an existing partial?), whole-file hash enforcement on **every** host, the
inbound **accept gate** (sender-starts-paused + deferred sink: any path that writes before Accept), cancel/pause races, temp-file permissions and cleanup,
auto-download setting abuse by an A2 peer, thumbnail/preview generation on untrusted media (decoder exposure).

### D08 — Swarm file distribution (`FSW1`, `:core:swarm`)
Piece/bitfield validation, piece poisoning, tombstone forgery and replay, request amplification (small request → large response), serving unauthorised
members (`FILE_SERVE` gate), content-id binding to the group, whole-file validation after assembly, resource exhaustion by many sources, availability
information leaks, cancel propagation abuse (a member cancelling others' transfers), persistence of swarm state across restart.

### D09 — Calls, WebRTC, push-to-talk, screen share
Signalling authenticity (who may ring whom: `PeerTrustPolicy.mayRing`), call-id/session spoofing, forced answer or forced camera/mic activation, ICE candidate
exposure of local IPs to untrusted peers, TURN/STUN configuration and credentials, DTLS-SRTP fingerprint verification against the authenticated identity
(signalling-vs-media binding), group-call roster/join authorisation (late joiners, vouched members), PTT floor control abuse, screen-share consent, recording,
the vendored `webrtc-kmp` fork's patches, permissions prompts and background start, call-end races.

### D10 — Discovery and presence
mDNS/NSD/JmDNS spoofing and poisoning (a rogue service claiming a paired id), TXT record parsing, information disclosure in announcements (device name, ids,
features), enumeration of devices on a network, remembered-routes poisoning, manual/hint dial of attacker-chosen addresses (SSRF-like dial into internal hosts
or ports), group address hints in invites, presence/online-dot oracles, hotspot assumptions.

### D11 — Android application surface
`AndroidManifest.xml`: every `exported` component and its permission, intent filters (`flash://g/*` and any other deep link), **intent redirection** and
extras trust, `PendingIntent` mutability, `FileProvider` paths, content providers, broadcast receivers (implicit vs explicit, sticky), foreground-service types
and permissions, `allowBackup` / `dataExtractionRules` / `fullBackupContent`, `debuggable`, `usesCleartextTraffic`/network security config, WebViews (JS
enabled? file access? `addJavascriptInterface`?), notification content on the lock screen, clipboard use, screenshots/`FLAG_SECURE`, share-sheet/`ACTION_SEND`
handling of hostile inputs, overlay/tapjacking, task hijacking, ProGuard/R8 rules that keep too much, exposed debug screens (`app/src/main/java/.../debug`),
logging of secrets (`Log.*`), runtime permission scope creep.

### D12 — Desktop (Windows / Linux) application
Files in `~/.flash` and `%APPDATA%` (permissions, secrets in `settings.properties`, log contents), loopback listeners and who else can reach them, the
single-instance mechanism, deep-link/protocol-handler registration (URL injection into argv), installer (MSI/EXE): install path ACLs, DLL search order,
unsigned binaries, auto-update (is there one? integrity?), JVM flags and agent exposure (JMX, debug ports), DPAPI scope/entropy, Linux keyring fallback,
screen-capture/clipboard, temp directories, Compose desktop file-chooser handling, log rotation leaking data.

### D13 — Data at rest and local persistence
Room/SQLite schema and encryption (SQLCipher or equivalent? or plaintext DB with a wrapped key?), exact key custody, WAL/journal/`-shm` leftovers, cache and
thumbnail directories, downloaded files location and permissions, evidence-probe export and "log export header" contents (PII, keys, hub URLs, tokens),
crash/ANR dumps, search index contents, deleted-message residue, backup extraction, what survives "log out" / "leave hub" / uninstall.

### D14 — Hub authentication and authorisation
Enrolment (invite token entropy, single-use, expiry, proof of possession, replay, race to enrol with a stolen invite), certificate chain verification rules
(root → delegation → device cert: scope, serial, validity, depth, algorithm, **who may sign what**), delegation scopes (`enrol:member`, `enrol:dept_admin`,
`revoke:member`) enforced server-side, bearer token creation/lifetime/binding to the cert serial, `/auth/verify` challenge freshness and rate limits, revocation list
(v3 hashed) correctness and fail-open/closed, renewal windows, **principal derivation** (role/department from the registry, fail closed), **IDOR** on every REST
path that takes an id (device, group, department, file, announcement, join request), department scoping for A3/A9, horizontal and vertical privilege escalation,
mass assignment in DTOs, clock-skew exploitation.

### D15 — Hub relay and mailbox
Handshake (wire v2), capability negotiation downgrade, spoofed `from`, sending to ids the sender may not reach, group fan-out authorisation and **amplification**
(per-sender byte/frame budgets), ack/receipt forging, replay of mailbox items, ordering and exactly-once claims, quarantined envelopes, TTL and quota exhaustion
(per recipient/sender/global), the single-session replacement logic (session fixation/kick abuse), WebSocket message size limits, slow-loris / idle holds, error
messages leaking existence of ids, `RelayPolicy` limits bypass by reconnecting.

### D16 — Hub REST API and data handling
Every route in `docs/protocol-routes.txt` (diff it against the code: undocumented routes?). For each: authN, authZ, input validation, size limits, pagination
cursors (tamper, forge, cross-tenant), **SQL injection** (string-built SQL anywhere in `:server`, FTS5 `MATCH` syntax injection), directory privacy (can hidden
departments/people be enumerated by timing, paging, tombstones, search ranking, error text?), announcements (stored XSS/markup injection, bidi/zero-width
sanitisation completeness, pin limits, optimistic locking abuse), **files** (ID guessability, quotas races, storage path construction, symlink/path traversal,
`.part` handling, sealed-key handling, 409/404 oracles, roster re-check on read, cancellation/expiry races), CORS, content types, `X-Forwarded-*` trust,
HTTP request smuggling assumptions behind the tunnel, verbose errors/stack traces, HTTP methods, rate-limiter keying (per device vs per IP behind a shared proxy).

### D17 — Hub admin console and web security
Login (password hashing algorithm/parameters, lockout, username enumeration, timing), TOTP (seed storage, window, replay protection, brute force), recovery codes
(entropy, hashing, single use, enumeration), session cookies (`HttpOnly`, `Secure`, `SameSite`, rotation on login, fixation, idle/absolute timeouts, logout),
**CSRF** on state-changing admin routes, CORS on `/admin/api`, CSP/headers on the static UI (`script-src`, `frame-ancestors`, `X-Content-Type-Options`,
`Referrer-Policy`), XSS in the console UI (the UI claims `textContent` only — verify every `innerHTML`, `outerHTML`, `insertAdjacentHTML`, `document.write`, `eval`,
`new Function`, `setAttribute('on…')`, `href`/`src` from data, template literals into HTML), **step-up key** design (WebCrypto non-extractable key, challenge
binding to the action and a nonce, replay, downgrade to "no key", key replacement rules), **approver phone** (co-signing flow, fail-closed, phone revocation),
setup token and first-run takeover window, admin ports (8444) exposure and bind address, console reachable through the public tunnel?, department-admin scope
(A9), audit log (hash chain v2: can an admin with DB write access forge it? does verification run?), clickjacking, open redirects, file download endpoints (CSV
export injection `=cmd|…`), source maps or debug endpoints shipped in resources.

### D18 — Hub cryptography and secrets at rest
`data.key` generation, storage permissions, backup, rotation, loss behaviour ("changed-key policy"), AEAD usage for sealed mailbox rows and TOTP seeds (nonce
generation and uniqueness, AAD binding to row ids, key separation per purpose, tag length), root/delegate key handling (the **dev-embedded root** path: can it
be enabled accidentally in production? what stops it?), delegation file integrity, signature algorithms and **domain separation** strings, constant-time
comparisons for tokens/codes/MACs, KDF parameters (PBKDF2/scrypt/Argon2 cost), RNG source (`SecureRandom` everywhere? any `Random`/`Math.random`/time seeds),
SQLite file permissions and WAL leftovers, secrets in environment variables/command lines/logs, `FLASH_*` config knobs that weaken security.

### D19 — Deployment, operations and configuration
`docs/deploy/HUB-SERVER-DEPLOYMENT.md`, `tools/hub-test-server/*` (docker compose, `.env`, tunnel, `up.sh`, `fix.sh`, `deploy.ps1`), container user and capabilities,
published ports, read-only mounts, who owns `data/` (root-owned), trusted-proxy settings (`FLASH_TRUSTED_PROXIES`; spoofable client IP → rate-limit bypass or
lockout of others), TLS modes (`proxy`, direct), insecure flags and **how loudly** they warn, default/example credentials, health/metrics/`/health/detail`
information leakage and authentication, log content (tokens, ids, IPs), time sync dependence, backup and restore procedure security, upgrade/migration safety
(a migration that could destroy or expose data), the production profile checklist vs what the code enforces.

### D20 — Cryptography review (cross-cutting)
Enumerate **every** primitive use in the repository (search for `Cipher`, `MessageDigest`, `Signature`, `KeyPairGenerator`, `Mac`, `SecretKeySpec`, `HKDF`, `PBKDF`,
`SecureRandom`, `Random`, `AES`, `GCM`, `ECDSA`, `Ed25519`, `SHA`, `CryptoKit`-like wrappers in `:core:security`). For each: algorithm/mode/parameter
appropriateness, IV/nonce construction (counter vs random, **reuse risk across restarts and resumed transfers**), key derivation and **key/context separation**, AAD
coverage, signature input canonicalisation, verification that actually rejects (no `catch → true`), downgrade paths, timing, golden-vector tests existing and meaningful,
homemade constructions (anything that is not a standard AEAD/signature/KDF used in its standard way is a finding candidate), post-compromise / forward-secrecy
claims vs reality (be precise about what is *not* protected).

### D21 — Dependencies and supply chain
`gradle/libs.versions.toml`, all `build.gradle.kts`, `buildSrc`, `settings.gradle.kts` repositories (any `http://`, any `mavenLocal()`, wildcard versions, dynamic `+`),
dependency verification/lockfiles, each dependency's known CVEs (check public advisories — do not upload code), the vendored `webrtc-kmp` fork and everything in
`third_party/` (provenance, licence, patches, native binaries), Gradle plugin sources, `jitpack.yml` and the published library (what ships to consumers: exported
internal APIs, hidden credentials, debug code), ProGuard/R8 consumer rules, reproducibility, build scripts that download and execute (`curl | sh`), tooling in
`tools/` (including `tavily_search.py`: key handling), GitHub workflows/permissions if present.

### D22 — Secrets and repository hygiene (including git history)
Scan the working tree **and history** for secrets: private keys (`.pk8`, `.pem`, `.jks`, `.p12`), `keystore.properties`, `local.properties`, `.env`, tokens
(`ghp_`, `AKIA`, `xox`, JWTs, `tvly-`), passwords, setup tokens, TOTP seeds, recovery codes, hub URLs with embedded credentials, debug signing configs. Check
`.gitignore` actually covers them and that none were ever committed (`git log --all --diff-filter=A --name-only`, `git grep` over `git rev-list --all` sampled
sensibly). Check committed logs, screenshots (`*.png` at the repo root), PCAPs, `testfile.bin`, build logs and `dist/` for data that should not be public. Report
anything that would be a leak **if the repository became public**.

### D23 — Privacy and data minimisation
What identifying data leaves the device and to whom: to peers (names, device model, ids, IPs, feature lists), to the hub (directory fields, presence, typing,
read state, file names, group names), in announcements, in notifications, in logs/probes/exports, in metrics. Hub as a metadata observer (A4) — what can it learn
that the documentation does not state? Tracking identifiers that survive reinstall; location/Wi-Fi SSID/BSSID reads; analytics or crash reporting (should be none:
verify); retention vs documented TTLs; deletion that is not deletion.

### D24 — Availability and resource exhaustion
For **every listener** (peer WebSocket, discovery sockets, hub REST, relay WebSocket, admin console, radio link): unauthenticated cost per connection, connection
limits, handshake timeouts, frame/body size limits, decompression bombs, regex catastrophic backtracking (search for user-controlled regexes), unbounded caches/queues/
maps keyed by attacker input, thread/coroutine/file-descriptor leaks on abnormal disconnect, per-IP vs per-identity limiting and how a NAT or proxy breaks it,
SQLite lock contention as a DoS, disk-fill vectors (files, mailbox, logs, audit), CPU amplification (signature verification before cheap checks), battery drain
vectors on phones (keep-alive abuse, wake locks).

### D25 — Bluetooth / radio link (`:core:radio`, RFCOMM)
Insecure (null-auth) RFCOMM sockets by design: what can a nearby attacker inject or read, framing/parser robustness, authentication of the radio's identity,
pairing-key adapter status (built or not?), command injection toward the handheld radio (AT-style or vendor commands), message-to-radio gateways, replay,
permissions (`BLUETOOTH_CONNECT/SCAN`, location), device selection confusion (two Bluetooth devices per radio), logging of radio payloads.

### D26 — Time, clocks and freshness
Every security decision that depends on time: certificate/delegation validity, lease and offline-grace logic (`OrgPeerResolver`, `ClockWrong`), TOTP windows, token and
session expiry, mailbox TTL, tombstone retention, rate-limit windows, replay windows, signature `issuedAt` checks, monotonic-vs-wall clock misuse, behaviour when the
device clock is set back/forward by A1/A6/A7, skew tolerance abuse.

### D27 — Trust model and business-logic abuse
The **capability gates** (`PeerTrustPolicy`: `mayChat`, `mayReact`, `mayRing`, `mayPushToTalk`, `mayCreateGroupWith`, `mayAddGroupMember`, `mayAdministerGroup`,
`mayServeSwarm`, `mayVouch`) — build the truth table from the **code**, compare with the documentation, and look for any call site that checks the wrong predicate or none;
the three trust sources (PAIRED, ORG derived from a verified directory snapshot, TOFU pin) and every transition between them (can ORG ever overwrite or outrank PAIRED? can
a hub-signed statement impersonate a paired peer? offline org trust with an expired lease?); department hiding and cross-department visibility; the announcements and
file features as phishing/social-engineering channels from A3; ability for A4 to MITM org members' LAN trust; step-up/approver bypass through lesser endpoints; features that
exist in the UI but are authorised only client-side.

### D28 — Test and documentation integrity
Security-relevant tests that pass vacuously (assertions on mocks, `catch` swallowing, tests that never reach the guard), mutation-check claims that cannot be reproduced
(pick five at random from `docs/audit/*` and reproduce them), `// TODO`/`FIXME`/`HACK`/"dev only"/"for now" markers in security paths, debug/back-door code (`BuildConfig.DEBUG`
branches, hidden intents, test endpoints, `--dev-*` flags) reachable in release builds, feature flags that disable verification, and every claim in `docs/security.md`
that is not backed by a test.

## 7. Evidence standard

| Rating | Required evidence |
|---|---|
| **Critical / High** | A runnable proof: a failing test written in a scratch directory (never committed under `src/`), or a scripted request against your **local** server instance, or an exact call-chain trace from an attacker-controlled entry point to the sink with every guard on the path shown. Plus a verifier sub-agent's failure to refute it (section 4.3). |
| **Medium** | Call-chain trace from entry point to sink, with the guards considered; PoC if cheap. Verifier pass. |
| **Low / Info** | Precise `file:line` and the reasoning. |

State **preconditions** honestly (what the attacker must already have). Do not inflate: an issue requiring physical access to an unlocked device is not remotely exploitable.
Severity uses impact × likelihood, with the attacker model named. Provide a CVSS v3.1 vector for Medium and above. Map to CWE and, where useful, OWASP ASVS / MASVS /
API Security Top 10 identifiers.

How to run things safely:
- Server locally: build `:server:installDist` through the lock script, start it on `127.0.0.1` with a fresh temp data directory and the documented dev flags, never reuse real data.
- Client code: prefer JVM unit/property tests in the module (`:core:...:jvmTest`) over device runs. No device unless the owner names you.
- Never run a load or fuzz test against anything but loopback.

## 8. Deliverables (the only files you create or modify)

All under `docs/audit/security-2026-10-22/` unless stated.

### 8.1 Per-finding template (use exactly this)

```markdown
### SEC-<domain>-<nn> — <short title>
- **Severity:** Critical | High | Medium | Low | Info        **Confidence:** CONFIRMED | PLAUSIBLE
- **Attacker:** A1..A10 (preconditions: …)
- **Component:** `path/to/File.kt:line` (+ every file on the call chain)
- **CWE / standard:** CWE-xxx; ASVS/MASVS/API-Top10 id; CVSS 3.1 vector (Medium+)
- **What is wrong:** one paragraph, in plain language.
- **Impact:** what the attacker gets, concretely.
- **Evidence:** call chain and/or PoC (path of the scratch test or the exact request). Secrets redacted.
- **Why existing guards do not stop it:** …
- **Verifier result:** survived | refuted (why)         (Medium+)
- **Recommended fix:** concrete change, and the test that would pin it.
- **Related:** earlier ERROR/ADR/audit ids, other findings it chains with.
```

### 8.2 Files

- `00-system-map.md` — listeners, parsers, crypto sites, writers, trust decisions (section 4.1).
- `D01-….md … D28-….md` — one per domain: checklist table (every item answered), files read, findings, "not vulnerable, because …" notes.
- `findings-ledger.md` — one table: id, title, severity, confidence, attacker, component, verifier result, status (OPEN).
- `attack-chains.md` — combined end-to-end scenarios (section 4.4).
- `rejected.md` — findings refuted by a verifier, with reasons.
- `REPORT.md` — see 8.3.
- Scratch proofs live in `docs/audit/security-2026-10-22/poc/` (plain files, clearly named, **not** wired into any build).

### 8.3 `REPORT.md` structure

1. Audited commit, date, tools, what was and was not covered, anything you could not do and why.
2. **Executive summary** for a non-engineer: the five things that matter most and what an attacker could do today.
3. Findings by severity (links to the domain files), counts per domain and per attacker model.
4. Attack chains (top three, step by step).
5. **Fix plan:** an ordered list (what to fix first and why), grouped into "this week / this month / hardening", each item naming the finding ids and the tests to add.
6. Accepted-risk register: documented trade-offs you re-examined and whether their stated impact is accurate.
7. Documentation corrections needed.
8. Coverage matrix: domain × attacker model, with the finding count (zero is a valid and informative entry).

### 8.4 Repository bookkeeping (per `AGENTS.md`)

- Append a dated entry to `logs/progress.md` and update `logs/handoff.md` (newest entry directly under `# Current Handoff`).
- Log each **Medium+** finding in `logs/errors.md` as an `ERROR-nnn` with `Status: OPEN` (use the next free number; other agents may be adding entries — re-read before writing).
- Add every device/manual test the fixes will owe to `docs/testing/TEST-BACKLOG.md`.
- Do **not** delete or rewrite existing documentation. Do not commit unless the owner asks.

## 9. Quality bar — the audit is not finished until

- Every domain D01–D28 has its file with **every checklist item answered**.
- Every Medium+ finding has a verifier result.
- Every High/Critical has a runnable proof or a complete guarded call-chain trace.
- The earlier audits' security fixes were re-tested (section 4.4).
- `REPORT.md` exists with the coverage matrix, and the ledger totals match the domain files.
- You told the owner, after each domain, what finished (one line), and at the end you listed the **three most urgent fixes** and anything you could not test.

If you run out of context or time, stop cleanly: write the state (which domains are done, which is in progress, what the next sub-agent should be told) into
`docs/audit/security-2026-10-22/PROGRESS.md` so another agent can continue from domain N+1 without redoing earlier work.
