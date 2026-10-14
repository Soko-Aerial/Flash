# Stream S1-org-trust: Device Test Backlog Additions

Canonical backlog entries owed for physical device testing per Plan B §4 / §11:

## ORGT-01 — Offline 1:1 chat with unpaired colleague after prior sync
- **Preconditions:** Devices A and B are enrolled in the same organization and have performed at least one successful directory sync. Both devices connect to a local Wi-Fi router with the WAN/internet cable physically disconnected. Devices A and B have never paired.
- **Action:** Device A opens the Nearby screen or Contacts/Directory list, selects Colleague B (labeled "Colleague"), and sends a 1:1 text message.
- **Expected:** Message delivers directly over LAN TLS without reaching the hub; Device B displays message and sender with "Colleague" chip.
- **Status:** OWED (Physical device test).

## ORGT-02 — Lease expiry via device clock rollback and forward advancement
- **Preconditions:** Device A is enrolled and synced with 30-day offline grace period.
- **Action:**
  1. Advance Device A system clock by 31 days while offline.
  2. Roll back Device A system clock by 10 minutes (exceeding 5-minute skew tolerance).
- **Expected:** In both cases, `OrgPeerResolver` transitions to `OrgLease.Expired` (`reason = TIME` / `CLOCK`), emits `org.lease.expired`, and disables ORG chat with honest notice: "Reconnect to your company network to chat with colleagues who are not paired with you" or "Your device clock looks wrong". Paired chats continue to work.
- **Status:** OWED (Physical device test).

## ORGT-03 — TOFU spoof attempt against colleague device ID
- **Preconditions:** Device A connects to LAN. Rogue Device C connects and advertises Device B's `deviceId` with an uncertified ephemeral key, creating a TOFU pin in Device A's store. Legitimate Device B then connects with the certified statement key.
- **Action:** Device B initiates handshake with Device A.
- **Expected:** Device A replaces the stale TOFU pin with the authoritative statement fingerprint (`org.pin.replaced`), closes the rogue session, and admits Device B as a verified Colleague.
- **Status:** OWED (Physical device test).

## ORGT-04 — Revoked colleague stays chat-able offline until sync, refused after
- **Preconditions:** Devices A and B are colleagues chatting offline. Hub admin revokes Device B.
- **Action:**
  1. A and B continue chatting while router internet remains disconnected.
  2. Router internet is reconnected; Device A syncs directory with hub.
  3. Device A attempts to chat with Device B.
- **Expected:** While offline, chat continues until lease expiry. After Device A syncs tombstone, subsequent messages to B are blocked with: "This person is no longer part of your organisation."
- **Status:** OWED (Physical device test).

## ORGT-05 — Hub database restore from backup (version rollback)
- **Preconditions:** Device A has synced directory version 12. Hub database is restored from backup to version 8.
- **Action:** Device A performs directory sync against restored hub.
- **Expected:** Client detects monotonic version drop (`epoch` reset or version < stored), discards local snapshot, and executes a full resync without data corruption.
- **Status:** OWED (Physical device test).

## ORGT-08 — Desktop <-> phone offline colleague chat
- **Preconditions:** Desktop client and Android phone enrolled in the same org, connected to same offline Wi-Fi subnet.
- **Action:** Send 1:1 chat and delivery receipt between desktop and phone.
- **Expected:** Interoperability verified; both hosts show "Colleague" chip and message delivered.
- **Status:** OWED (Physical device test).

## ORGT-09 — PTT refused for an ORG colleague
- **Preconditions:** Device A receives connection from unpaired Colleague B.
- **Action:** Colleague B attempts to initiate Push-to-Talk audio stream to Device A.
- **Expected:** `PeerTrustPolicy.mayPushToTalk` denies frame; PTT audio does not play; PTT UI control remains disabled for unpaired colleagues.
- **Status:** OWED (Physical device test).

## ORGT-10 — Incoming call ringing from an ORG colleague
- **Preconditions:** Unpaired Colleague B calls Device A over LAN.
- **Action:** Device B rings Device A.
- **Expected:** Device A incoming call screen rings, displaying caller name with "Colleague" badge; auto-answer is strictly rejected; answering establishes WebRTC session.
- **Status:** OWED (Physical device test).

## ORGT-13 — ECO mode wake for ORG send
- **Preconditions:** Device A in battery saver / ECO mode with outbox message queued for Colleague B.
- **Action:** `RememberedRoutes` and connection planner evaluate candidates.
- **Expected:** Colleague B is retained in active dial candidate set (capped at 8) and dialed on demand to flush outbox.
- **Status:** OWED (Physical device test).

## ORGT-14 — Device identity key regenerated
- **Preconditions:** Device A undergoes Android Keystore key regeneration (`IdentityKeyAction.REGENERATE`).
- **Action:** Device A restarts and connects to LAN.
- **Expected:** Client detects SPKI mismatch against enrolled statement, enters `IDENTITY_MISMATCH` degraded state, displays: "Your device identity changed. Rejoin your organisation to chat with colleagues again." No silent crash or silent un-enrolment.
- **Status:** OWED (Physical device test).
