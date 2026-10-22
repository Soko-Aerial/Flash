@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.relay

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.HubErrorCodes
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.relay.AckStatus
import com.transfer.flash.core.hubprotocol.relay.QueueEvent
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.hubprotocol.relay.RelayFrameCodec
import com.transfer.flash.core.hubprotocol.relay.RelayLimits
import com.transfer.flash.server.auth.RevocationService
import com.transfer.flash.server.directory.OrganizationDirectory
import com.transfer.flash.server.mailbox.EnqueueResult
import com.transfer.flash.server.mailbox.MailboxMessage
import com.transfer.flash.server.mailbox.MailboxStore
import com.transfer.flash.server.observability.LogSanitizer
import com.transfer.flash.server.observability.RelayMetrics
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Router and dispatcher for the `/flash-relay` WebSocket: "Hub relay wire v2" (ADR-109, `docs/protocol.md`), with the
 * delivery semantics of ADR-112.
 *
 * **Handshake.** `FLASH_HUB_CHALLENGE` -> signed `FLASH_HUB_HELLO` within [handshakeTimeoutMs] -> [RelayAuthenticator]
 * -> `FLASH_HUB_WELCOME`. No identity is read from the URL or from any later frame: every `FLASH_DELIVER` /
 * `FLASH_META` carries the sender the handshake proved (SEC-02), because `FLASH_RELAY` has no sender field at all.
 *
 * **Delivery semantics (REL-02, REL-07).** AT-LEAST-ONCE, recipient-acknowledged:
 * - a live message is handed to the recipient session's bounded outbound queue and the sender is told `DELIVERED`
 *   (meaning "handed over", see `AckStatus.DELIVERED`). It stays in the session's in-flight table until the recipient's
 *   `FLASH_RECV_ACK`; if the session ends first, the message is spilled into the mailbox.
 * - an offline recipient (or one still being drained) gets a durable mailbox row and the sender is told `QUEUED`.
 * - the mailbox row, or the in-flight entry, is deleted ONLY by the recipient's `FLASH_RECV_ACK`; then the sender is
 *   sent `FLASH_QUEUE_STATE state=DELIVERED`. An expired row produces `state=EXPIRED` (see `TtlSweeper`), a lost one
 *   `state=DROPPED`.
 * - a reconnecting device is drained from the mailbox in pages, oldest first, `replay=true`; a message may therefore be
 *   delivered more than once and CLIENTS DEDUPE BY MESSAGE ID.
 * - while a device has mailbox rows waiting, live messages for it also go through the mailbox, so a newer message
 *   never overtakes an older one.
 *
 * @param authenticator null means "no authenticator is configured": every connection is refused (fail closed).
 * @param serverId the realm audience sent in the challenge (root fingerprint, upper-case hex).
 * @param revocation live revocation state; a device that becomes revoked is kicked at once ([kickIfRevoked]) and in any
 *   case dropped on its next frame. [revocationListProvider] is the older list-only source and is honoured too.
 * @param groups answers "who is in this group" for group `FLASH_META` and group `FLASH_RELAY`.
 */
public class RelayFrameRouter(
    public val connectionPool: RelayConnectionPool,
    public val mailboxQueue: MailboxStore = MailboxStore.None,
    public val directory: OrganizationDirectory? = null,
    public val revocationListProvider: (() -> RevocationList?)? = null,
    public val authenticator: RelayAuthenticator? = null,
    public val serverId: String = "",
    public val policy: RelayPolicy = RelayPolicy(),
    public val handshakeTimeoutMs: Long = policy.handshakeTimeoutMs,
    public val groups: GroupMembershipView = directory?.let { DirectoryGroupMembership(it) } ?: GroupMembershipView.None,
    public val revocation: RevocationService = RevocationService.None,
    public val metrics: RelayMetrics = RelayMetrics(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceSource: () -> String = SecureNonce::next,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /**
     * The address the per-IP connection cap counts against. Production passes the [com.transfer.flash.server.auth.ClientAddressResolver]
     * (right-most untrusted `X-Forwarded-For` entry behind a trusted proxy, the TCP peer otherwise); the default is the TCP
     * peer, which is only right without a proxy.
     */
    private val clientAddressOf: (ApplicationCall) -> String = { it.request.origin.remoteAddress },
) {

    private val logger = LoggerFactory.getLogger(RelayFrameRouter::class.java)
    private val accepting = AtomicBoolean(true)
    private val limiter = ConnectionLimiter(policy.maxConnections, policy.maxConnectionsPerIp)

    /** Sockets currently open (handshaking or established). */
    public val openConnections: Int get() = limiter.openCount

    init {
        revocation.onRevoked { deviceId, _, reason -> kickIfRevoked(deviceId, reason) }
    }

    // =====================================================================================================
    // Session registration
    // =====================================================================================================

    /**
     * Wires [session] to this router (cleanup hook, rate bucket) and registers it in the pool. [handleSession] does
     * this for real connections; tests that build sessions by hand call it instead of `pool.register`.
     */
    public fun register(session: RelaySession): RelaySession? {
        session.onClosed = { s, reason -> sessionEnded(s, reason) }
        session.rateBucket = TokenBucket(policy.frameBurst, policy.framesPerSecond)
        session.byteBucket = TokenBucket(policy.senderByteBurst, policy.senderBytesPerSecond.toDouble())
        return connectionPool.register(session)
    }

    // =====================================================================================================
    // Inbound frames
    // =====================================================================================================

    /**
     * Dispatches an inbound raw [frameText] from the authenticated [originSession].
     *
     * Anything the session claims about its own identity is ignored: the stamped sender is always
     * `originSession.deviceId`, which came from the handshake.
     */
    public suspend fun processFrame(originSession: RelaySession, frameText: String) {
        if (frameText.isBlank()) return
        originSession.lastFrameAt = clock()
        metrics.inc(RelayMetrics.BYTES_IN, frameText.length.toLong())

        if (isRevoked(originSession)) {
            kickRevoked(originSession, "revoked")
            return
        }
        if (hasExpired(originSession)) {
            expire(originSession)
            return
        }

        val bucket = originSession.rateBucket
        if (bucket != null && !bucket.tryTake()) {
            rateLimited(originSession)
            return
        }

        val frame = when (val decoded = RelayFrameCodec.decodeResult(frameText, HubContract.MAX_FRAME_BYTES)) {
            is RelayFrameCodec.Decoded.Ok -> decoded.frame
            is RelayFrameCodec.Decoded.Rejected -> {
                metrics.inc(RelayMetrics.FRAMES_IN, 1, "kind" to "rejected")
                logger.warn("Rejected frame from '{}': {} {}", LogSanitizer.clean(originSession.deviceId), decoded.reason, LogSanitizer.clean(decoded.detail))
                val code = codeFor(decoded.reason)
                offerFrame(originSession, RelayFrame.Error(code, if (code == HubErrorCodes.ERR_TOO_LARGE) MESSAGE_TOO_LARGE else MESSAGE_BAD_FRAME))
                if (code == HubErrorCodes.ERR_TOO_LARGE) {
                    originSession.closeGracefully("too_large", CloseReason.Codes.TOO_BIG)
                } else {
                    noteRefusal(originSession)
                }
                return
            }
        }
        metrics.inc(RelayMetrics.FRAMES_IN, 1, "kind" to kindOf(frame))

        when (frame) {
            is RelayFrame.Ping -> {
                originSession.recordPing()
                offerFrame(originSession, RelayFrame.Pong(ts = clock()))
            }
            is RelayFrame.Pong -> Unit
            is RelayFrame.Send -> handleSend(originSession, frame)
            is RelayFrame.RecvAck -> handleRecvAck(originSession, frame)
            is RelayFrame.Meta -> handleInboundMeta(originSession, frame)
            else -> {
                // SEC-02: a client may not send FLASH_ACK / FLASH_DELIVER / FLASH_QUEUE_STATE / FLASH_KICK ...
                // (there is no peer-forwarded ack on this wire), so there is nothing to forward and nothing to spoof.
                logger.warn("Unexpected {} frame from '{}'", frame::class.simpleName, LogSanitizer.clean(originSession.deviceId))
                offerFrame(originSession, RelayFrame.Error(HubErrorCodes.ERR_PROTOCOL, MESSAGE_UNEXPECTED_FRAME))
                noteRefusal(originSession)
            }
        }
    }

    private suspend fun rateLimited(session: RelaySession) {
        metrics.inc(RelayMetrics.RATE_LIMIT_HITS)
        val now = clock()
        if (now - session.lastRateLimitNoticeAt >= 1_000L) {
            session.lastRateLimitNoticeAt = now
            offerFrame(session, RelayFrame.Error(HubErrorCodes.ERR_RATE_LIMIT, MESSAGE_RATE_LIMIT))
        }
        noteRefusal(session)
    }

    /** Counts a refused frame; a session that keeps sending refusable frames is disconnected. */
    private suspend fun noteRefusal(session: RelaySession) {
        session.refusals++
        if (session.refusals >= policy.abusiveRejectionsBeforeClose) {
            logger.warn("Closing '{}' after {} refused frames", LogSanitizer.clean(session.deviceId), session.refusals)
            session.closeGracefully("rate_limit", CloseReason.Codes.VIOLATED_POLICY)
        }
    }

    // =====================================================================================================
    // FLASH_RELAY
    // =====================================================================================================

    private enum class RouteKind { LIVE, QUEUED, DUPLICATE, QUOTA, UNKNOWN_RECIPIENT, UNAVAILABLE, FAILED }

    private suspend fun handleSend(origin: RelaySession, send: RelayFrame.Send) {
        val group = send.group
        if (group != null) {
            handleGroupSend(origin, send, group)
            return
        }
        if (!withinByteBudget(origin, send, send.to, copies = 1)) return
        val kind = routeOne(origin.deviceId, send.to, send.id, send.type, send.sentAt, send.payload, group = null)
        offerFrame(origin, ackFor(kind, send.id, send.to))
    }

    /**
     * RLY-02: charges the sender's byte bucket the payload size times the copies the hub is about to emit. Over budget, nothing is
     * routed: the sender gets `REJECTED` with `ERR_RATE_LIMIT` for this message id, the refusal is counted, and a session that keeps
     * flooding is closed by [noteRefusal] like any other abusive sender.
     */
    private suspend fun withinByteBudget(origin: RelaySession, send: RelayFrame.Send, to: String, copies: Int): Boolean {
        val bucket = origin.byteBucket ?: return true
        if (bucket.tryTake(send.payload.length.toLong() * copies.coerceAtLeast(1))) return true
        metrics.inc(RelayMetrics.RATE_LIMIT_HITS)
        metrics.inc(RelayMetrics.MESSAGES, 1, "outcome" to "byte_limited")
        offerFrame(origin, RelayFrame.Ack(id = send.id, to = to, status = AckStatus.REJECTED, ts = clock(), reason = HubErrorCodes.ERR_RATE_LIMIT))
        noteRefusal(origin)
        return false
    }

    /** REL-14: one `FLASH_RELAY{group}` becomes one delivery per member, and ONE aggregate `FLASH_ACK` to the sender. */
    private suspend fun handleGroupSend(origin: RelaySession, send: RelayFrame.Send, groupId: String) {
        val members = groups.membersOf(groupId)
        if (members == null || origin.deviceId !in members) {
            // The same answer whether the group does not exist or the sender is not in it: no membership oracle.
            metrics.inc(RelayMetrics.MESSAGES, 1, "outcome" to "rejected")
            offerFrame(
                origin,
                RelayFrame.Ack(id = send.id, to = groupId, status = AckStatus.REJECTED, ts = clock(), reason = HubErrorCodes.ERR_NOT_MEMBER),
            )
            return
        }
        val recipients = members.filter { it != origin.deviceId }
        if (!withinByteBudget(origin, send, groupId, copies = recipients.size)) return
        val gate = Semaphore(policy.groupFanoutConcurrency)
        val outcomes = coroutineScope {
            recipients.map { member ->
                async {
                    gate.withPermit { routeOne(origin.deviceId, member, send.id, send.type, send.sentAt, send.payload, groupId) }
                }
            }.awaitAll()
        }
        val live = outcomes.count { it == RouteKind.LIVE }
        val queued = outcomes.count { it == RouteKind.QUEUED || it == RouteKind.DUPLICATE }
        val failed = outcomes.size - live - queued
        val status = when {
            failed == 0 && queued == 0 -> AckStatus.DELIVERED
            failed == 0 -> AckStatus.QUEUED
            live + queued > 0 -> AckStatus.QUEUED // partial: accepted, `failed` says how many members were refused
            outcomes.all { it == RouteKind.QUOTA } -> AckStatus.QUOTA_EXCEEDED
            outcomes.all { it == RouteKind.UNKNOWN_RECIPIENT } -> AckStatus.REJECTED
            else -> AckStatus.ERROR
        }
        val reason = when {
            failed > 0 && live + queued > 0 -> "partial"
            status == AckStatus.REJECTED -> HubErrorCodes.ERR_UNKNOWN_RECIPIENT
            status == AckStatus.QUOTA_EXCEEDED -> HubErrorCodes.ERR_QUOTA_EXCEEDED
            status == AckStatus.ERROR -> HubErrorCodes.ERR_INTERNAL
            else -> null
        }
        offerFrame(
            origin,
            RelayFrame.Ack(
                id = send.id, to = groupId, status = status, ts = clock(), reason = reason,
                delivered = live, queued = queued, failed = failed,
            ),
        )
    }

    /**
     * Routes one message copy to one recipient: live if the recipient is connected AND caught up, otherwise durable.
     * Never suspends on another client: a live hand-off is a non-blocking queue offer.
     */
    private suspend fun routeOne(
        originId: String,
        recipientId: String,
        msgId: String,
        type: String,
        sentAt: Long,
        payload: String,
        group: String?,
    ): RouteKind {
        val target = connectionPool.get(recipientId)
        if (target != null) {
            val deliver = RelayFrame.Deliver(from = originId, id = msgId, type = type, sentAt = sentAt, payload = payload, group = group)
            val flight = InFlight(originId, type, payload, sentAt, group, System.nanoTime())
            when (target.handOffLive(msgId, RelayFrameCodec.encode(deliver), flight, policy.maxInFlightPerSession)) {
                OfferResult.OK -> {
                    metrics.inc(RelayMetrics.FRAMES_OUT, 1, "kind" to "FLASH_DELIVER")
                    metrics.inc(RelayMetrics.MESSAGES, 1, "outcome" to "delivered_live")
                    return RouteKind.LIVE
                }
                OfferResult.FULL -> {
                    logger.warn("Recipient '{}' is a slow consumer; closing it, the message goes to the mailbox", LogSanitizer.clean(recipientId))
                    target.fail("slow_consumer")
                }
                OfferResult.CLOSED, null -> Unit // not caught up, over its in-flight cap or gone: through the mailbox
            }
        }

        val result = mailboxQueue.enqueue(MailboxMessage(originId, recipientId, msgId, type, payload, sentAt, group))
        val kind = when (result) {
            is EnqueueResult.Queued -> RouteKind.QUEUED
            is EnqueueResult.DuplicateQueued -> RouteKind.DUPLICATE
            is EnqueueResult.QuotaExceeded -> RouteKind.QUOTA
            EnqueueResult.UnknownRecipient -> RouteKind.UNKNOWN_RECIPIENT
            EnqueueResult.Unavailable -> RouteKind.UNAVAILABLE
            is EnqueueResult.Failed -> RouteKind.FAILED
        }
        metrics.inc(
            RelayMetrics.MESSAGES, 1,
            "outcome" to when (kind) {
                RouteKind.QUEUED -> "queued"
                RouteKind.DUPLICATE -> "duplicate"
                RouteKind.QUOTA -> "quota_exceeded"
                RouteKind.UNKNOWN_RECIPIENT -> "unknown_recipient"
                RouteKind.UNAVAILABLE -> "undeliverable"
                else -> "error"
            },
        )
        if (result.isStored) {
            // REL-07: the recipient may have connected between our "offline" decision and the insert. A reconnect drain
            // that ran before the insert would never see this row, so wake the recipient's drain now.
            connectionPool.get(recipientId)?.let { requestDrain(it) }
        }
        return kind
    }

    private fun ackFor(kind: RouteKind, id: String, to: String): RelayFrame.Ack = when (kind) {
        RouteKind.LIVE -> RelayFrame.Ack(id = id, to = to, status = AckStatus.DELIVERED, ts = clock())
        RouteKind.QUEUED -> RelayFrame.Ack(id = id, to = to, status = AckStatus.QUEUED, ts = clock())
        RouteKind.DUPLICATE -> RelayFrame.Ack(id = id, to = to, status = AckStatus.DUPLICATE_QUEUED, ts = clock())
        RouteKind.QUOTA -> RelayFrame.Ack(id = id, to = to, status = AckStatus.QUOTA_EXCEEDED, ts = clock(), reason = HubErrorCodes.ERR_QUOTA_EXCEEDED)
        RouteKind.UNKNOWN_RECIPIENT -> RelayFrame.Ack(id = id, to = to, status = AckStatus.REJECTED, ts = clock(), reason = HubErrorCodes.ERR_UNKNOWN_RECIPIENT)
        RouteKind.UNAVAILABLE -> RelayFrame.Ack(id = id, to = to, status = AckStatus.OFFLINE_UNDELIVERABLE, ts = clock())
        RouteKind.FAILED -> RelayFrame.Ack(id = id, to = to, status = AckStatus.ERROR, ts = clock(), reason = HubErrorCodes.ERR_INTERNAL)
    }

    // =====================================================================================================
    // FLASH_RECV_ACK
    // =====================================================================================================

    /**
     * `FLASH_RECV_ACK`: the device says it has durably stored [RelayFrame.RecvAck.msgId] (REL-02). This is the ONLY thing
     * that deletes a mailbox row or an in-flight entry, and it can only touch the sending device's OWN copies (the
     * delete is keyed by the authenticated device id), so a device can not acknowledge, and so destroy, someone else's
     * messages. Idempotent. The original sender is told `FLASH_QUEUE_STATE state=DELIVERED`.
     */
    private suspend fun handleRecvAck(recipient: RelaySession, ack: RelayFrame.RecvAck) {
        val ackFrom = ack.from?.takeIf { it.isNotBlank() }
        val candidate = recipient.inFlight[ack.msgId]
        val flight = if (candidate != null && (ackFrom == null || ackFrom == candidate.origin) && recipient.inFlight.remove(ack.msgId, candidate)) candidate else null
        if (flight != null) {
            metrics.ackLatency.observeSeconds((System.nanoTime() - flight.handedAtNanos) / 1_000_000_000.0)
            notifyDelivered(flight.origin, recipient.deviceId, ack.msgId)
            return
        }
        val rows = try {
            mailboxQueue.acknowledge(recipient.deviceId, ack.msgId, ackFrom)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            metrics.inc(RelayMetrics.DB_ERRORS, 1, "op" to "ack")
            logger.error("RECV_ACK handling failed: {}", t.toString())
            return
        }
        rows.forEach { notifyDelivered(it.origin, recipient.deviceId, ack.msgId) }
    }

    private fun notifyDelivered(originId: String, recipientId: String, msgId: String) {
        val origin = connectionPool.get(originId) ?: return
        offerFrame(origin, RelayFrame.QueueState(id = msgId, to = recipientId, state = QueueEvent.DELIVERED, ts = clock()))
    }

    /** Tells an online sender that a message it queued expired undelivered (`TtlSweeper`). */
    public fun notifyExpired(originId: String, recipientId: String, msgId: String) {
        val origin = connectionPool.get(originId) ?: return
        offerFrame(origin, RelayFrame.QueueState(id = msgId, to = recipientId, state = QueueEvent.EXPIRED, ts = clock()))
    }

    /** Tells each online sender that a message it queued could not be opened and will not be delivered (RLY-01; the row is quarantined). */
    private fun reportQuarantined(rows: List<com.transfer.flash.server.mailbox.QuarantinedRow>) {
        for (r in rows) notifyDropped(r.origin, r.target, r.msgId)
    }

    private fun notifyDropped(originId: String, recipientId: String, msgId: String) {
        val origin = connectionPool.get(originId) ?: return
        offerFrame(origin, RelayFrame.QueueState(id = msgId, to = recipientId, state = QueueEvent.DROPPED, ts = clock()))
    }

    // =====================================================================================================
    // FLASH_META
    // =====================================================================================================

    /**
     * Routes `FLASH_META` (typing / presence): to one device when [RelayFrame.Meta.to] is set, otherwise to the members
     * of the directory group named by `conversationId` (the sender must be a member: `ERR_NOT_MEMBER` otherwise, SEC-02),
     * otherwise to the connected peer named by `conversationId`. The forwarded frame's `from` is overwritten with the
     * authenticated sender. Meta is ephemeral: a recipient whose queue is full simply misses it.
     */
    private fun handleInboundMeta(origin: RelaySession, meta: RelayFrame.Meta) {
        val sender = origin.deviceId
        val forwarded = RelayFrameCodec.encode(meta.copy(from = sender))

        val directRecipient = meta.to
        if (!directRecipient.isNullOrBlank()) {
            offerEphemeral(directRecipient, forwarded)
            return
        }

        val convId = meta.conversationId
        if (convId.isBlank()) return

        val members = groups.membersOf(convId)
        if (members != null) {
            if (sender !in members) {
                offerFrame(origin, RelayFrame.Error(HubErrorCodes.ERR_NOT_MEMBER, MESSAGE_NOT_MEMBER))
                return
            }
            for (memberId in members) if (memberId != sender) offerEphemeral(memberId, forwarded)
            return
        }
        offerEphemeral(convId, forwarded)
    }

    private fun offerEphemeral(deviceId: String, text: String) {
        val target = connectionPool.get(deviceId) ?: return
        if (target.offer(text) == OfferResult.OK) {
            metrics.inc(RelayMetrics.FRAMES_OUT, 1, "kind" to "FLASH_META")
        }
    }

    // =====================================================================================================
    // Mailbox drain (REL-02, REL-07)
    // =====================================================================================================

    /**
     * Asks for [session]'s mailbox to be (re)drained. Safe to call from anywhere, any number of times: at most one
     * drain per session runs, and a request that arrives while one is finishing is never lost (the wake-up counter is
     * compared after the running flag is released).
     */
    public fun requestDrain(session: RelaySession) {
        session.wakeups.incrementAndGet()
        startDrainIfIdle(session)
    }

    private fun startDrainIfIdle(session: RelaySession) {
        if (!session.isOpen) return
        if (session.beginDrain()) {
            scope.launch { runDrain(session) }
        }
    }

    private suspend fun runDrain(session: RelaySession) {
        var failures = 0
        try {
            while (true) {
                val seen = session.wakeups.get()
                try {
                    drainPages(session)
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    failures++
                    metrics.inc(RelayMetrics.DB_ERRORS, 1, "op" to "drain")
                    logger.error("Mailbox drain for '{}' failed ({}): {}", LogSanitizer.clean(session.deviceId), failures, t.toString())
                    if (session.isOpen && failures < MAX_DRAIN_RETRIES) {
                        delay(DRAIN_RETRY_DELAY_MS)
                        continue
                    }
                    // The mailbox stays unreadable: cut the connection so the device reconnects and drains afresh,
                    // rather than letting live frames overtake rows we can not read.
                    session.fail("mailbox_unavailable")
                    session.abortDrain()
                    return
                }
                // Atomic with the live hand-off: either the drain is over and the session is released to live traffic,
                // or a wake-up arrived and the session stays `draining` for another pass.
                if (session.finishDrain(seen)) return
                failures = 0
            }
        } catch (e: CancellationException) {
            session.abortDrain()
            throw e
        }
    }

    /** Pushes every mailbox row after the session's cursor, page by page, until a page comes back empty. */
    private suspend fun drainPages(session: RelaySession) {
        while (session.isOpen) {
            val fetched = mailboxQueue.fetchPageDetailed(session.deviceId, session.cursor, policy.drainPageSize)
            reportQuarantined(fetched.quarantined)
            val page = fetched.messages
            // A page of only quarantined rows (the fetch gave up skipping) is not the end: ask again, the marked rows are excluded now.
            if (page.isEmpty() && fetched.quarantined.isEmpty()) return
            for (row in page) {
                val deliver = RelayFrame.Deliver(
                    from = row.origin, id = row.msgId, type = row.type, sentAt = row.sentAt,
                    payload = row.payload, replay = true, group = row.group,
                )
                if (!session.enqueue(RelayFrameCodec.encode(deliver))) {
                    session.fail("send_timeout")
                    return
                }
                session.cursor = row.seq
                metrics.inc(RelayMetrics.REDELIVERIES)
                metrics.inc(RelayMetrics.FRAMES_OUT, 1, "kind" to "FLASH_DELIVER")
            }
        }
    }

    /**
     * Drains [session]'s mailbox to completion before returning (tests and callers that must wait). Returns the number
     * of rows pushed. Production code uses [requestDrain].
     */
    public suspend fun drainMailboxForDevice(session: RelaySession): Int {
        var pushed = 0
        while (session.isOpen) {
            val fetched = mailboxQueue.fetchPageDetailed(session.deviceId, session.cursor, policy.drainPageSize)
            reportQuarantined(fetched.quarantined)
            val page = fetched.messages
            if (page.isEmpty() && fetched.quarantined.isEmpty()) break
            for (row in page) {
                val deliver = RelayFrame.Deliver(
                    from = row.origin, id = row.msgId, type = row.type, sentAt = row.sentAt,
                    payload = row.payload, replay = true, group = row.group,
                )
                if (!session.enqueue(RelayFrameCodec.encode(deliver))) return pushed
                session.cursor = row.seq
                pushed++
            }
        }
        return pushed
    }

    // =====================================================================================================
    // Session lifecycle
    // =====================================================================================================

    /**
     * Installs the `/flash-relay` WebSocket endpoint onto the Ktor routing hierarchy.
     */
    public fun registerRoute(route: Route) {
        route.webSocket(HubContract.PATH_RELAY) {
            handleSession(this, call)
        }
    }

    /**
     * Handles the full lifecycle of a connected WebSocket peer: admission, handshake, registration, the frame loop and
     * cleanup.
     */
    public suspend fun handleSession(session: WebSocketSession, call: ApplicationCall) {
        val remoteAddress = "${call.request.origin.remoteAddress}:${call.request.origin.remotePort}"
        val ipKey = clientKey(call)

        if (!accepting.get()) {
            metrics.inc(RelayMetrics.REJECTED_CONNECTIONS, 1, "why" to "shutdown")
            refuse(session, RelayFrame.Kick(reason = "server_shutdown"), CloseReason.Codes.GOING_AWAY, "shutdown")
            return
        }
        val refusal = limiter.tryAcquire(ipKey)
        if (refusal != null) {
            metrics.inc(RelayMetrics.REJECTED_CONNECTIONS, 1, "why" to refusal.name.lowercase())
            logger.warn("Refusing connection from {}: {} cap reached", remoteAddress, refusal)
            refuse(session, RelayFrame.Error(HubErrorCodes.ERR_RATE_LIMIT, MESSAGE_TOO_MANY_CONNECTIONS), CloseReason.Codes.TRY_AGAIN_LATER, "connection_cap")
            return
        }
        try {
            runSession(session, remoteAddress)
        } finally {
            limiter.release(ipKey)
        }
    }

    private suspend fun runSession(session: WebSocketSession, remoteAddress: String) {
        val auth = authenticator
        if (auth == null) {
            logger.error("Refusing connection from {}: no relay authenticator is configured", remoteAddress)
            metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to HubErrorCodes.ERR_AUTH)
            refuse(session, RelayFrame.Error(HubErrorCodes.ERR_AUTH, MESSAGE_AUTH_FAILED), CloseReason.Codes.VIOLATED_POLICY, HubErrorCodes.ERR_AUTH)
            return
        }

        // 1. CHALLENGE
        val challenge = RelayFrame.Challenge(serverId = serverId, serverNonce = nonceSource(), issuedAt = clock())
        session.send(Frame.Text(RelayFrameCodec.encode(challenge)))

        // 2. HELLO, within the deadline. The deadline covers the whole wait, however slowly the client trickles.
        val outcome = withTimeoutOrNull(handshakeTimeoutMs) { readHello(session) }
        val hello = when (outcome) {
            null -> {
                logger.warn("Handshake timeout from {}", remoteAddress)
                metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to HubErrorCodes.ERR_HANDSHAKE_TIMEOUT)
                refuse(session, RelayFrame.Error(HubErrorCodes.ERR_HANDSHAKE_TIMEOUT, MESSAGE_HANDSHAKE_TIMEOUT), CloseReason.Codes.VIOLATED_POLICY, HubErrorCodes.ERR_HANDSHAKE_TIMEOUT)
                return
            }
            is HelloOutcome.Refused -> {
                logger.warn("Handshake refused from {}: {}", remoteAddress, outcome.code)
                metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to outcome.code)
                refuse(session, RelayFrame.Error(outcome.code, MESSAGE_BAD_FRAME), CloseReason.Codes.VIOLATED_POLICY, outcome.code)
                return
            }
            is HelloOutcome.Received -> outcome.hello
        }

        // 3. AUTHENTICATE (+ the revocation state, by serial AND device id, as a second line of defence)
        var validUntilMs: Long? = null
        val principal = when (val result = auth.authenticate(challenge, hello, clock())) {
            is AuthResult.Accepted -> {
                validUntilMs = result.validUntilMs
                result.principal
            }
            is AuthResult.Rejected -> {
                logger.warn("Rejecting connection from {} (claimed '{}'): {} - {}", remoteAddress, LogSanitizer.clean(hello.deviceId), result.code, LogSanitizer.clean(result.logDetail, 200))
                metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to result.code)
                refuse(session, RelayFrame.Error(result.code, messageFor(result.code)), CloseReason.Codes.VIOLATED_POLICY, result.code)
                return
            }
        }
        if (isRevokedNow(principal.certSerial, principal.deviceId)) {
            logger.warn("Rejecting revoked device '{}' from {}", LogSanitizer.clean(principal.deviceId), remoteAddress)
            metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to HubErrorCodes.ERR_REVOKED)
            refuse(session, RelayFrame.Error(HubErrorCodes.ERR_REVOKED, MESSAGE_REVOKED), CloseReason.Codes.VIOLATED_POLICY, HubErrorCodes.ERR_REVOKED)
            return
        }
        if (!accepting.get()) {
            refuse(session, RelayFrame.Kick(reason = "server_shutdown"), CloseReason.Codes.GOING_AWAY, "shutdown")
            return
        }

        // 4. WELCOME, then register, then drain. WELCOME is queued first so no other frame can precede it, and the
        // session is `draining` from the start so nothing live overtakes the mailbox.
        val relaySession = RelaySession(
            deviceId = principal.deviceId,
            friendlyName = sanitizeName(hello.name, principal.deviceId),
            role = principal.role,
            remoteAddress = remoteAddress,
            websocketSession = session,
            principal = principal,
            features = hello.features.toSet(),
            outboundCapacity = policy.outboundQueueCapacity,
            sendTimeoutMs = policy.sendTimeoutMs,
        )
        relaySession.draining = true
        relaySession.validUntilMs = validUntilMs
        relaySession.offer(
            RelayFrameCodec.encode(RelayFrame.Welcome(principal = principal, serverTime = clock(), limits = RelayLimits())),
        )
        metrics.inc(RelayMetrics.HANDSHAKES, 1, "result" to "ok")
        register(relaySession)
        requestDrain(relaySession)
        scheduleExpiry(relaySession)

        try {
            for (frame in session.incoming) {
                when (frame) {
                    is Frame.Text -> {
                        if (frame.data.size > HubContract.MAX_FRAME_BYTES) {
                            metrics.inc(RelayMetrics.FRAMES_IN, 1, "kind" to "rejected")
                            offerFrame(relaySession, RelayFrame.Error(HubErrorCodes.ERR_TOO_LARGE, MESSAGE_TOO_LARGE))
                            relaySession.closeGracefully("too_large", CloseReason.Codes.TOO_BIG)
                            break
                        }
                        processFrame(relaySession, frame.readText())
                    }
                    is Frame.Binary -> {
                        offerFrame(relaySession, RelayFrame.Error(HubErrorCodes.ERR_PROTOCOL, MESSAGE_UNEXPECTED_FRAME))
                        noteRefusal(relaySession)
                    }
                    else -> Unit // control frames are handled by the WebSocket layer
                }
                if (!relaySession.isOpen) break
            }
        } catch (e: CancellationException) {
            throw e
        } catch (error: Throwable) {
            logger.debug("Session error for '{}': {}", LogSanitizer.clean(principal.deviceId), error.toString())
        } finally {
            sessionEnded(relaySession, if (relaySession.isOpen) "disconnected" else relaySession.reasonClosed)
        }
    }

    /**
     * Cleanup that runs exactly once per session, from whichever path ends it (socket closed, slow consumer, write
     * failure, kick, shutdown): removes it from the pool ONLY IF it is still the registered one (REL-01), and moves its
     * unacknowledged live messages into the mailbox (REL-02) so a half-open connection loses nothing.
     */
    internal fun sessionEnded(session: RelaySession, reason: String) {
        if (!session.ended.compareAndSet(false, true)) return
        session.expiryJob?.cancel()
        connectionPool.unregister(session)
        metrics.inc(RelayMetrics.SESSIONS_CLOSED, 1, "reason" to reasonLabel(reason))
        val pending = session.inFlight.entries.map { it.key to it.value }.sortedBy { it.second.handedAtNanos }
        session.inFlight.clear()
        if (session.isOpen) session.fail(reason.ifBlank { "disconnected" }, CloseReason.Codes.NORMAL)
        if (pending.isNotEmpty()) {
            scope.launch { spill(session.deviceId, pending) }
        }
    }

    private suspend fun spill(deviceId: String, pending: List<Pair<String, InFlight>>) {
        for ((msgId, flight) in pending) {
            val result = try {
                mailboxQueue.enqueue(MailboxMessage(flight.origin, deviceId, msgId, flight.type, flight.payload, flight.sentAt, flight.group))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                EnqueueResult.Failed(t)
            }
            if (result.isStored) {
                metrics.inc(RelayMetrics.MAILBOX_EVENTS, 1, "event" to "spilled")
            } else {
                // F60 / ERROR-773: Do not call mailboxQueue.noteDropped() here because
                // mailboxQueue.enqueue already increments the dropped counter and metric
                // for non-stored outcomes (QuotaExceeded, Failed).
                notifyDropped(flight.origin, deviceId, msgId)
            }
        }
        // The device may already be back on a new connection: make sure it picks the spilled rows up.
        connectionPool.get(deviceId)?.let { requestDrain(it) }
    }

    private sealed interface HelloOutcome {
        data class Received(val hello: RelayFrame.Hello) : HelloOutcome
        data class Refused(val code: String) : HelloOutcome
    }

    private suspend fun readHello(session: WebSocketSession): HelloOutcome {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull() ?: return HelloOutcome.Refused(HubErrorCodes.ERR_PROTOCOL)
            if (frame !is Frame.Text) continue
            if (frame.data.size > HubContract.MAX_HANDSHAKE_FRAME_BYTES) return HelloOutcome.Refused(HubErrorCodes.ERR_TOO_LARGE)
            return when (val decoded = RelayFrameCodec.decodeResult(frame.readText(), HubContract.MAX_HANDSHAKE_FRAME_BYTES)) {
                is RelayFrameCodec.Decoded.Rejected -> HelloOutcome.Refused(codeFor(decoded.reason))
                is RelayFrameCodec.Decoded.Ok -> when (val hello = decoded.frame) {
                    is RelayFrame.Hello -> HelloOutcome.Received(hello)
                    else -> HelloOutcome.Refused(HubErrorCodes.ERR_PROTOCOL)
                }
            }
        }
    }

    // =====================================================================================================
    // Revocation (SEC-02, SEC-05)
    // =====================================================================================================

    private fun isRevokedNow(serial: Long?, deviceId: String): Boolean {
        if (revocation.isRevoked(serial, deviceId)) return true
        val list = revocationListProvider?.invoke() ?: return false
        return if (serial != null) list.isRevoked(serial, deviceId) else list.revokesDevice(deviceId)
    }

    private fun isRevoked(session: RelaySession): Boolean = isRevokedNow(session.principal?.certSerial, session.deviceId)

    /** `RevocationService.onRevoked` handler: drops the device's live connection right away if it is now revoked. */
    public suspend fun kickIfRevoked(deviceId: String, reason: String) {
        val session = connectionPool.get(deviceId) ?: return
        if (isRevoked(session)) kickRevoked(session, reason)
    }

    // ---- credential expiry (REV-05) --------------------------------------------------------------------

    private fun hasExpired(session: RelaySession): Boolean {
        val until = session.validUntilMs ?: return false
        return clock() > until + policy.sessionExpirySkewMs
    }

    /** Closes the session with `ERR_EXPIRED` when its certificate or delegation ends: a session must not outlive the credential that opened it. */
    private fun scheduleExpiry(session: RelaySession) {
        val until = session.validUntilMs ?: return
        session.expiryJob = scope.launch {
            while (session.isOpen) {
                val wait = until + policy.sessionExpirySkewMs - clock()
                if (wait <= 0L) break
                kotlinx.coroutines.delay(minOf(wait, 5_000L).coerceAtLeast(1L))
            }
            if (session.isOpen) expire(session)
        }
    }

    private suspend fun expire(session: RelaySession) {
        logger.info("Closing session of '{}': its certificate or the hub delegation expired", LogSanitizer.clean(session.deviceId))
        offerFrame(session, RelayFrame.Error(HubErrorCodes.ERR_EXPIRED, MESSAGE_EXPIRED))
        session.closeGracefully("expired", CloseReason.Codes.VIOLATED_POLICY)
    }

    private suspend fun kickRevoked(session: RelaySession, reason: String) {
        logger.warn("Disconnecting device '{}': certificate revoked while connected", LogSanitizer.clean(session.deviceId))
        offerFrame(session, RelayFrame.Revoked(reason = reason, serial = session.principal?.certSerial))
        session.closeGracefully("revoked", CloseReason.Codes.VIOLATED_POLICY)
    }

    /**
     * Sends the device's live connection `FLASH_KICK` ([reason]) and closes it. Returns false when it is not connected.
     */
    public suspend fun kick(deviceId: String, reason: String): Boolean {
        val session = connectionPool.get(deviceId) ?: return false
        offerFrame(session, RelayFrame.Kick(reason = reason))
        session.closeGracefully(reason, CloseReason.Codes.NORMAL)
        return true
    }

    /**
     * Broadcasts an announcement event to all active sessions that negotiated [HubContract.FEATURE_ANNOUNCEMENTS_V1]
     * and satisfy scope visibility.
     *
     * @return Number of sessions the frame was queued for.
     */
    public fun broadcastAnnouncement(event: RelayFrame.AnnouncementEvent): Int {
        val text = RelayFrameCodec.encode(event)
        return connectionPool.broadcastText(text) { session ->
            if (!session.features.contains(HubContract.FEATURE_ANNOUNCEMENTS_V1)) {
                false
            } else if (event.scope == "org") {
                true
            } else {
                val role = session.role
                val isOrgAdmin = role.equals("admin", ignoreCase = true) || role.equals("ORG_ADMIN", ignoreCase = true)
                val memberDept = session.principal?.departmentId
                isOrgAdmin || (event.departmentId != null && memberDept == event.departmentId)
            }
        }
    }

    // =====================================================================================================
    // Shutdown
    // =====================================================================================================

    /**
     * Graceful shutdown (REL-13): stop admitting connections, tell every session `FLASH_KICK server_shutdown` and close
     * them IN PARALLEL, wait for their unacknowledged messages to reach the mailbox, then stop the router's workers.
     * Idempotent.
     */
    public suspend fun shutdown(timeoutMs: Long = policy.shutdownTimeoutMs) {
        if (!accepting.compareAndSet(true, false)) return
        connectionPool.closeAll("server_shutdown", timeoutMs)
        withTimeoutOrNull(timeoutMs) { scope.coroutineContext.job.children.toList().joinAll() }
        scope.cancel()
    }

    // =====================================================================================================
    // Helpers
    // =====================================================================================================

    private suspend fun refuse(session: WebSocketSession, frame: RelayFrame, code: CloseReason.Codes, why: String) {
        try {
            session.send(Frame.Text(RelayFrameCodec.encode(frame)))
            session.close(CloseReason(code, why.take(100)))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // The peer already went away.
        }
    }

    /** Queues [frame] for [session] without waiting; a full queue makes the session a slow consumer and closes it. */
    private fun offerFrame(session: RelaySession, frame: RelayFrame) {
        when (session.offer(RelayFrameCodec.encode(frame))) {
            OfferResult.OK -> metrics.inc(RelayMetrics.FRAMES_OUT, 1, "kind" to kindOf(frame))
            OfferResult.FULL -> session.fail("slow_consumer")
            OfferResult.CLOSED -> Unit
        }
    }

    private fun clientKey(call: ApplicationCall): String =
        com.transfer.flash.server.auth.RateKeys.normalise(clientAddressOf(call))

    private fun kindOf(frame: RelayFrame): String = when (frame) {
        is RelayFrame.Challenge -> "FLASH_HUB_CHALLENGE"
        is RelayFrame.Hello -> "FLASH_HUB_HELLO"
        is RelayFrame.Welcome -> "FLASH_HUB_WELCOME"
        is RelayFrame.Error -> "FLASH_ERROR"
        is RelayFrame.Send -> "FLASH_RELAY"
        is RelayFrame.Deliver -> "FLASH_DELIVER"
        is RelayFrame.RecvAck -> "FLASH_RECV_ACK"
        is RelayFrame.Ack -> "FLASH_ACK"
        is RelayFrame.QueueState -> "FLASH_QUEUE_STATE"
        is RelayFrame.Meta -> "FLASH_META"
        is RelayFrame.Ping -> "FLASH_PING"
        is RelayFrame.Pong -> "FLASH_PONG"
        is RelayFrame.Revoked -> "FLASH_REVOKED"
        is RelayFrame.Kick -> "FLASH_KICK"
        is RelayFrame.AnnouncementEvent -> "FLASH_ANNOUNCEMENT"
    }

    private fun reasonLabel(reason: String): String = when (reason) {
        "disconnected", "superseded", "server_shutdown", "slow_consumer", "send_timeout", "send_failed", "revoked",
        "rate_limit", "too_large", "shutdown_timeout",
        -> reason
        else -> "other"
    }

    private fun codeFor(reason: RelayFrameCodec.RejectReason): String = when (reason) {
        RelayFrameCodec.RejectReason.TOO_LARGE, RelayFrameCodec.RejectReason.PAYLOAD_TOO_LARGE -> HubErrorCodes.ERR_TOO_LARGE
        RelayFrameCodec.RejectReason.UNSUPPORTED_VERSION -> HubErrorCodes.ERR_UNSUPPORTED_VERSION
        RelayFrameCodec.RejectReason.INVALID_FIELD -> HubErrorCodes.ERR_BAD_REQUEST
        RelayFrameCodec.RejectReason.MALFORMED, RelayFrameCodec.RejectReason.UNKNOWN_TYPE -> HubErrorCodes.ERR_PROTOCOL
    }

    private fun messageFor(code: String): String = when (code) {
        HubErrorCodes.ERR_REVOKED -> MESSAGE_REVOKED
        HubErrorCodes.ERR_EXPIRED -> MESSAGE_EXPIRED
        else -> MESSAGE_AUTH_FAILED
    }

    private fun sanitizeName(raw: String, fallback: String): String {
        val cleaned = raw.filter { it.code >= 0x20 && it.code != 0x7F }.trim().take(HubContract.MAX_NAME_LENGTH)
        return cleaned.ifBlank { fallback }
    }

    public companion object {
        // Fixed sentences: the wire never carries exception text or a certificate detail (SEC-16).
        public const val MESSAGE_AUTH_FAILED: String = "Authentication failed."
        public const val MESSAGE_REVOKED: String = "This device has been removed from the organisation."
        public const val MESSAGE_EXPIRED: String = "This device's enrolment has expired. Renew it."
        public const val MESSAGE_HANDSHAKE_TIMEOUT: String = "The handshake did not complete in time."
        public const val MESSAGE_BAD_FRAME: String = "The request could not be processed."
        public const val MESSAGE_UNEXPECTED_FRAME: String = "That frame is not accepted here."
        public const val MESSAGE_TOO_LARGE: String = "The frame is larger than this hub accepts."
        public const val MESSAGE_RATE_LIMIT: String = "Too many frames. Slow down."
        public const val MESSAGE_TOO_MANY_CONNECTIONS: String = "Too many connections. Try again later."
        public const val MESSAGE_NOT_MEMBER: String = "You are not a member of that group."

        private const val MAX_DRAIN_RETRIES: Int = 5
        private const val DRAIN_RETRY_DELAY_MS: Long = 1_000L
    }
}

/** Random single-use challenge nonce source (32 bytes, Base64URL). */
public object SecureNonce {
    private val random = SecureRandom()

    public fun next(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
