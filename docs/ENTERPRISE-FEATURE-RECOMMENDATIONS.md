# Enterprise hub — feature recommendations (server and client)

Date: 2026-10-13. Status: RECOMMENDATIONS ONLY. Nothing here is built or decided; each item needs an owner decision and, where
marked, an ADR before code (AGENTS.md section 8). Written after the hub audit (`docs/audit/2026-10-12-server-hub-audit.md`) and the
Phase 1-3 fixes, so it assumes the fixed baseline: authenticated relay, offline root + delegation, revocation, durable mailbox with
ack-based delivery, authenticated directory, admin console, sealed messages (dormant until sealing keys are served).

How to read the tables: **Value** = what it gives an organisation (H/M/L). **Effort** = S (days), M (1-3 weeks), L (a month+).
**Builds on** names code that already exists so the work is an extension, not a new subsystem. **Risk / decision** is what could go
wrong or what the owner must decide. Items marked **ADR** change the trust model or wire contract.

Guiding constraints (do not break these to add a feature):
1. The hub never needs to read message content (sealed envelopes; AGENTS.md section 19). Features that need content (search, DLP,
   retention by content) must be designed as client-side or opt-in "hub can read" modes, stated honestly in the UI.
2. The root key stays offline. Anything that needs root authority is an explicit, rare, audited admin ceremony.
3. LAN-first stays: the hub is a fallback/relay and directory, never a requirement for devices on the same network.
4. Open-source, self-hosted, no vendor cloud. Optional integrations must be pluggable and off by default.

---

## A. Server-side features

### A1. Identity, access and administration

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| S-01 | **Role-based admin (RBAC) with scoped admins**: roles such as Org admin, Department admin, Auditor (read-only), Helpdesk (invite + unlock only). Today admins are all-powerful. | H | M | `admin/AdminAuthService`, step-up, audit | Needs a permission matrix; Auditor role must be unable to see secrets. **ADR** |
| S-02 | **SSO for the admin console (OIDC / SAML)** with the existing TOTP + step-up as fallback; map IdP groups to RBAC roles. | H | M | admin session layer, `CsrfGuard` | Air-gapped orgs keep local login; OIDC must not weaken step-up (step-up stays device-key based). |
| S-03 | **Directory sync (SCIM 2.0 server + LDAP/AD import)**: create/disable users and departments from the company directory; offboarding in the HR system auto-revokes devices. | H | L | `OrganizationDirectory`, `SqliteRevocationService` | Mapping person -> multiple devices needs a `userId` on the device registry (not present today; also needed for multi-device delivery, REL-14 residual). **ADR** |
| S-04 | **People model: user -> many devices** (phone + laptop) with per-user presence, "revoke person" (all devices), and per-user quotas. | H | M | `identity` registry, relay pool | Foundation for S-03 and for multi-device message delivery. **ADR** |
| S-05 | **Offboarding workflow**: one action revokes all of a person's devices, removes group membership, optionally transfers group-admin rights, and writes one audit event with a checklist. | H | S | S-04, revocation, directory | Group-admin transfer needs a rule when the last admin leaves (the "owner-loss" problem in the chat audit). |
| S-06 | **Device approval mode**: invites create *pending* devices that an admin approves before the cert is issued (for high-security orgs); optional auto-approve per department. | M | S | `EnrollmentService`, invites | Adds latency; make it a per-invite flag. |
| S-07 | **Guest / contractor access with automatic expiry** (cert validity capped by end date, scoped to named groups, visible badge in clients). | H | S | invite scope, cert clamp (SEC-18) | Must be impossible to extend silently: extension is an audited admin action. |
| S-08 | **Delegation rotation wizard**: console flow that prepares the new server key, shows the exact `keytool issue-delegation` command, validates the uploaded delegation, and cuts over without dropping sessions. Expiry reminders by email/webhook. | H | S | `DelegationManager`, `keytool` | Today a first delegation needs a restart (known gap); fix as part of this. |
| S-09 | **Break-glass and two-person approval** for the most dangerous actions (replace delegation, create org admin, export audit): require N-of-M admins to confirm via step-up. | M | M | `HubStepUp` | UX cost; offer as an org setting. **ADR** |

### A2. Security, compliance and audit

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| S-10 | **Audit streaming to a SIEM** (syslog/TLS, HTTPS webhook, S3-compatible object drop) with the signed chain head attached so the receiver can verify completeness. | H | M | `AuditLogger` hash chain | Never block the hub on a slow sink: bounded queue + at-least-once. |
| S-11 | **Retention policies**: per department/group TTL for the offline mailbox and for hub-held files; legal hold flag that suspends deletion for named users/groups (honest label: only affects what the hub stores, not devices). | H | M | `MailboxLimits`, `TtlSweeper` | Sealed content means retention acts on envelopes, not content. **ADR** |
| S-12 | **Compliance export bundle**: signed, time-boxed export of audit + directory + device history for an auditor (JSON + CSV + signature + verify script). | M | S | audit export, verify chain | Keep content out unless an explicit "hub can read" mode exists. |
| S-13 | **Security posture dashboard**: devices with soon-expiring certs, devices not seen for N days, outdated app versions (reported at handshake), failed-login heat map, revoked-but-still-connecting attempts. | M | S | handshake `name`/version, metrics | Version reporting must be opt-out-able; no hardware fingerprinting. |
| S-14 | **Per-device remote "lock hub access"** (soft quarantine: relay and directory refused, LAN unaffected) as a milder step than revocation. | M | S | revocation list, relay kick | Clear distinction in UI between quarantine (reversible) and revoke. |
| S-15 | **Key transparency log for sealing keys**: append-only, signed record of every sealing-key change so clients can detect a malicious hub swapping a recipient's key. | H | M | `PUT /device/sealing-key`, audit chain | Needed to make sealed messages hold up against a *hostile hub*; today a hub could serve a substitute key. Clients should pin/notify on key change. **ADR** |
| S-16 | **mTLS-capable listener option** (device cert as the TLS client cert) for sites that want network-layer device authentication in front of the app-layer handshake. | L | M | TLS modes (ADR-114) | Proxies often terminate TLS; document trade-offs. |

### A3. Messaging platform

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| S-17 | **Push wake-up for sleeping phones** (UnifiedPush first, FCM as optional adapter): the hub sends a content-free "you have mail" ping when it queues for an offline device. Directly addresses Transsion/Hiber and Android Doze (EXP-002, HIB-10). | H | M | mailbox enqueue hook, `MailboxStats` | FCM means a third party sees metadata (device token, time); UnifiedPush is self-hostable. Keep it an adapter, off by default. **ADR** |
| S-18 | **Hub file relay**: chunked, resumable, content-addressed storage for large files to offline recipients (per-chunk SHA-256 as the app already does), separate quota and retention from the message mailbox, files sealed per recipient. | H | L | transfer engine chunking, swarm tombstones | Disk and bandwidth cost; per-department file quotas; antivirus hook point (S-24). **ADR** |
| S-19 | **Announcement / broadcast channels** (read-only for members, posted by named admins; optional "must acknowledge" with a server-side ack count). | H | M | group fan-out, directory visibility | Ack tracking is metadata; make it opt-in per announcement. |
| S-20 | **Org-wide group templates** (e.g. every new "Sales" hire auto-joins `#sales`, `#announcements`): auto-join rules by department/role evaluated at enrolment and on department change. | M | S | invite `autoJoin`, directory | Auto-join must still deliver the group secret/roster (CLI-16 dependency). |
| S-21 | **Scheduled and expiring messages server-side** (deliver at a time; auto-delete from the mailbox after N hours) for ops/on-call use. | L | S | mailbox, sweeper | Sender-side scheduling already works offline; only worth it for cross-device guarantees. |
| S-22 | **Presence and "last seen" policy**: org-level switch for none / department-only / everyone; availability schedules (working hours) and Do-Not-Disturb honoured at the hub for push (S-17). | M | S | `PresenceView` | Privacy: default to department-only. |
| S-23 | **Integration API and webhooks**: signed outbound webhooks for events (device enrolled/revoked, join request, delegation expiring) and a service-account principal that can post to announcement channels (bots) with scoped tokens. | M | M | admin API, audit | Bots must be visible as bots and sealed like any member. **ADR** |
| S-24 | **Content scanning hook for hub-held files** (ClamAV via a socket, or pluggable command) for files stored by S-18. | M | S | S-18 | Only possible when files are not sealed to recipients, or when scanning is done at the sender's client; state the trade-off. |

### A4. Operations and reliability

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| S-25 | **Backup / restore tooling**: `hub backup` (SQLite online backup via `VACUUM INTO` + keys excluded or encrypted + manifest + checksum), `hub restore`, scheduled backups, restore drill command that verifies a backup boots and the audit chain verifies. | H | S | `ServerDatabase`, keytool | Backups must never contain the root key (it is offline); delegate key encrypted at rest. |
| S-26 | **High availability**: active/standby with a shared Postgres (or Litestream-style replication) and cross-node session routing; the relay already keys sessions by device so a message bus (NATS/Redis pub/sub) is the missing link. | M | L | `RelayConnectionPool`, `MailboxStore` | Not needed until one node saturates (load numbers owed: HUBB-12). Keep SQLite as the default single-node store. **ADR** |
| S-27 | **Prometheus + ready-made Grafana dashboard and alert rules** (queue depth, oldest message age, handshake failure rate, revocation lag, delegation days left, disk free). | M | S | `/metrics` (management guard) | Ship dashboards as files in the repo. |
| S-28 | **Zero-downtime upgrades**: graceful drain already exists; add a versioned DB migration check (`hub migrate --dry-run`) and a compatibility matrix for clients (min/max protocol version advertised at `/api/v1/hub`). | M | S | `Migrator`, `/hub` | Needed before the first production upgrade. |
| S-29 | **Container and packaging polish**: signed images, SBOM, systemd unit, Helm chart, Windows service wrapper, one-command install script that runs the keytool ceremony interactively. | M | M | Dockerfile (ADR-114) | Docker image was written but not built or scanned yet (open item). |
| S-30 | **Rate-limit and quota management in the console** (per-department mailbox/file quotas, connection caps, abuse blocklist for IPs/devices) with live effect and audit. | M | S | `RelayPolicy`, `MailboxLimits` | Today these are config/code defaults. |
| S-31 | **Multi-tenant mode** (several organisations on one hub, separate roots and databases). | L | L | everything | Probably a different product (managed hosting); only if there is demand. |

Recommended server order: S-25 (backup) -> S-08 (delegation wizard) -> S-04/S-05 (people + offboarding) -> S-17 (push wake-up) ->
S-10 (SIEM) -> S-01 (RBAC) -> S-15 (key transparency) -> S-18 (file relay) -> S-03 (SCIM) -> the rest. Reasoning: backups and the
delegation lifecycle are what an operator will hit first; people/offboarding unlocks most organisational value; push wake-up fixes the
largest real-world reliability gap seen on devices; key transparency is what makes "the hub cannot read your messages" true against a
hostile hub rather than only an honest one.

---

## B. Client-side features (to make the organisation more useful and easy to use)

### B1. Onboarding and account

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-01 | **Guided "Join your organisation" flow**: scan QR / open link -> plain-language verification steps ("checking the hub is really yours: fingerprint matches") -> pick profile name/photo -> auto-join the department's default groups -> a 3-card tour. | H | S | `EnterpriseHub.enroll`, `FlashEnrollDialog`, S-20 | Needs the invite's `autoJoin` scope to actually deliver group access (CLI-16). |
| C-02 | **Organisation badge and trust indicators**: a visible "Acme" verified badge on coworkers, department chips, guest badge with expiry date, and a calm banner when the hub is offline ("messages still go directly on this network"). | H | S | `FlashHubStatusMath`, directory DTOs | Keep jargon out (AGENTS.md section 22). |
| C-03 | **Multiple hubs / organisations** (work + a community hub) with an org switcher and per-org notification and identity separation. | M | L | `EnterpriseHub`, trust store | Today one hub; needs per-hub trust store, relay client and settings. **ADR** |
| C-04 | **"My devices" screen**: list the user's enrolled devices, rename, sign one out remotely, see which is the primary. | M | M | S-04, device registry | Requires the user -> devices model. |
| C-05 | **Seamless re-enrol after revocation or expiry** with one tap when the admin re-approves, keeping groups ("Restore N groups" already exists). | M | S | relink offer (client agent) | Show who/when revoked it; never auto re-enrol silently. |

### B2. Finding people and groups

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-06 | **Company directory with search**: people by name, department, role; presence dot; one-tap chat/call/PTT; "ask admin to add me" for hidden groups. | H | M | `EnterpriseDirectoryClient`, `FlashBrowseOrgGroupsSheet` | Respect directory visibility rules; page results for 5,000 people. |
| C-07 | **Org chart / department browser** (tree), with department channels and key contacts pinned ("IT helpdesk", "HR", "Security"). | M | M | departments API | Needs a `manager`/`title` field on members (optional, admin-set). |
| C-08 | **Group discovery with join states** (Open / Request / Hidden-invite-only) and a clear pending view with cancel; admins see an approvals inbox with badge count in the tab bar. | H | S | join flow (CLI-04), join-requests API | Largely built; polish and notifications for decisions. |
| C-09 | **@mentions and @department / @everyone with admin limits**, mention-only notification mode per group. | H | M | chat repository, group roster | `@everyone` needs a per-group permission to stop abuse. |
| C-10 | **Role-based quick actions**: dept admins get "Add member / approve / post announcement" in the group header; org admins get a link to the console. | M | S | roles in cert | UI only, authorisation is server-side. |

### B3. Communication tools for work

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-11 | **Announcements inbox** (read-only org/department channels; "Acknowledge" button; unread-required marker; admin sees ack counts). | H | M | S-19 | Opt-in tracking; clear privacy copy. |
| C-12 | **Department push-to-talk channels** and **on-call "call the duty person"** (rota in the directory, one tap rings the person currently on duty). | H | M | `:core:ptt`, group calls | Rota data lives server-side (new directory objects). **ADR** |
| C-13 | **Scheduled group calls / stand-ups with join link and calendar (.ics) export**, call rooms that persist (like a standing meeting room). | M | M | group calling | No external calendar sync at first; share .ics. |
| C-14 | **Threads and structured messages**: reply threads, polls, forms ("Shift report" template), checklists, and pinned runbooks per group. | M | L | message model, pinned messages (exists) | Messaging model change; do polls and pins first. |
| C-15 | **Quiet hours / Do-Not-Disturb with exceptions** (VIP contacts, on-call, urgent flag), honoured on device and (via S-22) by the hub. | M | S | notification manager | Android channel behaviour differs by version; test on devices. |
| C-16 | **"Urgent" messages** that break through DND once per N minutes with a confirmation read-receipt to the sender, admin-restrictable to certain roles. | M | S | receipts over relay | Abuse risk: rate-limit per sender. |

### B4. Files, search and knowledge

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-17 | **Shared files library per group** (all files ever shared, with version, owner, size, searchable by name), including files that arrived via the hub relay (S-18) and swarm. | H | M | shared-content viewer (exists, Phase 3), swarm | Storage on devices; offer "free up space" per group. |
| C-18 | **Send big files to a department/group via the hub when peers are offline** (resumable, shows "stored on hub until Tuesday"). | H | L | S-18, transfer engine | Depends on hub file relay. |
| C-19 | **On-device encrypted search** across chats and files (SQLite FTS with an on-device key), "search this group / everyone". | H | M | message DB (Room) | Never send queries to the hub; index size on low-end phones; wipe on revoke. |
| C-20 | **Link and document previews generated on the sender's device** (the hub never fetches URLs). | L | S | attachments | Privacy-first previews. |

### B5. Trust, safety and device behaviour

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-21 | **Safety-number / key-change warnings** for sealed chats: show when a coworker's sealing key changes (new phone, or hub tampering, S-15), with an easy "verify in person" QR. | H | M | `HubSealing`, S-15 | Without this, sealing can be undermined by a hostile hub. Wording must not frighten users on normal phone replacement. |
| C-22 | **Work-data wipe on revoke / unenrol**: remove hub-linked groups' local data, files and sealing keys after confirmation (offer "keep personal LAN chats"). | H | M | unenrol flow, repository | Needs a clear separation between "org data" and personal LAN chats; test thoroughly (data-loss risk). **ADR** |
| C-23 | **Screen-capture and screenshot restrictions** for flagged groups (FLAG_SECURE on Android; desktop best-effort) and a watermark option for sensitive channels. | M | S | Compose host | Desktop cannot truly prevent capture; label honestly. |
| C-24 | **App lock (biometric/PIN) required for the work profile**, auto-lock timer set by org policy, shown in Settings as "Required by your organisation". | M | M | settings, org policy fetch | Org policy distribution: add `GET /api/v1/policy` signed by the delegate. **ADR** |
| C-25 | **Org policy sync** (signed): minimum app version, allowed attachment types/sizes, whether the hub may relay content, retention hints shown to users, mandatory app lock. | M | M | S-28, C-24 | Policies are advisory unless enforced server-side; say so. |

### B6. Resilience and clarity

| # | Feature | Value | Effort | Builds on | Risk / decision |
|---|---|---|---|---|---|
| C-26 | **Delivery path indicator** per message ("sent over your network" / "via hub" / "waiting for the hub") and a "why isn't this delivered?" helper that checks LAN, hub, revocation and cert expiry in plain language. | H | S | `HubStatus`, outbox states, hub acks (CLI-06) | Keep it out of the main bubble; show on tap (Message Info exists). |
| C-27 | **Offline mailbox status**: "3 messages waiting for Priya on the hub, kept until Friday" (from QUEUE_STATE), with a cancel option while queued. | M | S | `FLASH_QUEUE_STATE` | Needs a server cancel-queued-message operation (small). |
| C-28 | **Hub health banner for admins in the app** (delegation expiring, device quota nearly full) with a link to the console. | L | S | role, `/hub` | Admin-only. |
| C-29 | **Desktop-first admin helpers**: drag a CSV of colleagues into the desktop app to create invites in bulk and print onboarding cards (talks to the console API with step-up). | L | M | console APIs | Only if admins do not want to leave the chat app. |

Recommended client order: C-26 (path clarity, tiny and removes the "did it send?" anxiety) -> C-01/C-02 (onboarding trust) ->
C-06 + C-08 (directory + approvals) -> C-21 (key-change warnings, needed with sealing) -> C-22 (data wipe on revoke) -> C-11
(announcements) -> C-19 (on-device search) -> C-12 (department PTT / on-call) -> C-17/C-18 (files, depends on S-18).

---

## C. Cross-cutting dependencies (what unlocks what)

- **User -> devices model (S-04)** unlocks multi-device delivery, offboarding (S-05), SCIM (S-03), "my devices" (C-04).
- **Group roster/secret to remote hub-only members (CLI-16, ADR-123 PROPOSED)** is the blocker for auto-join (S-20, C-01), group
  files over the hub (C-17/C-18) and trustworthy approvals. It needs an owner decision on the group-trust path before the group-facing
  items above are worth building.
- **Key transparency (S-15) + key-change UI (C-21)** are a pair: one without the other leaves sealing weaker than it looks.
- **Push wake-up (S-17)** is the single biggest improvement for phones that freeze the app (Transsion/Hiber, measured in EXP-002 and
  HIB-10); it should be validated on the Infinix handset before anything else on the delivery side.
- **Hub file relay (S-18)** is the most expensive item and the most requested pattern in organisations; scope it only after file
  retention (S-11) and quotas (S-30) exist.

## D. Not recommended (and why)

- Hub-side full-text search or keyword DLP over message content: contradicts the sealed-content design. If compliance requires it, make
  it an explicit "compliance mode" group type where the hub can read, labelled in the UI, decided per organisation by ADR.
- A cloud-hosted default hub or telemetry: contradicts the self-hosted, open-source profile.
- Reusing the admin TOTP as the step-up factor for destructive actions: step-up is intentionally a device-key signature (phishing
  resistant); keep them separate.
- Federating hubs (hub-to-hub relay) before multi-hub clients (C-03) and HA (S-26) exist.
