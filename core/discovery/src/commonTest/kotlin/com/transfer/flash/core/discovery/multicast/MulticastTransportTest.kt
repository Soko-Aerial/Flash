// FlashProtocol/FlashTextFraming are @FlashInternalApi; this suite is inside the owning module.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.discovery.multicast

import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashProtocol
import com.transfer.flash.core.discovery.core.DiscoveryModePolicy
import com.transfer.flash.core.discovery.core.FlashAdvertisedIdentity
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.discovery.core.FlashTransportEvent
import com.transfer.flash.core.discovery.core.StandardEndpointDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Behavioural suite for [MulticastTransport], driven through a fake [MulticastSocketFactory] so it
 * runs everywhere with no network and no real clock.
 *
 * The two properties this suite exists to pin are the ones the DNS-SD pair cannot provide:
 *
 * 1. **A peer's address and identity arrive together, in the datagram.** There is no resolution step
 *    whose failure leaves a peer with a correct address and no `device_id` — the state that left the
 *    desktop unable to dial the phone on 2026-09-14 (`txtKeys=[] txtBytes=1`).
 * 2. **Silence is evidence.** A peer that stops announcing is gone after its lease, because the
 *    transport's own protocol requires it to keep announcing. That is the ghost-peer fix: no goodbye
 *    is needed from a process that died, and no cache TTL has to expire.
 *
 * Loops are driven by direct calls to the internal seams ([MulticastTransport.handleDatagram],
 * [MulticastTransport.sweepLeases], [MulticastTransport.announceNow]) rather than by waiting on
 * timers, so every case is synchronous and deterministic.
 */
class MulticastTransportTest {

    // -- Fixtures --------------------------------------------------------------

    private class FakeBinding(
        override val label: String,
        override val broadcastTargets: List<String> = emptyList(),
        private val broadcastResult: Boolean = true,
    ) : MulticastSocketBinding {
        val sent = mutableListOf<ByteArray>()
        val broadcastSent = mutableListOf<ByteArray>()

        /** Every send in the order it happened, "multicast" or "broadcast", to pin that broadcast follows. */
        val order = mutableListOf<String>()
        var closed = false
        var nextDatagram: MulticastDatagram? = null

        override fun send(payload: ByteArray): Boolean {
            sent += payload
            order += "multicast"
            return true
        }

        override fun sendBroadcast(payload: ByteArray): Boolean {
            broadcastSent += payload
            order += "broadcast"
            return broadcastResult
        }

        override fun receive(timeoutMs: Int): MulticastDatagram? = nextDatagram.also { nextDatagram = null }

        override fun close() {
            closed = true
        }
    }

    private class FakeFactory(private var bindingCount: Int = 1) : MulticastSocketFactory {
        var bindCalls = 0
        var closeCalls = 0
        val bindings = mutableListOf<FakeBinding>()

        /** Interfaces that appear only from the NEXT bind onward (Wi-Fi coming up). */
        var nextBindingCount: Int? = null

        /** What binding number `i` reports as its broadcast targets, and whether its broadcast send succeeds. */
        var broadcastTargetsFor: (Int) -> List<String> = { emptyList() }
        var broadcastResultFor: (Int) -> Boolean = { true }

        override fun bind(group: String, port: Int): List<MulticastSocketBinding> {
            bindCalls += 1
            bindings.clear()
            nextBindingCount?.let { bindingCount = it; nextBindingCount = null }
            repeat(bindingCount) {
                bindings += FakeBinding("test$it", broadcastTargetsFor(it), broadcastResultFor(it))
            }
            return bindings.toList()
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private val peerId = FlashDeviceId("0a3bd2e8-b713-4aae-9eff-48bb17a901cf")
    private val selfId = FlashDeviceId("ff485975-33bd-45e3-87a3-c900e249a305")

    private fun peerIdentity(
        id: FlashDeviceId = peerId,
        name: String = "Prince Ayaata",
        proto: Int = FlashProtocol.VERSION,
    ) = FlashAdvertisedIdentity(
        deviceId = id,
        friendlyName = name,
        deviceModel = "Pixel 8",
        protocolVersion = proto,
    )

    private val selfIdentity = peerIdentity(id = selfId, name = "Flash Desktop")

    private fun datagramOf(
        identity: FlashAdvertisedIdentity,
        port: Int = 45822,
        source: String = "192.168.0.188",
    ) = MulticastDatagram(
        payload = MulticastProtocol.encode(identity, port).encodeToByteArray(),
        sourceAddress = source,
    )

    /**
     * Runs [block] against a started transport whose loops are parked on the test scheduler, with
     * events collected eagerly. The collector is launched on `Dispatchers.Unconfined` because a
     * SharedFlow with no subscriber drops its emissions, and the transport must never be blamed for
     * an event a test forgot to subscribe to.
     */
    private fun withTransport(
        factory: FakeFactory = FakeFactory(),
        nowMs: () -> Long = { 1_000L },
        mode: FlashDiscoveryMode = FlashDiscoveryMode.STANDARD,
        broadcastEnabled: Boolean = true,
        info: MutableList<String> = mutableListOf(),
        warnings: MutableList<String> = mutableListOf(),
        block: suspend (MulticastTransport, FakeFactory, MutableList<FlashTransportEvent>) -> Unit,
    ) = runTest {
        val transport = MulticastTransport(
            socketFactory = factory,
            directory = StandardEndpointDirectory(),
            dispatcher = StandardTestDispatcher(testScheduler),
            timeSourceMs = nowMs,
            announceIntervalMs = 20_000L,
            peerLeaseMs = 60_000L,
            sweepIntervalMs = 5_000L,
            broadcastEnabled = broadcastEnabled,
            logInfo = { info += it },
            logWarn = { message, _ -> warnings += message },
        )
        val seen = mutableListOf<FlashTransportEvent>()
        val collector = launch(Dispatchers.Unconfined) { transport.events.collect { seen += it } }
        try {
            transport.startBrowsing()
            if (mode != FlashDiscoveryMode.STANDARD) {
                transport.setMode(DiscoveryModePolicy.forMode(mode))
            }
            block(transport, factory, seen)
        } finally {
            transport.stop()
            collector.cancel()
        }
    }

    private fun MutableList<FlashTransportEvent>.found() =
        filterIsInstance<FlashTransportEvent.Found>().map { it.endpoint }

    private fun MutableList<FlashTransportEvent>.lost() =
        filterIsInstance<FlashTransportEvent.Lost>().map { it.deviceId }

    // -- Discovery: address and identity arrive together ------------------------

    @Test
    fun announcement_yieldsFound_carryingTheSourceAddressAndTheAnnouncedPort() =
        withTransport { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity(), port = 45822, source = "192.168.0.188"))

            val found = seen.found().single()
            // The address is the datagram's SOURCE — the property the DNS-SD path cannot offer,
            // because there the address comes from a record that can be stale while the identity
            // comes from a record that can be missing.
            assertEquals("192.168.0.188", found.hostAddress)
            assertEquals(45822, found.port)
            assertEquals(peerId, found.deviceId)
            assertEquals("Prince Ayaata", found.friendlyName)
        }

    @Test
    fun announcement_withoutADialableSourceAddress_isIgnored() =
        withTransport { transport, _, seen ->
            // An endpoint with no address would replace a good one with something undialable.
            transport.handleDatagram(datagramOf(peerIdentity(), source = ""))
            assertTrue(seen.found().isEmpty())
        }

    @Test
    fun ownAnnouncement_isFilteredByIdentity_notByAddress() =
        withTransport { transport, _, seen ->
            // Advertising is what puts our identity on the wire, and therefore what makes our own
            // datagram recognisable when multicast loopback hands it back to us.
            transport.startAdvertising(45822, selfIdentity)
            // The source is deliberately NOT our address: a multi-homed host announces from several,
            // so an address-based filter would drop our own datagram on one interface and pass it on
            // another — identity is the only filter that is correct everywhere.
            transport.handleDatagram(datagramOf(selfIdentity, source = "192.168.0.126"))
            assertTrue(seen.found().isEmpty())
        }

    @Test
    fun peerWithAnIncompatibleProtocol_isDropped() =
        withTransport { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity(proto = FlashProtocol.VERSION + 99)))
            assertTrue(seen.found().isEmpty(), "an incompatible peer must not enter the list")
        }

    @Test
    fun peerThatMovesToANewAddress_emitsUpdated() =
        withTransport { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity(), source = "192.168.0.188"))
            transport.handleDatagram(datagramOf(peerIdentity(), source = "192.168.0.199"))

            assertEquals(1, seen.found().size, "the same device must be Found once")
            val updated = seen.filterIsInstance<FlashTransportEvent.Updated>().single()
            assertEquals("192.168.0.199", updated.endpoint.hostAddress)
        }

    // -- Liveness: the lease is the whole point ---------------------------------

    @Test
    fun reAnnouncementWithNoFieldChange_emitsPresence_notSilence() =
        withTransport { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity()))
            transport.handleDatagram(datagramOf(peerIdentity()))

            // The mandatory invariant of FlashRadioTransport: a consumer that ages peers on a TTL
            // reads deduped silence as death. This transport is the one place where the heartbeat is
            // backed by a fresh datagram rather than by the absence of a goodbye.
            assertEquals(1, seen.found().size)
            assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Presence>().size)
        }

    @Test
    fun peerThatStopsAnnouncing_isLostOnceItsLeaseExpires() {
        var now = 1_000L
        withTransport(nowMs = { now }) { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity()))

            // Still inside the lease: three missed announcements is the tolerance, so one missed
            // datagram (or a screen-off gap) must never evict a live peer.
            now += 59_000L
            transport.sweepLeases(now)
            assertTrue(seen.lost().isEmpty(), "a peer inside its lease must survive a sweep")

            // Past it: the peer is gone, without anyone having sent a goodbye.
            now += 2_000L
            transport.sweepLeases(now)
            assertEquals(listOf(peerId), seen.lost())
        }
    }

    @Test
    fun reAnnouncementWithinTheLease_keepsThePeerAlive() {
        var now = 1_000L
        withTransport(nowMs = { now }) { transport, _, seen ->
            transport.handleDatagram(datagramOf(peerIdentity()))
            repeat(5) {
                now += 30_000L
                // A live peer keeps announcing, which is exactly what a dead one cannot do.
                transport.handleDatagram(datagramOf(peerIdentity()))
                transport.sweepLeases(now)
            }
            assertTrue(seen.lost().isEmpty(), "a peer that keeps announcing must never expire")
            assertEquals(1, seen.found().size)
        }
    }

    // -- Hostile input -----------------------------------------------------------

    @Test
    fun aFloodOfDistinctDeviceIds_isBounded_andKnownPeersKeepRenewing() =
        withTransport(warnings = mutableListOf()) { transport, _, seen ->
            fun id(n: Int) = FlashDeviceId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

            for (n in 1..(MulticastTransport.DEFAULT_MAX_PEERS + 50)) {
                transport.handleDatagram(datagramOf(peerIdentity(id = id(n), name = "Spoof $n")))
            }
            assertEquals(MulticastTransport.DEFAULT_MAX_PEERS, seen.found().size)

            // A peer already in the table is unaffected by the full table.
            seen.clear()
            transport.handleDatagram(datagramOf(peerIdentity(id = id(1), name = "Spoof 1")))
            assertEquals(1, seen.filterIsInstance<FlashTransportEvent.Presence>().size)
        }

    @Test
    fun aFullTable_reportsItselfOnce_notPerDatagram() {
        val warnings = mutableListOf<String>()
        withTransport(warnings = warnings) { transport, _, _ ->
            fun id(n: Int) = FlashDeviceId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
            for (n in 1..(MulticastTransport.DEFAULT_MAX_PEERS + 20)) {
                transport.handleDatagram(datagramOf(peerIdentity(id = id(n))))
            }
            assertEquals(1, warnings.count { "peer table full" in it }, "warnings: $warnings")
        }
    }

    @Test
    fun anOversizedNameInAnAnnouncement_isTruncatedOnTheWayIn() =
        withTransport { transport, _, seen ->
            // Built by hand: a hostile sender does not go through MulticastProtocol.encode's bound.
            val text = MulticastProtocol.encode(peerIdentity(), 45822)
                .replace("Prince%20Ayaata", "A".repeat(900))
            assertTrue("A".repeat(900) in text, "fixture did not inject the oversized name: $text")
            transport.handleDatagram(MulticastDatagram(text.encodeToByteArray(), "192.168.0.188"))

            val found = seen.found().single()
            assertTrue(found.friendlyName.length <= MulticastProtocol.MAX_TEXT_FIELD_CHARS, found.friendlyName)
        }

    // -- Announcing ------------------------------------------------------------

    @Test
    fun announceNow_sendsTheAnnouncementOnEveryBoundSocket() {
        val factory = FakeFactory(bindingCount = 3)
        withTransport(factory = factory) { transport, _, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            // One socket per interface, so every interface carries the announcement; a wildcard
            // socket would have reached whichever one the routing table happened to pick.
            assertEquals(3, factory.bindings.size)
            factory.bindings.forEach { binding ->
                assertTrue(binding.sent.isNotEmpty(), "no announcement went out on ${binding.label}")
            }
            val decoded = assertNotNull(
                MulticastProtocol.decode(factory.bindings.first().sent.last().decodeToString()),
            )
            assertEquals(selfId, decoded.identity.deviceId)
            assertEquals(45822, decoded.port)
        }
    }

    // -- DR2: the directed-broadcast beacon ---------------------------------------

    @Test
    fun announceNow_alsoBroadcastsTheSamePayload_afterTheMulticastSend() {
        val factory = FakeFactory(bindingCount = 2).apply {
            broadcastTargetsFor = { listOf("192.168.1.255") }
        }
        withTransport(factory = factory) { transport, _, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            factory.bindings.forEach { binding ->
                // One announcement, two destinations. The same bytes: DR2 changes no protocol.
                assertEquals(1, binding.sent.size)
                assertEquals(1, binding.broadcastSent.size, "no broadcast went out on ${binding.label}")
                assertTrue(binding.sent.single().contentEquals(binding.broadcastSent.single()))
                // Multicast first: a broadcast problem must never be able to delay or replace it.
                assertEquals(listOf("multicast", "broadcast"), binding.order)
            }
        }
    }

    @Test
    fun aFailedBroadcast_neverStopsOrSilencesTheMulticastAnnounce_andIsNotAWarning() {
        val factory = FakeFactory(bindingCount = 2).apply {
            // Interface 0 has no broadcast address, which is normal (a VPN, a /32): sendBroadcast is false.
            broadcastResultFor = { it != 0 }
        }
        val warnings = mutableListOf<String>()
        withTransport(factory = factory, warnings = warnings) { transport, _, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            factory.bindings.forEach { assertEquals(1, it.sent.size, "multicast missing on ${it.label}") }
            assertTrue(warnings.isEmpty(), "a false broadcast is normal, not a warning: $warnings")
        }
    }

    @Test
    fun ghostMode_doesNotBroadcastEither() =
        withTransport(mode = FlashDiscoveryMode.GHOST) { transport, factory, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            assertTrue(
                factory.bindings.all { it.broadcastSent.isEmpty() && it.sent.isEmpty() },
                "GHOST must be radio-silent on the broadcast path too",
            )
        }

    @Test
    fun broadcastEnabledFalse_sendsMulticastOnly_andSaysNothingAboutBroadcast() {
        val info = mutableListOf<String>()
        withTransport(broadcastEnabled = false, info = info) { transport, factory, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            assertTrue(factory.bindings.all { it.broadcastSent.isEmpty() })
            assertTrue(factory.bindings.all { it.sent.size == 1 })
            assertTrue(info.none { "Broadcast beacon" in it }, "nothing to report when the beacon is off: $info")
        }
    }

    @Test
    fun binding_logsWhereTheBeaconGoes_perInterface_andSaysNoneWhenThereIsNoAddress() {
        val factory = FakeFactory(bindingCount = 2).apply {
            broadcastTargetsFor = { if (it == 0) listOf("192.168.1.255", "10.0.0.255") else emptyList() }
        }
        val info = mutableListOf<String>()
        withTransport(factory = factory, info = info) { _, _, _ ->
            val line = info.single { "Broadcast beacon" in it }
            assertEquals("Broadcast beacon to test0=192.168.1.255+10.0.0.255, test1=none (port 45823)", line)
        }
    }

    @Test
    fun peersWeAlreadyKnow_doNotTriggerAReply() {
        val factory = FakeFactory()
        withTransport(factory = factory) { transport, _, _ ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()
            val sentBefore = factory.bindings.first().sent.size

            // An announcement from a device we know is the normal 20-second cadence arriving; it
            // must not make us answer. Answering every announcement is how a two-device LAN turns
            // into a reply storm.
            transport.handleDatagram(datagramOf(peerIdentity()))
            transport.handleDatagram(datagramOf(peerIdentity()))
            transport.announceNow() // the reply path, if it ran, would have queued here

            assertEquals(
                sentBefore + 1,
                factory.bindings.first().sent.size,
                "only the explicit announce should have been sent",
            )
        }
    }

    @Test
    fun ghostMode_suppressesAnnouncing_butKeepsListening() =
        withTransport(mode = FlashDiscoveryMode.GHOST) { transport, factory, seen ->
            transport.startAdvertising(45822, selfIdentity)
            transport.announceNow()

            assertTrue(
                factory.bindings.all { it.sent.isEmpty() },
                "GHOST must be radio-silent on the announce path",
            )
            // ...but a ghost that could not see anyone would be useless as a receiver.
            transport.handleDatagram(datagramOf(peerIdentity()))
            assertEquals(1, seen.found().size)
        }

    // -- Lifecycle -------------------------------------------------------------

    @Test
    fun stop_releasesEverySocketAndTheFactory() {
        val factory = FakeFactory(bindingCount = 2)
        withTransport(factory = factory) { transport, _, _ ->
            // `withTransport` stops the transport itself; assert from inside by stopping here.
            transport.stop()
            assertTrue(factory.bindings.all { it.closed }, "every bound socket must be closed")
            assertEquals(1, factory.closeCalls, "the factory's process-wide resource must be released")
        }
    }

    @Test
    fun restartBrowsing_rebindsInsteadOfReusingDeadSockets() {
        val factory = FakeFactory()
        withTransport(factory = factory) { transport, _, _ ->
            assertEquals(1, factory.bindCalls)
            // A socket on an interface that went away keeps reporting itself healthy, so re-arming a
            // loop on it changes nothing: recovery has to close and bind again.
            transport.restartBrowsing()
            assertEquals(2, factory.bindCalls)
            assertTrue(factory.bindings.none { it.closed }, "the rebind must leave live sockets")
        }
    }

    @Test
    fun restartBrowsing_keepsAnnouncing_onTheFreshSockets() {
        // B1: the rebind used to cancel the announce loop together with the receive loops and never
        // start it again, so every network change (the reason a rebind happens at all) left this
        // device listening but silent. Peers' leases on us then ran out after 60 s.
        val factory = FakeFactory()
        withTransport(factory = factory) { transport, _, _ ->
            transport.startAdvertising(45822, selfIdentity)
            kotlinx.coroutines.delay(2_000)
            assertTrue(factory.bindings.first().sent.isNotEmpty(), "precondition: announcing before the rebind")

            transport.restartBrowsing()
            kotlinx.coroutines.delay(2_000)
            assertTrue(
                factory.bindings.first().sent.isNotEmpty(),
                "no announcement went out on the rebound socket",
            )
            val sentAfterBurst = factory.bindings.first().sent.size

            kotlinx.coroutines.delay(60_000)
            assertTrue(
                factory.bindings.first().sent.size > sentAfterBurst,
                "the periodic cadence must survive the rebind",
            )
        }
    }

    @Test
    fun restartBrowsing_whileNotAdvertising_staysSilent() {
        val factory = FakeFactory()
        withTransport(factory = factory) { transport, _, _ ->
            transport.restartBrowsing()
            kotlinx.coroutines.delay(30_000)
            assertTrue(factory.bindings.all { it.sent.isEmpty() }, "a listener-only transport must not announce")
        }
    }

    @Test
    fun aHostWithNoUsableInterface_startsAnyway_andBindsOnceOneAppears() {
        // The additivity rule, and the reason this test replaces one that asserted a hard failure:
        // `CompositeDiscovery.startAll` reports failure if ANY transport fails, and both hosts turn
        // that into "refuse to boot". Wi-Fi off / cellular-only at boot is an ordinary state, so this
        // transport must degrade rather than fail — and repair itself when an interface appears.
        val factory = FakeFactory(bindingCount = 0)
        withTransport(factory = factory) { transport, _, seen ->
            assertEquals(1, factory.bindCalls, "startBrowsing must have attempted the bind")
            assertEquals(0, factory.bindings.size, "no interface to bind on")
            // The multicast lock the factory may have taken while trying must not be left held.
            assertEquals(1, factory.closeCalls)

            // Wi-Fi comes up. Nobody calls startBrowsing again — the transport owns the repair.
            factory.nextBindingCount = 2
            transport.recoverBindingsIfNeeded()

            assertEquals(2, factory.bindCalls)
            assertEquals(2, factory.bindings.size)
            // ...and it now actually hears peers, which is what the repair is for.
            transport.handleDatagram(datagramOf(peerIdentity()))
            assertEquals(1, seen.found().size)
        }
    }

    @Test
    fun aDegradedStart_reportsItselfOnce_ratherThanOncePerRetry() {
        val factory = FakeFactory(bindingCount = 0)
        withTransport(factory = factory) { transport, _, seen ->
            // Three sweeps of a still-interface-less host: one state message, not three. A log line
            // and a state event per retry is how a diagnostic surface becomes unreadable.
            repeat(3) { transport.recoverBindingsIfNeeded() }
            val degraded = seen.filterIsInstance<FlashTransportEvent.StateChanged>()
                .filter { "No usable interface" in it.message }
            assertEquals(1, degraded.size, "degradation must be reported once per episode")
            assertEquals(4, factory.bindCalls, "but every sweep must still re-attempt the bind")
        }
    }
}
