package com.transfer.flash.core.swarm.driver

import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferRecipient
import com.transfer.flash.core.transfer.model.FlashTransferState

/**
 * Evidence probes for group file transfers (ERROR-119, docs/testing/PROBES.md), derived from the row stream so the engine
 * stays sans-IO. One line per state CHANGE, never per piece: the offer is shown, the person accepted, the first byte
 * arrived, the file is done, and on the sender when each member first received something and when each member has the
 * whole file. They answer "how long did it take to start" from a log, which the logs of 2026-10-07 could not.
 */
internal class SwarmRowProbes(
    private val emit: (name: String, fields: List<Pair<String, Any?>>) -> Unit,
) {
    private class Track(val firstSeenMs: Long) {
        var offeredAtMs: Long? = null
        var acceptedAtMs: Long? = null
        var firstByteSeen = false
        var doneSeen = false
        val membersStarted = HashSet<String>()
        val membersDone = HashSet<String>()
    }

    private val tracks = HashMap<String, Track>()

    fun onRow(
        transferId: String,
        direction: FlashTransferDirection,
        state: FlashTransferState,
        bytesDone: Long,
        bytesTotal: Long,
        recipients: List<FlashTransferRecipient>,
        nowMs: Long,
    ) {
        val id = transferId.take(ID_LENGTH)
        var fresh = false
        val t = tracks.getOrPut(transferId) { fresh = true; Track(nowMs) }
        // A row first seen already finished, or with members already holding, was restored after a restart: its timings
        // belong to an earlier run, so it is marked seen without a line (the desktop log of 2026-10-07 reported a 0 ms
        // "receive" for a file received an hour before).
        if (fresh) {
            if (direction == FlashTransferDirection.Receiving) {
                if (state == FlashTransferState.Completed) {
                    t.offeredAtMs = nowMs
                    t.acceptedAtMs = nowMs
                    t.firstByteSeen = true
                    t.doneSeen = true
                }
            } else {
                for (r in recipients) {
                    if (r.bytesHeld > 0L) t.membersStarted.add(r.peerId)
                    if (r.hasAll) t.membersDone.add(r.peerId)
                }
            }
        }
        if (direction == FlashTransferDirection.Receiving) {
            if (state == FlashTransferState.Offered && t.offeredAtMs == null) {
                t.offeredAtMs = nowMs
                emit("swarm.offer.shown", listOf("transfer" to id, "bytes" to bytesTotal))
            }
            val moving = state != FlashTransferState.Offered && state != FlashTransferState.Cancelled
            if (moving && t.acceptedAtMs == null) {
                t.acceptedAtMs = nowMs
                emit("swarm.accepted", listOf("transfer" to id, "afterOfferMs" to t.offeredAtMs?.let { nowMs - it }))
            }
            val accepted = t.acceptedAtMs
            if (accepted != null && bytesDone > 0L && !t.firstByteSeen) {
                t.firstByteSeen = true
                emit("swarm.first_byte", listOf("transfer" to id, "sinceAcceptMs" to (nowMs - accepted)))
            }
            if (state == FlashTransferState.Completed && !t.doneSeen) {
                t.doneSeen = true
                val took = nowMs - (accepted ?: t.firstSeenMs)
                emit(
                    "swarm.recv.done",
                    listOf(
                        "transfer" to id,
                        "bytes" to bytesTotal,
                        "sinceAcceptMs" to took,
                        "avgKBps" to if (took > 0) bytesTotal * 1000 / took / 1024 else null,
                    ),
                )
            }
        } else {
            for (r in recipients) {
                if (r.bytesHeld > 0L && t.membersStarted.add(r.peerId)) {
                    emit(
                        "swarm.member.first",
                        listOf("transfer" to id, "member" to r.peerId.take(ID_LENGTH), "sinceSendMs" to (nowMs - t.firstSeenMs)),
                    )
                }
                if (r.hasAll && t.membersDone.add(r.peerId)) {
                    emit(
                        "swarm.member.done",
                        listOf(
                            "transfer" to id,
                            "member" to r.peerId.take(ID_LENGTH),
                            "sinceSendMs" to (nowMs - t.firstSeenMs),
                            "done" to t.membersDone.size,
                            "seen" to recipients.size,
                        ),
                    )
                }
            }
        }
        if (state == FlashTransferState.Failed || state == FlashTransferState.Cancelled) tracks.remove(transferId)
    }

    private companion object {
        const val ID_LENGTH = 8
    }
}
