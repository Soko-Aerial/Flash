// The framing helper carries the library's @FlashInternalApi marker, and this harness opts in on
// purpose: `FlashTextFraming.parseFields(text, "FLASH_CALL") != null` is the EXACT expression the
// facade's calling branch evaluates (Flash.kt), so a copy of it here would prove nothing. The app
// module opts in for the same reason (DiscoveryEngineHolder). This is a library-validation harness,
// not a model of what a downstream app should depend on.
@file:OptIn(com.transfer.flash.core.common.annotation.FlashInternalApi::class)

package com.transfer.flash.sample.consumer

import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.model.FlashDeviceId
import com.transfer.flash.core.common.protocol.FlashTextFraming
import com.transfer.flash.core.common.result.FlashError
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.discovery.FlashDiscovery
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.engine.DefaultFlashEngine
import com.transfer.flash.core.engine.FlashEngine
import com.transfer.flash.core.messaging.EmptyFlashChatRepository
import com.transfer.flash.core.messaging.protocol.DirectMessageActionCodec
import com.transfer.flash.core.messaging.protocol.GroupFrameCodec
import com.transfer.flash.core.messaging.protocol.GroupWireFrame
import com.transfer.flash.core.messaging.protocol.PttFrameCodec
import com.transfer.flash.core.messaging.protocol.PttPingFrame
import com.transfer.flash.core.messaging.protocol.PttSessionCodec
import com.transfer.flash.core.messaging.protocol.PttSessionFrame
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashNetwork
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.persistence.settings.FlashSettingsDataStore
import com.transfer.flash.core.security.trust.FlashTrustStore
import com.transfer.flash.core.transfer.FlashTransferRepository
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferId
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the umbrella facade's calling seam, run from the one consumer shape the
 * `compileOnly` hazard applies to (ADR-033; `logs/errors.md` HAZARD-002).
 *
 * ## Why this test lives in `:sample:consumer` and not in `:core:engine`
 *
 * `:core:engine` declares `compileOnly(project(":core:calling"))`, so `FlashCalling` is on the
 * engine's own compile classpath and on both of its test classpaths (`androidHostTest` even adds
 * `implementation(project(":core:calling"))` for its stub). This module depends on `:core:engine`
 * and nothing else, so `com.transfer.flash.core.calling` is genuinely absent from its runtime
 * classpath — the only place in this repository where the hazard can be observed at all.
 *
 * **Do not add `core-calling` to this module.** Doing so would not "fix" anything; it would delete
 * the property this test exists to check, and every assertion below would stop proving anything.
 *
 * ## What is covered — all on the real consumer classpath, no Robolectric
 *
 * 1. **The precondition, asserted rather than assumed**: `FlashCalling` is not loadable, its class
 *    file is not a resource, and neither is another class of the same module. If this module ever
 *    gained the dependency, these tests fail instead of quietly proving nothing.
 * 2. **Class level**: every declared member type of the classes `Flash.create` runs on the inbound
 *    text path — `FlashKt` (home of the `FLASH_CALL` recognition helper), `Wiring` (the private
 *    class owning `handleInboundText`) and `Flash` (the entry point) — resolves here. The JVM
 *    resolves every signature; a calling type in any of them would be a `NoClassDefFoundError`
 *    thrown by these assertions.
 * 3. **Bytecode level**: no `.class` file of the engine package references
 *    `com/transfer/flash/core/calling` except the three the attach seam is allowed to own. That
 *    covers method *bodies*, which the signature scan cannot see, and it is the runtime form of the
 *    published-AAR grep the hazard entry was argued from.
 * 4. **Behaviour**: a hand-assembled engine (the documented A10 path; `Flash.create` needs an
 *    Application `Context` and the encrypted Room DB, which a host-JVM test cannot supply) answers
 *    the three calling entry points with `false`/no-op — never with a `Throwable`, `Error`
 *    included — for real chat, `FLASH_CALL` and PTT frames; `engine.ptt` is null; `close()` works.
 * 5. **Separation**: a real `FLASH_CALL` frame is recognized by the calling branch's prefix test and
 *    rejected by every other family parser, and no chat/PTT/group frame is recognized by it.
 *
 * ## What this test does NOT prove
 *
 * - It does **not** execute `Wiring.handleInboundText`'s control flow. That class is file-private
 *   in `:core:engine` and its constructor takes an `android.content.Context`, so no consumer can
 *   reach it and this repository deliberately has no Robolectric. The dispatcher's *ordering* stays
 *   covered by `:core:engine`'s own host tests plus the parser-level assertions here — not by
 *   running the dispatcher itself.
 * - It runs on a **host JVM: ART/device behaviour is not covered.** HAZARD-002's failure mode is a
 *   runtime resolution error; HotSpot's lazy resolution is strong evidence but not a substitute for
 *   a consumer app without `core-calling` running on a device.
 * - It never runs `Flash.create` and never attaches a real `FlashCalling`.
 */
class UmbrellaFacadeContractTest {

    private companion object {
        const val PEER = "device-b"
        const val ENGINE_PACKAGE = "com.transfer.flash.core.engine"

        /** The package whose absence from this classpath is the entire precondition. */
        const val CALLING_PACKAGE = "com.transfer.flash.core.calling"

        /**
         * The token the facade's calling branch gates on. `CallFrameCodec.PREFIX` cannot be used
         * here — it lives in `:core:calling`, which this module must not have — so the literal is
         * written out, exactly as this consumer would have to.
         */
        const val CALL_PREFIX = "FLASH_CALL"

        const val SENT_AT = 1_757_500_000_000L

        /**
         * The only engine classes allowed to reference `:core:calling`: the interface that declares
         * the seam and the implementation plus its attach lambda, which is what ADR-033 §4 describes
         * and what the published AAR was grepped for. If a future change makes another engine class
         * reference the package, the scan below fails and names it.
         */
        val CALLING_REFERRERS: Set<String> = setOf(
            "FlashEngine.class",
            "DefaultFlashEngine.class",
            "DefaultFlashEngine\$attachCalling\$1\$1.class",
        )
    }

    private val loader: ClassLoader
        get() = requireNotNull(FlashEngine::class.java.classLoader) {
            "the engine has no class loader — every assertion below would be vacuous"
        }

    // ─────────────────────────── 1. the precondition ───────────────────────────

    @Test
    fun `precondition — the calling module is absent from this consumer's runtime classpath`() {
        val loader = loader

        // The same loader finds the module under test, so an absence below is not a loader artifact.
        assertNotNull(
            "the engine itself must be loadable through this loader",
            Class.forName("$ENGINE_PACKAGE.DefaultFlashEngine", false, loader),
        )

        val typeFailure = runCatching { Class.forName("$CALLING_PACKAGE.FlashCalling", false, loader) }
            .exceptionOrNull()
        assertTrue(
            "PREPROOF BROKEN: FlashCalling is loadable, so this module is no longer the consumer " +
                "shape HAZARD-002 applies to (got: $typeFailure)",
            typeFailure is ClassNotFoundException,
        )
        assertNull(
            "PREPROOF BROKEN: the calling class file is on this classpath as a resource",
            loader.getResource("${CALLING_PACKAGE.replace('.', '/')}/FlashCalling.class"),
        )

        // One more class from the same module, so a rename of FlashCalling alone cannot fake this.
        val codecFailure = runCatching { Class.forName("$CALLING_PACKAGE.protocol.CallFrameCodec", false, loader) }
            .exceptionOrNull()
        assertTrue(
            "PREPROOF BROKEN: CallFrameCodec is loadable without adding core-calling (got: $codecFailure)",
            codecFailure is ClassNotFoundException,
        )
    }

    // ────────────────────── 2/3. linkage of the dispatched classes ──────────────────────

    @Test
    fun `classes the facade executes on every inbound text frame resolve every member type`() {
        val loader = loader
        val engineClasses = engineClassBytes()

        listOf(
            "$ENGINE_PACKAGE.FlashKt", // isCallFrameText: the recognition half of the calling branch
            "$ENGINE_PACKAGE.Wiring", // the private class owning handleInboundText / handleInboundBinary
            "$ENGINE_PACKAGE.Flash", // `object Flash`, the entry point a consumer calls
        ).forEach { className ->
            val cls = Class.forName(className, false, loader)
            assertTrue(
                "$className declares no members at all — the resolution scan would prove nothing",
                cls.declaredMethods.isNotEmpty() || cls.declaredFields.isNotEmpty(),
            )
            cls.declaredMethods.forEach { method ->
                assertNull(
                    "$className.${method.name}: JVM type resolution must not need :core:calling",
                    runCatching { method.parameterTypes; method.returnType }.exceptionOrNull(),
                )
            }
            cls.declaredFields.forEach { field ->
                assertNull(
                    "$className.${field.name}: JVM type resolution must not need :core:calling",
                    runCatching { field.type }.exceptionOrNull(),
                )
            }
        }

        assertTrue("no engine class files were found to scan", engineClasses.isNotEmpty())
    }

    @Test
    fun `only the attach seam references the calling package — bytecode, method bodies included`() {
        val referencing = engineClassBytes()
            .filterValues { bytes -> bytes.toString(Charsets.ISO_8859_1).contains(CALLING_PACKAGE.replace('.', '/')) }
            .keys

        assertEquals(
            "engine classes whose bytes reference :core:calling (a new one here means code a " +
                "non-calling consumer could execute now depends on a class that is not on its " +
                "classpath — see ADR-033 §4)",
            CALLING_REFERRERS,
            referencing,
        )
    }

    @Test
    fun `the calling-typed members cannot even be enumerated without the dependency`() {
        val loader = loader

        // The confinement above is asserted on bytes; this is the same boundary seen from the JVM.
        // Enumerating `calls`/`attachCalling` builds Method objects, which resolves their declared
        // types, so it fails before any consumer code could call them. (Observed on this JDK:
        // NoClassDefFoundError out of Class.getDeclaredMethods0.)
        listOf("$ENGINE_PACKAGE.FlashEngine", "$ENGINE_PACKAGE.DefaultFlashEngine").forEach { className ->
            val failure = runCatching { Class.forName(className, false, loader).declaredMethods }.exceptionOrNull()
            assertTrue(
                "$className: expected the declared members to be unresolvable without :core:calling, " +
                    "got: $failure",
                failure is NoClassDefFoundError && failure.message?.contains("FlashCalling") == true,
            )
        }

        // The routing entry points are typed without the type, which is what lets them work here —
        // but there is no reflective escape hatch around the boundary either: every member lookup on
        // these two classes runs the same enumeration, so even
        // `getDeclaredMethod("onInboundCallText", String, String, Continuation)` fails on this
        // classpath. A direct, compiled call works (the behaviour tests call exactly that method);
        // reflection is not a workaround, adding the dependency is.
    }

    // ─────────────────────────── 4. behaviour ───────────────────────────

    @Test
    fun `an unattached facade consumes-or-drops every inbound frame family instead of throwing`() {
        val engine = newEngine()
        try {
            (nonCallFrames() + callFrames()).forEach { (label, frame) ->
                val outcome = driveCallSeam(engine, frame)
                assertNull(
                    "$label threw on the unattached facade (the NoClassDefFoundError family): " +
                        outcome.exceptionOrNull(),
                    outcome.exceptionOrNull(),
                )
                assertFalse(
                    "$label must be consumed-or-dropped, never consumed, while nothing is attached",
                    outcome.getOrDefault(true),
                )
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `the unattached state is honest — ptt is null and signaling lifecycle calls are safe no-ops`() {
        val engine = newEngine()
        try {
            assertNull("no PTT engine was attached", engine.ptt)

            // The sessions collector calls these on every session edge, calling or not.
            assertNull(
                "onCallSignalingLost must be a no-op without an attached engine",
                runCatching { engine.onCallSignalingLost(PEER) }.exceptionOrNull(),
            )
            assertNull(
                "onCallSignalingRestored must be a no-op without an attached engine",
                runCatching { engine.onCallSignalingRestored(PEER) }.exceptionOrNull(),
            )
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a consumer that never attached calling can close the engine`() {
        val engine = newEngine()
        assertNull(
            "close() must not resolve a calling type on a consumer without :core:calling " +
                "(the README's finally block runs on exactly this engine)",
            runCatching { engine.close() }.exceptionOrNull(),
        )
        assertNull("close() is documented idempotent", runCatching { engine.close() }.exceptionOrNull())
    }

    @Test
    fun `the calling-typed members stay unusable without the dependency — the documented boundary`() {
        // `engine.calls` / `engine.attachCalling(...)` do not even compile in this module: the
        // getter's return type is a class that is not on this compile classpath. That compile-time
        // failure is the intended contract — a consumer that places calls adds core-calling, which
        // the README instructs. Reflection shows the same boundary, and pins it so a future cleanup
        // cannot silently widen the seam into code a non-calling consumer runs:
        val failure = runCatching { FlashEngine::class.java.getMethod("getCalls") }.exceptionOrNull()
        assertTrue(
            "expected the `calls` getter to be unresolvable here (ADR-033 §4), got: $failure",
            failure is NoClassDefFoundError && failure.message?.contains("FlashCalling") == true,
        )
    }

    // ─────────────────────── 5. frame-family separation ───────────────────────

    @Test
    fun `a call frame is recognized by the calling branch and rejected by every other parser`() {
        callFrames().forEach { (label, call) ->
            assertNotNull(
                "$label must be recognized by the calling branch " +
                    "(Flash.kt: parseFields(text, CALL_PREFIX) != null)",
                FlashTextFraming.parseFields(call, CALL_PREFIX),
            )

            // The dispatcher's other branches all gate on the first token, exactly like these real
            // parsers do — so a recognized-and-dropped call frame can never be parsed by one of them.
            listOf("FLASH_MSG", "FLASH_RCPT", "FLASH_READ", "FLASH_REACT", "FLASH_TYPING", "FLASH_XFER")
                .forEach { prefix ->
                    assertNull("$prefix must not accept a call frame", FlashTextFraming.parseFields(call, prefix))
                }
            assertNull(GroupFrameCodec.decode(call))
            assertNull(DirectMessageActionCodec.decode(call))
            assertNull(PttFrameCodec.decode(call))
            assertNull(PttSessionCodec.decode(call))
        }
    }

    @Test
    fun `no chat, PTT or group frame can be swallowed by the calling branch`() {
        nonCallFrames().forEach { (label, frame) ->
            assertNull(
                "the calling branch must not recognize $label as a call frame",
                FlashTextFraming.parseFields(frame, CALL_PREFIX),
            )
        }

        // Exact-token match, not a raw `startsWith`: a frame about calls is not a call frame.
        assertNull(FlashTextFraming.parseFields("FLASH_CALLS action=invite from=$PEER", CALL_PREFIX))
        assertNull(FlashTextFraming.parseFields("FLASH_CALL-TRACE action=invite", CALL_PREFIX))

        // And the families really are families: their own real decoders accept their own frames.
        assertNotNull(PttFrameCodec.decode(pttPingFrame()))
        assertNotNull(PttSessionCodec.decode(pttSessionStartFrame()))
        assertNotNull(GroupFrameCodec.decode(groupMessageFrame()))
        assertNotNull(FlashTextFraming.parseFields(chatTextFrame(), "FLASH_MSG"))
        assertNotNull(DirectMessageActionCodec.decode(deleteActionFrame()))
    }

    // ─────────────────────────── fixtures ───────────────────────────

    /**
     * Every inbound *text* frame family that is NOT calling signaling, with the label the assertions
     * report. Keys are ordered for stable failure messages.
     */
    private fun nonCallFrames(): Map<String, String> = linkedMapOf(
        "a chat text frame" to chatTextFrame(),
        "a delete-for-everyone frame" to deleteActionFrame(),
        "a delivery receipt frame" to FlashTextFraming.encodeFields(
            "FLASH_RCPT",
            listOf("messageId" to "msg-1", "conversationId" to "conv-1", "memberId" to PEER, "deliveredAt" to "$SENT_AT"),
        ),
        "a read receipt frame" to FlashTextFraming.encodeFields(
            "FLASH_READ",
            listOf("conversationId" to "conv-1", "memberId" to PEER, "upToMessageId" to "msg-1", "readAt" to "$SENT_AT"),
        ),
        "a reaction frame" to FlashTextFraming.encodeFields(
            "FLASH_REACT",
            listOf("messageId" to "msg-1", "conversationId" to "conv-1", "memberId" to PEER, "emoji" to "\uD83D\uDC4D", "isAdded" to "true"),
        ),
        "a typing frame" to FlashTextFraming.encodeFields(
            "FLASH_TYPING",
            listOf("conversationId" to "conv-1", "memberId" to PEER, "memberName" to "Peer B", "isTyping" to "true", "timestampMs" to "$SENT_AT"),
        ),
        "a transfer control frame" to FlashTextFraming.encodeFields(
            "FLASH_XFER",
            listOf("action" to "resume", "transferId" to "transfer-1"),
        ),
        "a PTT ping frame" to pttPingFrame(),
        "a PTT session-start frame" to pttSessionStartFrame(),
        "a group message frame" to groupMessageFrame(),
        "a group state frame" to FlashTextFraming.encodeFields(
            GroupFrameCodec.GROUP_PREFIX,
            listOf("action" to "state", "groupId" to "group-1", "from" to PEER),
        ),
    )

    /**
     * The `FLASH_CALL` frames this consumer can produce without `:core:calling`: the wire text of
     * `CallFrameCodec.encode(...)` for an invite, a group query (a frame calling owns and drops) and
     * an action from a future peer version.
     */
    private fun callFrames(): Map<String, String> = linkedMapOf(
        "a call invite frame" to FlashTextFraming.encodeFields(
            CALL_PREFIX,
            listOf("action" to "invite", "callId" to "call-1", "from" to PEER, "name" to "Peer B", "video" to "false"),
        ),
        "a call group-query frame" to FlashTextFraming.encodeFields(
            CALL_PREFIX,
            listOf("action" to "gquery", "callId" to "call-1", "groupId" to "group-1", "from" to PEER),
        ),
        "a call frame with an unknown action" to FlashTextFraming.encodeFields(
            CALL_PREFIX,
            listOf("action" to "future-action", "callId" to "call-1", "from" to PEER),
        ),
    )

    private fun chatTextFrame(): String = FlashTextFraming.encodeFields(
        "FLASH_MSG",
        listOf(
            "localId" to "msg-1",
            "conversationId" to "conv-1",
            "senderId" to PEER,
            "senderName" to "Peer B",
            "sentAt" to "$SENT_AT",
            "text" to "hello",
            "replyToId" to "",
            "replyToPreview" to "",
        ),
    )

    private fun deleteActionFrame(): String = FlashTextFraming.encodeFields(
        DirectMessageActionCodec.PREFIX,
        listOf("action" to DirectMessageActionCodec.DELETE_ACTION, "messageId" to "msg-1", "conversationId" to "conv-1", "from" to PEER),
    )

    private fun pttPingFrame(): String =
        PttFrameCodec.encode(PttPingFrame(eventId = "ping-1", from = PEER, senderName = "Peer B", sentAt = SENT_AT))

    private fun pttSessionStartFrame(): String = PttSessionCodec.encode(
        PttSessionFrame.Start(
            sessionId = "session-1",
            from = PEER,
            senderName = "Peer B",
            sentAt = SENT_AT,
            sampleRateHz = 8_000,
            packetMs = 20,
        ),
    )

    private fun groupMessageFrame(): String = GroupFrameCodec.encode(
        GroupWireFrame.Message(
            groupId = "group-1",
            messageId = "gmsg-1",
            from = PEER,
            senderName = "Peer B",
            sentAt = SENT_AT,
            text = "hello group",
        ),
    )

    // ─────────────────────────── harness ───────────────────────────

    /**
     * The documented hand-assembly path (ADR-010 / `DefaultFlashEngine`): real facade class, real
     * entry points, `EmptyFlashChatRepository` for chats, throwaway fakes for the rest, and nothing
     * attached. `Flash.create` is out of reach here — it needs an Application `Context` and opens
     * the encrypted Room database synchronously.
     */
    private fun newEngine(): FlashEngine = DefaultFlashEngine(
        chats = EmptyFlashChatRepository,
        transfers = FakeTransferRepo(),
        discovery = FakeDiscovery(),
        network = FakeNetwork(),
        trustStore = FakeTrustStore(),
        settings = FlashSettingsDataStore(
            produceFile = { File.createTempFile("flash-facade-contract-", ".preferences_pb") },
        ),
    )

    /** Drives the entry point `Flash.create`'s dispatcher calls for every inbound text frame. */
    private fun driveCallSeam(engine: FlashEngine, frame: String): Result<Boolean> =
        runCatching { runBlocking { engine.onInboundCallText(PEER, frame) } }

    /**
     * Every `.class` file of the engine module's own package, keyed by simple file name, read off
     * whatever this classpath holds — a classes directory or a jar. Byte-level and resolution-free
     * on purpose: enumerating the members of the calling-typed classes is impossible here (see the
     * attach-seam test), so the confinement claim has to be checked on bytes.
     */
    private fun engineClassBytes(): Map<String, ByteArray> {
        val className = "$ENGINE_PACKAGE.FlashEngine"
        val source = Class.forName(className, false, loader).protectionDomain?.codeSource?.location
            ?: throw AssertionError("no code source for $className — cannot scan the module's classes")
        val location = File(source.toURI())
        val packagePath = ENGINE_PACKAGE.replace('.', '/') + "/"

        if (location.isDirectory) {
            val classes = location.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.toList()
            val inPackage = classes.filter { it.invariantSeparatorsPath.contains("/$packagePath") }
            // A classpath entry may be the classes root (…/debug/com/transfer/…) or the package
            // directory itself, so fall back to everything when the package path is not in the path.
            return (inPackage.ifEmpty { classes }).associate { it.name to it.readBytes() }
        }

        return ZipFile(location).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(packagePath) && it.name.endsWith(".class") }
                .associate { entry -> entry.name.substringAfterLast('/') to zip.getInputStream(entry).use { it.readBytes() } }
        }
    }

    private class FakeDiscovery : FlashDiscovery {
        override val state: StateFlow<FlashDiscoveryState> = MutableStateFlow(FlashDiscoveryState())
        override val discoveredEndpoints: StateFlow<List<FlashDiscoveredEndpoint>> = MutableStateFlow(emptyList())
        override suspend fun startDiscovery(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopDiscovery(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun startAdvertising(listenPort: Int): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopAdvertising(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun stopAll(): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    private class FakeNetwork : FlashNetwork {
        override val networkState: StateFlow<FlashNetworkState> = MutableStateFlow(FlashNetworkState())
        override val activeSessions: StateFlow<Map<FlashDeviceId, FlashSession>> = MutableStateFlow(emptyMap())
        override val connectionHealth: StateFlow<FlashConnectionHealth> = MutableStateFlow(FlashConnectionHealth.Offline)
        override suspend fun start(listenPort: Int): FlashResult<Int> = FlashResult.Success(8080)
        override suspend fun stop(): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun connect(device: FlashDevice): FlashResult<FlashSession> =
            FlashResult.Failure(FlashError.NetworkUnavailable())
        override suspend fun connectManual(host: String, port: Int): FlashResult<FlashSession> =
            FlashResult.Failure(FlashError.NetworkUnavailable())
        override suspend fun disconnect(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)
    }

    private class FakeTrustStore : FlashTrustStore {
        override fun isTrusted(deviceId: FlashDeviceId): Boolean = false
        override fun trustPeer(deviceId: FlashDeviceId, friendlyName: String): FlashResult<Unit> = FlashResult.Success(Unit)
        override fun revokeTrust(deviceId: FlashDeviceId): FlashResult<Unit> = FlashResult.Success(Unit)
        override fun getTrustedPeers(): Map<FlashDeviceId, String> = emptyMap()
    }

    private class FakeTransferRepo : FlashTransferRepository {
        override val activeTransfers: StateFlow<List<FlashTransfer>> = MutableStateFlow(emptyList())
        override suspend fun sendFile(
            targetDevice: FlashDevice,
            fileUri: String,
            displayName: String,
            fileSize: Long,
        ): FlashResult<FlashTransferId> = FlashResult.Success(FlashTransferId("transfer-1"))

        override suspend fun pauseTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun resumeTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
        override suspend fun cancelTransfer(transferId: FlashTransferId): FlashResult<Unit> = FlashResult.Success(Unit)
    }
}
