@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.core.enterprise

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.common.logging.FlashProbe
import com.transfer.flash.core.common.protocol.Base64
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.runSuspendCatching
import com.transfer.flash.core.hubprotocol.HubContract
import com.transfer.flash.core.hubprotocol.certs.RevocationList
import com.transfer.flash.core.hubprotocol.rest.HubRestCodec
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** Version offer, request and one signed list over a negotiated, authenticated LAN session. */
@FlashInternalApi
public class HubRevocationGossip(
    private val cache: HubRevocationCache,
    private val onApplied: suspend () -> Unit = {},
    private val elapsedMs: () -> Long = TimeSource.Monotonic.markNow().let { start ->
        { start.elapsedNow().inWholeMilliseconds }
    },
) {
    private class Window(val session: Any, val start: Long) {
        var offered: Long? = null
        var requested: Long? = null
        var completed = false
    }

    private val lock = Mutex()
    private val windows = HashMap<Pair<String, String>, Window>()

    public fun session(
        peerId: String,
        negotiated: Boolean,
        authenticated: (root: String) -> Boolean,
        send: suspend (String) -> Unit,
    ): Session = Session(peerId, negotiated, authenticated, send)

    public inner class Session internal constructor(
        private val peerId: String,
        private val negotiated: Boolean,
        private val authenticated: (root: String) -> Boolean,
        private val send: suspend (String) -> Unit,
    ) {
        private val token = Any()

        /** Called in the session job. Picks up revocations even on a connection that stays open. */
        public suspend fun announceWhileConnected() {
            if (!negotiated) return
            while (true) {
                announce()
                delay(POLL_MS)
            }
        }

        public suspend fun announce() {
            val root = authenticatedScope() ?: return
            val version = cache.current()?.version ?: return
            val offer = lock.withLock {
                val window = window(root, peerId, token, create = true) ?: return
                if (window.offered != null || window.requested != null || window.completed) return
                window.offered = version
                frame("offer", root, version)
            }
            sendIfCurrent(root, offer)
        }

        public suspend fun receive(text: String): Boolean {
            if (text != PREFIX && !text.startsWith(PREFIX_SPACE)) return false
            if (text.length > MAX_FRAME_CHARS) return true
            val root = authenticatedScope() ?: return true
            val fields = FlashTextFraming.parseFields(text, PREFIX) ?: return true
            if (fields["root"] != root) return true
            val version = fields["v"]?.toLongOrNull()?.takeIf { it > 0 } ?: return true
            when (fields["op"]) {
                "offer" -> if (fields.size == 3) onOffer(root, version)
                "request" -> if (fields.size == 3) onRequest(root, version)
                "list" -> if (fields.size == 4) fields["body"]?.let { onList(root, version, it) }
            }
            return true
        }

        private suspend fun onOffer(root: String, version: Long) {
            if (version <= (cache.current()?.version ?: 0)) return
            val request = lock.withLock {
                val window = window(root, peerId, token, create = true) ?: return
                if (window.requested != null || window.completed) return
                window.requested = version
                frame("request", root, version)
            }
            sendIfCurrent(root, request)
        }

        private suspend fun onRequest(root: String, version: Long) {
            val list = lock.withLock {
                val window = window(root, peerId, token, create = false) ?: return
                if (window.offered != version || window.requested != null || window.completed) return
                window.completed = true
                cache.current()?.takeIf { it.version == version }
            } ?: return
            val bytes = HubRestCodec.encode(list).encodeToByteArray()
            if (bytes.size <= MAX_LIST_BYTES) sendIfCurrent(root, frame("list", root, version, Base64.encode(bytes)))
        }

        private suspend fun onList(root: String, version: Long, encoded: String) {
            lock.withLock {
                val window = window(root, peerId, token, create = false) ?: return
                if (window.requested != version || window.completed) return
                window.completed = true
            }
            val list = decode(encoded)?.takeIf { it.version == version }
            val result = if (list != null && authenticatedScope() == root) cache.accept(list, root) else HubRevocationCache.Acceptance.INVALID
            FlashProbe.emit("org.revocation.lan", "result" to result.name, "version" to version)
            if (result == HubRevocationCache.Acceptance.APPLIED && runSuspendCatching { onApplied() }.isFailure) {
                FlashProbe.emit("org.revocation.storage", "operation" to "project")
            }
        }

        private fun authenticatedScope(): String? = if (negotiated) cache.scopeId()?.takeIf(authenticated) else null

        private suspend fun sendIfCurrent(root: String, text: String) {
            if (authenticatedScope() == root) runSuspendCatching { send(text) }
        }
    }

    /** Called under [lock]. Reconnects cannot borrow a pending request or reset a peer's budget. */
    private fun window(root: String, peer: String, token: Any, create: Boolean): Window? {
        val now = elapsedMs()
        val key = root to peer
        val previous = windows[key]
        if (previous != null && now - previous.start < WINDOW_MS) return previous.takeIf { it.session === token }
        if (!create) return null
        if (windows.size >= MAX_WINDOWS) {
            windows.entries.removeAll { now - it.value.start >= WINDOW_MS }
            if (windows.size >= MAX_WINDOWS) return null
        }
        return Window(token, now).also { windows[key] = it }
    }

    private fun frame(op: String, root: String, version: Long, body: String? = null): String {
        val fields = listOf("op" to op, "root" to root, "v" to version.toString())
        return FlashTextFraming.encodeFields(PREFIX, if (body == null) fields else fields + ("body" to body))
    }

    private fun decode(encoded: String): RevocationList? {
        if (encoded.isEmpty() || encoded.length > MAX_BASE64_CHARS) return null
        val json = try {
            val bytes = Base64.decode(encoded)
            if (bytes.size > MAX_LIST_BYTES) return null
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: CharacterCodingException) {
            return null
        }
        if (jsonTooDeep(json)) return null
        return HubRestCodec.decodeOrNull(json)
    }

    public companion object {
        public const val FEATURE: String = "rvg1"
        public const val WINDOW_MS: Long = 10 * 60 * 1000L
        private const val PREFIX = "FLASH_RVG"
        private const val PREFIX_SPACE = "$PREFIX "
        private const val MAX_LIST_BYTES = HubContract.MAX_REST_BODY_BYTES
        private const val MAX_BASE64_CHARS = ((MAX_LIST_BYTES + 2) / 3) * 4
        private const val MAX_FRAME_CHARS = MAX_BASE64_CHARS + 256
        private const val MAX_WINDOWS = 5_000
        private const val POLL_MS = 30_000L
    }
}
