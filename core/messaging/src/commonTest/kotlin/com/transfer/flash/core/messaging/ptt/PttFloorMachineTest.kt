package com.transfer.flash.core.messaging.ptt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PttFloorMachineTest {
    private val config = PttFloorConfig(
        maxBurstMs = 60_000L,
        warnAtMs = 45_000L,
        heartbeatTimeoutMs = 5_000L,
        collisionWindowMs = 1_500L,
    )

    private fun reduce(
        state: PttFloorState,
        event: PttFloorEvent,
    ): PttTransition = PttFloorMachine.reduce(state, event, config)

    // Idle.

    @Test
    fun idlePressStartsTalking() {
        val t = reduce(PttFloorState.Idle, PttFloorEvent.LocalPress(1000L, "s1"))

        assertEquals(PttFloorState.Talking("s1", 1000L), t.state)
        assertEquals(listOf(PttFloorEffect.StartCapture("s1")), t.effects)
    }

    @Test
    fun idlePressWithBlankSessionIdIsIgnored() {
        val t = reduce(PttFloorState.Idle, PttFloorEvent.LocalPress(1000L, "  "))

        assertEquals(PttFloorState.Idle, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun idleRemoteStartStartsPlayout() {
        val t = reduce(
            PttFloorState.Idle,
            PttFloorEvent.RemoteStart("s1", "peer-a", "Peer A", 1000L),
        )

        assertEquals(
            PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L),
            t.state,
        )
        assertEquals(
            listOf(PttFloorEffect.StartPlayout("s1", "Peer A", 16000, 20)),
            t.effects,
        )
    }

    @Test
    fun idleIgnoresStrayEvents() {
        val idle = PttFloorState.Idle
        listOf(
            PttFloorEvent.RemoteStop("s1", "peer-a"),
            PttFloorEvent.StartAnnounced("s1"),
            PttFloorEvent.RemoteLeave("s1", "peer-a"),
            PttFloorEvent.RemoteActivity("s1", "peer-a", 1000L),
            PttFloorEvent.Tick(1000L),
            PttFloorEvent.LocalStopPress,
            PttFloorEvent.CallStarted,
            PttFloorEvent.MicDenied,
        ).forEach { event ->
            val t = reduce(idle, event)
            assertEquals(PttFloorState.Idle, t.state)
            assertTrue(t.effects.isEmpty())
        }
    }

    // Talking.

    @Test
    fun secondPressTogglesTalkOff() {
        val talking = PttFloorState.Talking("s1", 1000L)
        val t = reduce(talking, PttFloorEvent.LocalPress(2000L, "s2"))

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopCapture("s1"),
                PttFloorEffect.SendStop("s1"),
            ),
            t.effects,
        )
    }

    @Test
    fun explicitStopPressEndsTalk() {
        val t = reduce(PttFloorState.Talking("s1", 1000L), PttFloorEvent.LocalStopPress)

        assertEquals(PttFloorState.Idle, t.state)
        assertTrue(t.effects.contains(PttFloorEffect.SendStop("s1")))
    }

    @Test
    fun foreignStopCannotEndOurTalk() {
        val talking = PttFloorState.Talking("s1", 1000L)
        val t = reduce(talking, PttFloorEvent.RemoteStop("s1", "peer-a"))

        assertEquals(talking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun remoteStartWhileTalkingIsIgnored() {
        val talking = PttFloorState.Talking("s1", 1000L)
        val t = reduce(talking, PttFloorEvent.RemoteStart("s2", "peer-b", "Peer B", 2000L))

        assertEquals(talking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun higherIdLocalClaimYieldsToLowerIdRemoteClaim() {
        val talking = PttFloorState.Talking("local-session", 1000L, holderId = "peer-z")
        val event = PttFloorEvent.RemoteStart(
            "remote-session",
            "peer-a",
            "Peer A",
            1001L,
            sampleRateHz = 8000,
            packetMs = 60,
        )

        val t = reduce(talking, event)

        assertEquals(
            PttFloorState.Listening(
                "remote-session", "peer-a", "Peer A", 1001L, 1001L, 8000, 60,
            ),
            t.state,
        )
        assertEquals(
            listOf(
                PttFloorEffect.StopCapture("local-session"),
                PttFloorEffect.SendStop("local-session"),
                PttFloorEffect.StartPlayout("remote-session", "Peer A", 8000, 60),
            ),
            t.effects,
        )
    }

    @Test
    fun lowerIdLocalClaimKeepsFloorAgainstHigherIdRemoteClaim() {
        val talking = PttFloorState.Talking("local-session", 1000L, holderId = "peer-a")
        val t = reduce(
            talking,
            PttFloorEvent.RemoteStart("remote-session", "peer-z", "Peer Z", 1001L),
        )

        assertEquals(talking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun establishedTalkDoesNotYieldToLateLowerIdStart() {
        val talking = PttFloorState.Talking("local-session", 1000L, holderId = "peer-z")
        val t = reduce(
            talking,
            PttFloorEvent.RemoteStart("remote-session", "peer-a", "Peer A", 3000L),
        )

        assertEquals(talking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun startAnnouncementEnablesPacketsOnlyForCurrentTalk() {
        val talking = PttFloorState.Talking("s1", 1000L)

        val announced = reduce(talking, PttFloorEvent.StartAnnounced("s1"))
        assertEquals(talking.copy(announced = true), announced.state)
        assertEquals(listOf(PttFloorEffect.EnableCapturePackets("s1")), announced.effects)

        val replay = reduce(announced.state, PttFloorEvent.StartAnnounced("s1"))
        assertEquals(announced.state, replay.state)
        assertTrue(replay.effects.isEmpty())

        val stale = reduce(talking, PttFloorEvent.StartAnnounced("s2"))
        assertEquals(talking, stale.state)
        assertTrue(stale.effects.isEmpty())
    }

    @Test
    fun burstWarnsOnceThenCaps() {
        val talking = PttFloorState.Talking("s1", 0L)

        val warn = reduce(talking, PttFloorEvent.Tick(45_000L))
        assertEquals(PttFloorState.Talking("s1", 0L, warned = true), warn.state)
        assertEquals(listOf(PttFloorEffect.NotifyBurstWarning(15_000L)), warn.effects)

        val repeat = reduce(warn.state, PttFloorEvent.Tick(50_000L))
        assertEquals(warn.state, repeat.state)
        assertTrue(repeat.effects.isEmpty())

        val cap = reduce(warn.state, PttFloorEvent.Tick(60_000L))
        assertEquals(PttFloorState.Idle, cap.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopCapture("s1"),
                PttFloorEffect.SendStop("s1"),
                PttFloorEffect.NotifyEnded(PttEndReason.BURST_CAP, null),
            ),
            cap.effects,
        )
    }

    @Test
    fun callStartedTearsTalkDown() {
        val t = reduce(PttFloorState.Talking("s1", 1000L), PttFloorEvent.CallStarted)

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopCapture("s1"),
                PttFloorEffect.SendStop("s1"),
                PttFloorEffect.NotifyEnded(PttEndReason.CALL_STARTED, null),
            ),
            t.effects,
        )
    }

    @Test
    fun micDeniedReleasesFloor() {
        val t = reduce(PttFloorState.Talking("s1", 1000L), PttFloorEvent.MicDenied)

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopCapture("s1"),
                PttFloorEffect.SendStop("s1"),
                PttFloorEffect.NotifyEnded(PttEndReason.MIC_DENIED, null),
            ),
            t.effects,
        )
    }

    // Listening.

    @Test
    fun matchingRemoteStopEndsListen() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteStop("s1", "peer-a"))

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopPlayout("s1", PttEndReason.REMOTE_STOP),
                PttFloorEffect.NotifyEnded(PttEndReason.REMOTE_STOP, "Peer A"),
            ),
            t.effects,
        )
    }

    @Test
    fun foreignStopIsIgnoredWhileListening() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteStop("s2", "peer-a"))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun matchingSessionStopFromNonHolderIsIgnored() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteStop("s1", "peer-b"))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun receiverCancelLeavesQuietly() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.LocalStopPress)

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopPlayout("s1", PttEndReason.LOCAL_STOP),
                PttFloorEffect.SendLeave("s1"),
            ),
            t.effects,
        )
    }

    @Test
    fun pressWhileListeningReportsBusy() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.LocalPress(2000L, "s2"))

        assertEquals(listening, t.state)
        assertEquals(listOf(PttFloorEffect.NotifyBusy("Peer A")), t.effects)
    }

    @Test
    fun heartbeatOrAudioRefreshesAndTimeoutFires() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)

        val refreshed = reduce(
            listening,
            PttFloorEvent.RemoteActivity("s1", "peer-a", 4000L),
        )
        assertEquals(listening.copy(lastActivityMs = 4000L), refreshed.state)
        assertTrue(refreshed.effects.isEmpty())

        val alive = reduce(refreshed.state, PttFloorEvent.Tick(8000L))
        assertEquals(refreshed.state, alive.state)
        assertTrue(alive.effects.isEmpty())

        val dead = reduce(refreshed.state, PttFloorEvent.Tick(9000L))
        assertEquals(PttFloorState.Idle, dead.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopPlayout("s1", PttEndReason.TIMEOUT),
                PttFloorEffect.NotifyEnded(PttEndReason.TIMEOUT, "Peer A"),
            ),
            dead.effects,
        )
    }

    @Test
    fun staleActivityIsIgnored() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteActivity("s2", "peer-a", 4000L))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun olderActivityTimestampCannotMoveLivenessBackward() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 4000L)
        val t = reduce(listening, PttFloorEvent.RemoteActivity("s1", "peer-a", 3000L))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun matchingSessionActivityFromNonHolderIsIgnored() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteActivity("s1", "peer-b", 4000L))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun secondClaimWhileListeningIsIgnored() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.RemoteStart("s2", "peer-b", "Peer B", 2000L))

        assertEquals(listening, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun remoteLeaveNeverChangesState() {
        val talking = PttFloorState.Talking("s1", 1000L)
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val leave = reduce(talking, PttFloorEvent.RemoteLeave("s1", "peer-b"))
        assertEquals(talking, leave.state)
        assertEquals(listOf(PttFloorEffect.RemoveMember("s1", "peer-b")), leave.effects)
        assertEquals(listening, reduce(listening, PttFloorEvent.RemoteLeave("s1", "peer-b")).state)
        assertTrue(reduce(listening, PttFloorEvent.RemoteLeave("s1", "peer-b")).effects.isEmpty())
    }

    @Test
    fun staleLeaveDoesNotPruneCurrentTalk() {
        val talking = PttFloorState.Talking("s2", 1000L)
        val t = reduce(talking, PttFloorEvent.RemoteLeave("s1", "peer-b"))

        assertEquals(talking, t.state)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun callStartedWhileListeningSendsLeave() {
        val listening = PttFloorState.Listening("s1", "peer-a", "Peer A", 1000L, 1000L)
        val t = reduce(listening, PttFloorEvent.CallStarted)

        assertEquals(PttFloorState.Idle, t.state)
        assertEquals(
            listOf(
                PttFloorEffect.StopPlayout("s1", PttEndReason.CALL_STARTED),
                PttFloorEffect.SendLeave("s1"),
                PttFloorEffect.NotifyEnded(PttEndReason.CALL_STARTED, "Peer A"),
            ),
            t.effects,
        )
    }
}
