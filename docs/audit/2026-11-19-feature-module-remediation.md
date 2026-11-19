# Feature and module review remediation

The external 27-module review inspected local `dev` at `b635e4ac`. This record tracks the changes and open findings from that review. Device and deployed-Hub acceptance are in [MODREV-01 through MODREV-05](../testing/TEST-BACKLOG.md#modrev-01--voice-pause-excludes-audio).

## Fixes applied

| Finding | Change |
|---|---|
| Voice pause and desktop audio resources | Android pauses and resumes MediaRecorder. Desktop stops and flushes its capture line and discards reads that cross a pause boundary. The composer follows a successful transition and reports real silence. Desktop recorders and players release their resources on disposal. |
| HR8 transfer continuity | LAN-to-Hub adoption reads one chunk at a time. An existing Hub bitmap is retained and only successful writes are published as newly adopted. A refused Hub-to-LAN launch preserves its Hub row. |
| Desktop database access | Memory trim borrows Room's writer connection and coalesces events. Room opens the encrypted file before the desktop wires repositories. An unreadable store stays on disk. Statement binding cleanup uses the statement lock. |
| Android Quick Settings and services | The tile switches the existing discovery mode to Hidden instead of stopping the engine. Call and PTT services handle a session disappearing during launch. Radio catches refused foreground starts. Notification IDs are distinct. |
| HR10 group key distribution | An authenticated distribution for a future roster stays unacknowledged. The receiver retries deferred Hub deliveries until the directory catches up or message freshness expires. Membership and cryptographic checks still gate acceptance. |
| Announcement requests | The database migrates create-request idempotency to author and audience scope. |
| HR6 replacement console | The form uses each group's API ID. The device detail shows replacement state and invite ID from the detail endpoint. |
| IF-01 required encryption | The relay checks the outer sealed group envelope and its route bindings. It discards refused queue rows during drain and reports them as dropped. |
| IF-02 group policy | Clients fetch policy from the pinned Hub's public endpoint, without bearer credentials. They verify the enrolled server ID and recover from transient disconnects. |
| IF-03 Hub join links | Newly issued links survive ordinary roster changes and expire at the configured deadline. A changed join policy or visibility invalidates them. |
| IF-05 and IF-06 group creation | The client seeds from the confirmed Hub roster and serializes creation requests. Local write failure surfaces the committed Hub result and asks directory sync to reconcile it. Android keeps the draft on rotation. Both hosts guard repeated submission for Hub and personal groups. |
| Outbox and cancellation | A failed drain pass retries after a short delay. Transient state-store writes and selected network and route operations preserve cancellation. |
| HR9 call signaling | Replay checks are serialized and bounded. ICE configuration fetch refreshes credentials once on HTTP 401. |
| Desktop single instance | Lock contention still activates the primary. An I/O failure displays a startup error. Shutdown removes the activation endpoint while the lock is held. |
| Receive finalization | Late start callbacks preserve Verifying. A staged file that cannot be placed remains recoverable and its transfer fails. |
| Keystore and desktop vault | Failed reads preserve encrypted material. An unavailable key is distinguished from an absent key so a transient vault error cannot replace the existing Hub key. |
| Windows native video | Render and staging textures are installed together after both allocations succeed. Exported video entry points validate required pointers. |
| Avatar palette | Both avatar paths avoid integer overflow when selecting a palette color. |

## Review points checked in the current code

- A pending Hub invitation already surfaces the approval status in both shipping hosts. Their join dialogs query `inviteStatusSentence` before opening a conversation. The success result represents the accepted join request.
- The Android media double tap on the right half matches the requested forward gesture. Backward double tap is a separate interaction.
- The sample projects are compile harnesses. The review's roadmap includes native Wi-Fi Direct, managed folders, the extra radio modes and Linux feature-branch integration.

## Open defects and coverage

- **Hub:** A required-policy interval with no mailbox drain can leave older readable rows awaiting retirement. Join links have no per-link revocation, use limit or independent key lifecycle. The announcement admin's concurrent delete, publication permissions and preview findings remain open. Call eligibility and group-key retention need their own design and tests.
- **Transfers and persistence:** Desktop still performs Hub-to-LAN adoption while the receive pipeline is locked. Checkpoint cleanup, Android database sidecar recovery, sink-factory exception handling and remaining suspend cancellation paths need follow-up. Desktop now detects failed database opens and preserves the file. A desktop repair workflow remains open.
- **Calls, PTT and radio:** Mixed-route group ICE selection, renewal during a call and the PTT capture and session lifecycle findings remain open. The radio ACK ordering, restart deduplication and reconnect behavior also remain open.
- **Media and UI:** Media viewer restoration, mute wiring, native first-frame edge cases, desktop playback speed and Linux/macOS inline playback remain open. Remaining notification actions, UI semantics, settings, packaging, prototype isolation and baseline coverage are listed in the original review.
- **Secondary module findings:** The original review remains the reference for the other common, discovery, network, radio, theme, benchmark and vendored WebRTC items.

## Verification

Verified against `aced6579` on `dev`. The final Gradle results contain 3,644 passes, zero failures and four skips. Android debug assembly and lint, 87 Admin UI tests, and the Windows native playback smoke passed. Physical device, installed desktop and live Hub checks are pending. [Verification notes](../../logs/progress.md).
