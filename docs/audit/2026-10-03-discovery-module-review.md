# Discovery module review (2026-10-03)

Scope: `core/discovery` (`CompositeDiscovery`, `NsdTransport`, `JmdnsTransport`, `MulticastTransport`, the Android multicast factory) and how
the two hosts drive it (`DiscoveryEngineHolder`, `DesktopEngine`). Requested by the owner: "review the discovery module for bugs and feature
upgrades and make it better and faster and be honest".

**Nothing here is device-verified.** Every fix is unit-tested and the important ones mutation-checked (the fix switched off, the test fails).
Device checks are `DISC-01`...`DISC-08` in `docs/testing/TEST-BACKLOG.md` section 4t. Tracked as ERROR-100 (OPEN).

## Fixed in code

| ID | Where | Defect | Confidence | Test |
|---|---|---|---|---|
| B1 | `MulticastTransport.restartBrowsing` | The rebind cancelled the announce loop with the receive loops and never restarted it. After any network change (the reason a rebind happens) the device listened but stopped announcing; peers' 60 s lease on it then ran out. NSD / JmDNS masked it where they still saw the device. | Confirmed: test failed before the fix | `restartBrowsing_keepsAnnouncing_onTheFreshSockets` |
| B2 | `CompositeDiscovery.sweep` | A peer held by two transports (NSD + multicast, the normal case) that aged out of both in one sweep produced one `Lost` per directory. | Confirmed: test failed before the fix | `sweep_peerAgedOutOfTwoRadiosAtOnce_emitsOneLost` |
| B3 | `NsdTransport.handleMonitorLost`, `JmdnsTransport.handleServiceRemoved` | The debounced removal of an OLD instance name evicted the device even when it was registered under a new name (NSD name-conflict suffix, restart under a new name). Directory is keyed by device, the loss by service name. | Confirmed by test; the real-world trigger is plausible, not observed | `monitorLost_ofAnOldInstanceName_...`, `removalOfAnOldInstanceName...` |
| B4 | `NsdTransport.browseLoop` | The loop was an untracked coroutine. In ECO a forced restart (or an async start failure) left the old loop parked in its scan/idle phase; it woke after the restart found `browsing` true again and ran a second duty cycle that stopped the new loop's browse. Now one tracked job, replaced on restart. | Found by reading; **no test** (the NSD harness uses an inline dispatcher and no-op sleeps and cannot park a loop) | none |
| B5 | `CompositeDiscovery.applyPresence` | A heartbeat for a known peer only moved a timestamp. If its address or port had changed, `discoveredEndpoints` kept the old one and no `Updated` was emitted (a stable NSD peer sends no `Found` again). Now routed through the same path as a sighting. | Confirmed: test failed before the fix | `presence_carryingAChangedAddress_isPublishedNotSwallowed` |
| B6 | `CompositeDiscovery.startSweeperLocked` | Called without the lock it was named for; two concurrent callers (`startDiscovery`, `restartDiscovery`, `startAll`) could start two sweepers. Now guarded (the monitor is reentrant). | Found by reading; race not reproduced, no test | none |
| B7 | `MulticastTransport.handleDatagram`, `MulticastProtocol.decode` | Unauthenticated UDP: one host cycling device ids grew the lease map, the directory and every list without bound; decoded names were not length-bounded (only encode was). Now 256 peers max (new ids ignored when full, known peers still renew, one warning per episode) and name / model are cut to 64 characters on decode. | Confirmed by test | `aFloodOfDistinctDeviceIds_...`, `aFullTable_...`, `anOversizedNameInAnAnnouncement_...` |
| B11 | `CompositeDiscovery.sweep`, `FlashRadioTransport.presenceGraceMs` | One flat 30 s sweep for every transport, although the multicast beacon renews every ~20 s and promises a 60 s lease. One dropped UDP datagram is a 40 s gap, so a peer reachable only by multicast (the case that transport exists for) flapped Lost then Found. A transport can now declare a longer window (multicast: lease + one sweep interval); nothing can go below the 30 s default. | Confirmed by reasoning and test; the flap itself has not been observed on a device | `sweep_honoursALongerPerTransportGrace_butNeverAShorterOne` |
| B10 | `NsdTransport.mapResolved` | From API 34 a resolved service has a list of addresses and the deprecated `host` is the first one; a dual-stack peer can list a link-local IPv6 address first, which is not dialable. Now prefers routable IPv4. Below API 34 behaviour is unchanged. | **Defensive: no failure was ever reported**; the selection logic is tested, the platform call is not | `NsdHostSelectionTest` |

## Found, not changed

- **B8 (suspected, needs a device): Android 14+ may not hold the multicast lock for Flash's own UDP socket.** `AndroidMulticastSocketFactory` skips
  the lock from API 34 ("the framework manages it"), mirroring the NSD rule. That is plausible for NSD (the platform `NsdService` holds one; see
  `PC0-RUNBOOK.md`) but nothing found in the official documentation says it covers an app-owned `MulticastSocket`. While NSD holds a lock the
  chipset filter is open for everyone, so the multicast transport works by side effect; when NSD is not browsing (ECO idle phase) it may hear
  nothing. Not changed: acquiring the lock again on 34+ reverses a deliberate battery decision, and the right answer is a measurement (`DISC-07`).
- **Interface choice (suspected, privacy):** the Android factory binds every up, non-loopback IPv4 interface, including VPN tunnels and cellular. The
  beacon (device name, model, id) is sent on all of them. The directed-broadcast part already skips point-to-point interfaces; the multicast part does
  not. Left alone: the factory's KDoc records that interface capability flags differ by OEM, and filtering wrongly is silent deafness.
- **B9 (speed):** `startAll`, `aggregate` and `restartDiscovery` start transports one after another, so a slow JmDNS start delays NSD and
  multicast. Parallelising would change start ordering that the host code and tests rely on; not done without a measurement of how long the
  desktop start actually takes.
- **Not touched on purpose:** ECO limits, the stop/start flicker in `stopAdvertising`/`stopDiscovery` (they resume the other half), spoofing of
  identities (discovery is not authenticated; pairing and pinned TLS identities are the security boundary), and the legacy `NsdFlashDiscovery` /
  `LanDiscovery` classes (referenced only by `app/.../LanDiscovery.kt`, `LanController.kt` and their tests).
- `DiscoveryEngineHolder.kt` was not edited: it carried another session's uncommitted changes. Its `reArm` calls `restartDiscovery()` only, which
  after B1 now also restores the multicast announce loop.

## "Faster", honestly

B1 and B5 change how quickly a peer is seen again after a network change or an address change (seconds, instead of "until the next unrelated
event"). Nothing was benchmarked and no speed-up was measured; there is no claim of lower CPU or battery use.
