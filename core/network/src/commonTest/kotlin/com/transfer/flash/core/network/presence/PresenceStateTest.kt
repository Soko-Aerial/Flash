package com.transfer.flash.core.network.presence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * PC4's protocol rules on a simulated mesh: every frame is encoded, decoded and delivered, so the
 * wire format is exercised along with the state machine. Plan §7 PC4 exit tests: a Ghost device is
 * never leaked, a non-mutual contact is never leaked, stale reports expire, and a forged tip only
 * costs a failed dial.
 */
class PresenceStateTest {

    private val config = PresenceConfig.STANDARD

    private inner class Node(val id: String) {
        var saltCounter = 0
        val state = PresenceState(id, config, TestHash::digest) {
            ByteArray(PresenceCodec.SALT_BYTES) { i -> (id.hashCode() + i + saltCounter * 31).toByte() }.also { saltCounter++ }
        }
        var ghost = false
        var sessions: Set<String> = emptySet()
        var live: Set<String>? = null
        var seen: Map<String, PresenceEndpoint?> = emptyMap()
        var trusted: Set<String> = emptySet()
        var pinned: Set<String>? = null
        var rosters: List<Set<String>> = emptyList()

        /** Speaks presence at all; false models an app version from before PC4. */
        var modern = true

        fun view() = PresenceLocalView(
            ghost = ghost,
            sessions = sessions.associateWith { token(id, it) },
            live = live ?: sessions,
            seen = seen,
            trusted = trusted,
            pinned = pinned ?: trusted,
            rosters = rosters,
        )
    }

    private val tokens = HashMap<String, Any>()
    private fun token(a: String, b: String): Any = tokens.getOrPut(listOf(a, b).sorted().joinToString("|")) { Any() }

    /** Drops the session object for a pair, so the next step sees a new session. */
    private fun reconnect(a: String, b: String) {
        tokens.remove(listOf(a, b).sorted().joinToString("|"))
    }

    /** Every frame delivered, as (from, to, decoded frame). */
    private val wire = ArrayList<Triple<String, String, PresenceFrame>>()

    private fun connect(x: Node, y: Node) {
        x.sessions = x.sessions + y.id
        y.sessions = y.sessions + x.id
    }

    private fun disconnect(x: Node, y: Node) {
        x.sessions = x.sessions - y.id
        y.sessions = y.sessions - x.id
        reconnect(x.id, y.id)
    }

    /** Steps every node and delivers frames until the mesh is quiet. */
    private fun settle(now: Long, vararg nodes: Node) {
        val byId = nodes.associateBy { it.id }
        repeat(20) {
            var sentAny = false
            for (node in nodes) {
                if (!node.modern) continue
                for (out in node.state.step(now, node.view()).out) {
                    val frame = PresenceCodec.decode(PresenceCodec.encode(out.frame))!!
                    wire += Triple(node.id, out.peerId, frame)
                    sentAny = true
                    val target = byId[out.peerId] ?: continue
                    if (target.modern && node.id in target.sessions) {
                        target.state.onFrame(now, node.id, frame, target.view())
                    }
                }
            }
            if (!sentAny) return
        }
    }

    private fun reportedTo(to: String): Set<String> = wire
        .filter { it.second == to }
        .mapNotNull { it.third as? PresenceFrame.Report }
        .flatMap { r -> r.entries.filter { it.state != PresenceReportState.Gone }.map { it.deviceId } }
        .toSet()

    /** A, B and C: A is connected to both; B and C are not connected to each other. */
    private fun triangle(): Triple<Node, Node, Node> {
        val a = Node("A")
        val b = Node("B")
        val c = Node("C")
        connect(a, b)
        connect(a, c)
        a.trusted = setOf("B", "C")
        b.trusted = setOf("A", "C")
        c.trusted = setOf("A", "B")
        return Triple(a, b, c)
    }

    @Test
    fun aMutualContactIsReportedThroughTheCommonPeer() {
        val (a, b, c) = triangle()
        a.seen = mapOf("C" to PresenceEndpoint("192.168.1.30", 45822))
        settle(0, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(0, b.view()))
        val entry = wire.filter { it.first == "A" && it.second == "B" }
            .mapNotNull { it.third as? PresenceFrame.Report }.flatMap { it.entries }.single { it.deviceId == "C" }
        assertEquals(PresenceReportState.Connected, entry.state)
        assertEquals(1, entry.hops)
        assertEquals(PresenceEndpoint("192.168.1.30", 45822), entry.endpoint)
    }

    @Test
    fun aNonMutualContactIsNeverLeaked() {
        val (a, b, c) = triangle()
        b.trusted = setOf("A") // B has never met C
        settle(0, a, b, c)
        settle(config.refreshMs, a, b, c)
        assertFalse("C" in reportedTo("B"))
        assertTrue(b.state.reachable(config.refreshMs, b.view()).isEmpty())
    }

    @Test
    fun aGhostDeviceIsNeverLeaked() {
        val (a, b, c) = triangle()
        c.ghost = true
        settle(0, a, b, c)
        settle(config.refreshMs, a, b, c)
        assertFalse("C" in reportedTo("B"))
        // Ghost reports nothing about anyone, and sends no salt to be asked with.
        assertTrue(wire.none { it.first == "C" && it.third is PresenceFrame.Report })
        assertTrue(wire.filter { it.first == "C" }.all { (it.third as? PresenceFrame.Hello)?.salt == null })
    }

    @Test
    fun switchingToGhostWithdrawsTheDeviceFromEveryReport() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(0, b.view()))
        c.ghost = true
        settle(2_000, a, b, c)
        assertTrue(b.state.reachable(2_000, b.view()).isEmpty())
    }

    @Test
    fun aGhostDeviceStillReceivesPresence() {
        val (a, b, c) = triangle()
        b.ghost = true
        settle(0, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(0, b.view()))
    }

    @Test
    fun aDeviceThatNeverSaidShareIsNotReported() {
        val (a, b, c) = triangle()
        c.modern = false // an old client: no presence hello, so no opt-in
        settle(0, a, b, c)
        assertFalse("C" in reportedTo("B"))
    }

    @Test
    fun groupFellowsLearnAboutEachOtherWithoutBeingPaired() {
        val (a, b, c) = triangle()
        val group = setOf("A", "B", "C")
        a.trusted = emptySet()
        b.trusted = emptySet()
        c.trusted = emptySet()
        a.rosters = listOf(group)
        b.rosters = listOf(group)
        c.rosters = listOf(group)
        settle(0, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(0, b.view()))
    }

    @Test
    fun aGroupRosterTheReceiverIsNotInSharesNothing() {
        val (a, b, c) = triangle()
        b.trusted = setOf("A")
        a.rosters = listOf(setOf("A", "C", "D")) // B is not in this group
        settle(0, a, b, c)
        assertFalse("C" in reportedTo("B"))
    }

    @Test
    fun staleReportsExpire() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(0, b.view()))
        // A goes silent (its app froze): nothing refreshes B's copy.
        a.modern = false
        val later = config.maxAgeMs + 1
        b.state.step(later, b.view())
        assertTrue(b.state.reachable(later, b.view()).isEmpty())
    }

    @Test
    fun refreshKeepsAReportAlivePastMaxAge() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        settle(config.refreshMs, a, b, c)
        assertEquals(setOf("C"), b.state.reachable(config.maxAgeMs + 1, b.view()))
    }

    @Test
    fun aDropIsSentAsADeltaAndWithdrawsThePeer() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        disconnect(a, c)
        settle(config.minFrameGapMs, a, b, c)
        val delta = wire.filter { it.first == "A" && it.second == "B" }
            .mapNotNull { it.third as? PresenceFrame.Report }.last()
        assertFalse(delta.full)
        assertEquals(PresenceReportState.Gone, delta.entries.single { it.deviceId == "C" }.state)
        assertTrue(b.state.reachable(config.minFrameGapMs, b.view()).isEmpty())
    }

    @Test
    fun reportsRelayOnceAndNeverPastTwoHops() {
        // C - A - B - D - E: a chain; everyone is paired with everyone except as noted.
        val (a, b, c) = triangle()
        val d = Node("D")
        val e = Node("E")
        connect(b, d)
        connect(d, e)
        val all = setOf("A", "B", "C", "D", "E")
        listOf(a, b, c, d, e).forEach { n -> n.trusted = all - n.id }
        settle(0, a, b, c, d, e)
        val toD = wire.filter { it.first == "B" && it.second == "D" }
            .mapNotNull { it.third as? PresenceFrame.Report }.flatMap { it.entries }
        assertEquals(2, toD.first { it.deviceId == "C" }.hops)
        assertTrue("C" in d.state.reachable(0, d.view()))
        // D holds C only second-hand, so it must not pass C on to E.
        val toE = wire.filter { it.first == "D" && it.second == "E" }
            .mapNotNull { it.third as? PresenceFrame.Report }.flatMap { it.entries }
        assertFalse(toE.any { it.deviceId == "C" })
    }

    @Test
    fun reportsAreNeverSentBackToTheirSource() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        // B learned C from A; B must not tell A about C.
        val toA = wire.filter { it.first == "B" && it.second == "A" }
            .mapNotNull { it.third as? PresenceFrame.Report }.flatMap { it.entries }
        assertFalse(toA.any { it.deviceId == "C" })
    }

    @Test
    fun aForgedTipIsOfferedOnlyForAPinnedPeerAndCostsFailedDials() {
        val b = Node("B")
        b.trusted = setOf("A", "C")
        b.sessions = setOf("A")
        val view = b.view()
        val forged = PresenceFrame.Report(
            full = true,
            entries = listOf(PresenceEntry("C", 0, PresenceReportState.Connected, 1, PresenceEndpoint("10.0.0.99", 45822))),
        )
        assertTrue(b.state.onFrame(0, "A", forged, view))
        val tip = b.state.tips(0, view).getValue("C")
        assertEquals("A", tip.source)

        // Unpinned: never dialed from a tip (trust-on-first-use would pin whoever answers).
        b.pinned = setOf("A")
        assertTrue(b.state.tips(0, b.view()).isEmpty())
        b.pinned = null

        repeat(config.failedTipsToIgnore) { b.state.onTipResult(tip, success = false) }
        assertTrue(b.state.tips(0, b.view()).isEmpty())
        assertTrue(b.state.reachable(0, b.view()).isEmpty())
        // Silenced for the rest of this session: further reports are dropped.
        assertFalse(b.state.onFrame(1, "A", forged, b.view()))
        // A new session starts clean.
        reconnect("B", "A")
        b.state.step(2, b.view())
        assertTrue(b.state.onFrame(2, "A", forged, b.view()))
    }

    @Test
    fun aVouchedGroupMemberIsDialedFromATipOnceItHasAPinAndNotBefore() {
        // ADR-044 V2: C is a fellow member of B's group but was never paired with B. The owner's vouch is what
        // gives B a pin for C, and a tip is only ever dialed against a pinned subject.
        val b = Node("B")
        b.trusted = setOf("A")
        b.rosters = listOf(setOf("A", "B", "C"))
        b.sessions = setOf("A")
        val report = PresenceFrame.Report(
            full = true,
            entries = listOf(PresenceEntry("C", 0, PresenceReportState.Seen, 1, PresenceEndpoint("10.0.0.7", 45822))),
        )
        assertTrue(b.state.onFrame(0, "A", report, b.view()), "C is a contact through the roster, so the report is kept")

        b.pinned = setOf("A")
        assertTrue(b.state.tips(0, b.view()).isEmpty(), "no pin yet: trust-on-first-use would pin whoever answers")

        b.pinned = setOf("A", "C")
        val tip = b.state.tips(0, b.view()).getValue("C")
        assertEquals("A", tip.source)
        assertEquals(PresenceEndpoint("10.0.0.7", 45822), tip.endpoint)
    }

    @Test
    fun theHostViewCountsAnUnpairedRosterMemberWithAPinAsPinned() {
        val view = PresenceLocalView.of(
            ghost = false,
            sessions = emptyMap(),
            isLive = { false },
            sightings = emptyList(),
            trusted = setOf("owner"),
            hasPin = { it == "owner" || it == "vouched" },
            rosters = listOf(setOf("owner", "vouched", "unvouched")),
        )

        assertEquals(setOf("owner", "vouched"), view.pinned)
        assertEquals(setOf("owner"), view.trusted)
    }

    @Test
    fun aSuccessfulTipResetsTheFailureCount() {
        val b = Node("B")
        b.trusted = setOf("A", "C")
        b.sessions = setOf("A")
        val tipFrame = PresenceFrame.Report(
            true,
            listOf(PresenceEntry("C", 0, PresenceReportState.Seen, 1, PresenceEndpoint("10.0.0.9", 45822))),
        )
        b.state.onFrame(0, "A", tipFrame, b.view())
        val tip = b.state.tips(0, b.view()).getValue("C")
        repeat(config.failedTipsToIgnore - 1) { b.state.onTipResult(tip, false) }
        b.state.onTipResult(tip, true)
        repeat(config.failedTipsToIgnore - 1) { b.state.onTipResult(tip, false) }
        assertEquals(setOf("C"), b.state.tips(0, b.view()).keys)
    }

    @Test
    fun myOwnObservationBeatsATip() {
        val b = Node("B")
        b.trusted = setOf("A", "C")
        b.sessions = setOf("A")
        b.state.onFrame(
            0, "A",
            PresenceFrame.Report(true, listOf(PresenceEntry("C", 0, PresenceReportState.Seen, 1, PresenceEndpoint("10.0.0.9", 1)))),
            b.view(),
        )
        b.seen = mapOf("C" to PresenceEndpoint("10.0.0.10", 45822))
        assertTrue(b.state.tips(0, b.view()).isEmpty())
        b.seen = emptyMap()
        b.sessions = setOf("A", "C")
        assertTrue(b.state.tips(0, b.view()).isEmpty())
    }

    @Test
    fun framesFromStrangersAndReportsAboutStrangersAreIgnored() {
        val b = Node("B")
        b.trusted = setOf("A")
        b.sessions = setOf("A", "X")
        val report = PresenceFrame.Report(true, listOf(PresenceEntry("Z", 0, PresenceReportState.Seen, 1)))
        assertFalse(b.state.onFrame(0, "X", report, b.view()), "X is neither paired nor a group fellow")
        assertTrue(b.state.onFrame(0, "A", report, b.view()))
        assertTrue(b.state.reachable(0, b.view()).isEmpty(), "Z is not one of B's contacts")
    }

    @Test
    fun framesAreRateLimitedPerSender() {
        val b = Node("B")
        b.trusted = setOf("A")
        b.sessions = setOf("A")
        val hello = PresenceFrame.Hello(true, "00112233445566778899aabbccddeeff")
        val accepted = (1..config.rateCapacity + 5).count { b.state.onFrame(0, "A", hello, b.view()) }
        assertEquals(config.rateCapacity, accepted)
        assertTrue(b.state.onFrame(config.rateRefillMs, "A", hello, b.view()))
    }

    @Test
    fun aNewSessionGetsAFreshSalt() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        val firstSalt = wire.first { it.first == "A" && it.second == "B" && it.third is PresenceFrame.Hello }
            .let { (it.third as PresenceFrame.Hello).salt }
        reconnect("A", "B")
        wire.clear()
        settle(10, a, b, c)
        val secondSalt = wire.first { it.first == "A" && it.second == "B" && it.third is PresenceFrame.Hello }
            .let { (it.third as PresenceFrame.Hello).salt }
        assertNotEquals(firstSalt, secondSalt)
        // B asked again under the new salt in the same exchange, not a refresh interval later.
        assertTrue(wire.any { it.first == "B" && it.second == "A" && it.third is PresenceFrame.Want })
        assertTrue(wire.any { it.first == "A" && it.second == "B" && (it.third as? PresenceFrame.Report)?.entries?.any { e -> e.deviceId == "C" } == true })
        assertEquals(setOf("C"), b.state.reachable(10, b.view()))
    }

    @Test
    fun aQuietMeshSendsNothingBetweenRefreshes() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        wire.clear()
        settle(config.refreshMs / 2, a, b, c)
        assertTrue(wire.isEmpty())
        val next = a.state.step(config.refreshMs / 2, a.view()).nextInMs
        assertTrue(next in 1..config.refreshMs, "next=$next")
    }

    // ---------------------------------------------------------------- PC5: modes

    @Test
    fun anEcoReporterIsHeldUntilItsNextRefreshOnAStandardReceiver() {
        val (a, b, c) = triangle()
        a.state.config = PresenceConfig.ECO
        settle(0, a, b, c)
        // Past STANDARD's own 45 s, but A only refreshes every 60 s: B must still hold it.
        assertEquals(setOf("C"), b.state.reachable(PresenceConfig.ECO.refreshMs - 1, b.view()))
        a.modern = false
        val gone = PresenceConfig.ECO.refreshMs * 3 / 2 + 1
        b.state.step(gone, b.view())
        assertTrue(b.state.reachable(gone, b.view()).isEmpty(), "still expires once A is silent")
    }

    @Test
    fun aModeChangeAnnouncesTheNewRefreshAtOnce() {
        val (a, b, c) = triangle()
        settle(0, a, b, c)
        wire.clear()
        a.state.config = PresenceConfig.BOOST
        settle(1_000, a, b, c)
        val hellos = wire.filter { it.first == "A" }.mapNotNull { it.third as? PresenceFrame.Hello }
        assertEquals(setOf(PresenceConfig.BOOST.refreshMs), hellos.map { it.refreshMs }.toSet())
        assertEquals(2, hellos.size, "one to each peer")
    }

    @Test
    fun relayedReportsDoNotFlickerBetweenRefreshesInAnyModeMix() {
        val modes = listOf(PresenceConfig.ECO, PresenceConfig.STANDARD, PresenceConfig.BOOST)
        for (origin in modes) for (relay in modes) for (receiver in modes) {
            wire.clear()
            tokens.clear()
            // C - A - B - D: D hears about C only through B relaying A's report.
            val a = Node("A").apply { state.config = origin }
            val b = Node("B").apply { state.config = relay }
            val c = Node("C")
            val d = Node("D").apply { state.config = receiver }
            connect(a, c)
            connect(a, b)
            connect(b, d)
            listOf(a, b, c, d).forEach { n -> n.trusted = setOf("A", "B", "C", "D") - n.id }
            var t = 0L
            settle(t, a, b, c, d)
            while (t < 300_000L) {
                t += 500L
                settle(t, a, b, c, d)
                val label = "origin ${origin.refreshMs} relay ${relay.refreshMs} receiver ${receiver.refreshMs} t=$t"
                assertTrue("C" in d.state.reachable(t, d.view()), label)
            }
        }
    }
}
