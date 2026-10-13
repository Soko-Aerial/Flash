@file:OptIn(FlashInternalApi::class)

package com.transfer.flash.server.e2e

import com.transfer.flash.core.common.annotation.FlashInternalApi
import com.transfer.flash.core.hubprotocol.certs.EnrollmentInvite
import com.transfer.flash.core.hubprotocol.relay.RelayFrame
import com.transfer.flash.core.network.enterprise.EnterpriseHub
import com.transfer.flash.core.network.relay.FlashRelayClient
import com.transfer.flash.core.network.relay.KtorRelayTransport
import com.transfer.flash.core.security.crypto.FlashCrypto
import com.transfer.flash.core.security.crypto.FlashEcKeyPair
import com.transfer.flash.core.security.enterprise.MemoryEnterpriseTrustStore
import com.transfer.flash.server.HubServices
import com.transfer.flash.server.KeyTool
import com.transfer.flash.server.PassphraseSource
import com.transfer.flash.server.auth.HubKeyStore
import com.transfer.flash.server.auth.InviteScope
import com.transfer.flash.server.config.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * The CLIENT/SERVER contract test harness (ADR-124): the REAL [HubServices] (real Netty on a free 127.0.0.1 port, a real
 * SQLite file in a temp directory) driven by the REAL client classes of `:core:network` and `:core:security`.
 *
 * Test-only seams and why they exist (none of them is in production code):
 * - [E2eClock]: wall clock plus an offset the test advances, handed to BOTH the hub (`HubServices.build(clock = ...)`)
 *   and the client (`EnterpriseHub(clock = ...)`), so the renewal window is crossed without sleeping 29 days.
 * - the admin "revoke" is called on the hub's service layer (`HubServices.revocations`), exactly what the admin API
 *   does after its step-up check; the step-up itself is covered by `StepUpAuthTest`.
 * - `jitter` / `tickMs` on the client side are constructor parameters the client already exposes.
 */
class E2eClock {
    @Volatile
    private var offsetMs: Long = 0L
    fun now(): Long = System.currentTimeMillis() + offsetMs
    fun advance(ms: Long) {
        offsetMs += ms
    }
}

/** A plain JCA [FlashCrypto] (the client's own software implementation is internal to `:core:security`). */
class E2eCrypto : FlashCrypto {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    override val identityPublicKeyEncoded: ByteArray = pair.public.encoded

    override fun sign(data: ByteArray): ByteArray =
        Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
            initSign(pair.private)
            update(data)
            sign()
        }

    override fun verify(signature: ByteArray, data: ByteArray, peerPublicKey: ByteArray): Boolean = runCatching {
        val key = KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(peerPublicKey))
        Signature.getInstance(FlashCrypto.ECDSA_SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(data)
            verify(signature)
        }
    }.getOrDefault(false)

    override fun generateEphemeralEcdhKeyPair(): FlashEcKeyPair = throw UnsupportedOperationException("not used by the hub contract")

    override fun ecdhSessionKey(selfEphemeral: FlashEcKeyPair, peerEphemeralPublicKey: ByteArray): ByteArray =
        throw UnsupportedOperationException("not used by the hub contract")

    /** For tests that must sign as THIS device with the server-side helpers. */
    fun keyPair(): KeyPair = pair
}

/** The hub under test. One instance = one data directory; [restart] reopens the SAME directory (and port). */
class E2eHub(
    val clock: E2eClock = E2eClock(),
    private val configure: (ServerConfig) -> ServerConfig = { it },
    private val limits: () -> com.transfer.flash.server.auth.HubRateLimits = { com.transfer.flash.server.auth.HubRateLimits() },
) {
    private val root: File = Files.createTempDirectory("flash-e2e").toFile()
    val dataDir: File = File(root, "data").also { it.mkdirs() }
    private val rootKeyDir = File(root, "root-offline").toPath()
    val port: Int = ServerSocket(0).use { it.localPort }
    val url: String = "http://127.0.0.1:$port"
    lateinit var services: HubServices
        private set

    private var delegationSerial = 1L

    /** What the operator does OFFLINE: `init-root`, then `issue-delegation` for the hub's public key. */
    fun provision(maxDevices: Int = 50, validDays: Long = 30): E2eHub {
        val keys = HubKeyStore(dataDir.toPath()).loadOrCreateDelegateKey()
        val tool = KeyTool(PrintStream(ByteArrayOutputStream()), PassphraseSource { _, _ -> null })
        val rootKey = rootKeyDir.resolve(KeyTool.ROOT_PRIV)
        if (!Files.exists(rootKey)) tool.initRoot(rootKeyDir, encrypt = false)
        tool.issueDelegation(
            rootKey, keys.publicKey, setOf("enrol:member", "revoke:member"), maxDevices, validDays, delegationSerial++,
            outFile = dataDir.toPath().resolve("delegation.cert"),
        )
        return this
    }

    fun config(): ServerConfig = configure(
        ServerConfig(
            port = port, adminPort = port, adminOnPublicPort = true, host = "127.0.0.1", dataDir = dataDir.path,
            tlsMode = "none", allowInsecure = true, externalUrl = url,
        ),
    )

    fun start(): E2eHub {
        services = HubServices.build(config(), clock = clock::now, limits = limits())
        services.start(wait = false)
        return this
    }

    fun stop() {
        if (this::services.isInitialized) {
            runCatching { services.stop() }
            runCatching { services.database.close() }
        }
    }

    fun restart(): E2eHub {
        stop()
        // Netty releases the port asynchronously: retry the bind for a moment.
        var last: Throwable? = null
        repeat(20) {
            try {
                return start()
            } catch (t: Throwable) {
                last = t
                Thread.sleep(250)
            }
        }
        throw IllegalStateException("hub did not restart on port $port", last)
    }

    fun close() {
        stop()
        root.deleteRecursively()
    }

    fun inviteLink(
        department: String? = "eng",
        expiresInMs: Long = 10 * 60_000L,
        maxUses: Int = 1,
        autoJoinGroups: Set<String> = emptySet(),
        allowedGroups: Set<String> = emptySet(),
    ): String = services.enrollment!!.createInvite(
        url,
        InviteScope(
            department = department,
            expiry = clock.now() + expiresInMs,
            maxUses = maxUses,
            autoJoinGroups = autoJoinGroups,
            allowedGroups = allowedGroups,
        ),
        clock.now(),
    ).link

    fun invite(
        department: String? = "eng",
        expiresInMs: Long = 10 * 60_000L,
        maxUses: Int = 1,
        autoJoinGroups: Set<String> = emptySet(),
        allowedGroups: Set<String> = emptySet(),
    ): EnrollmentInvite = EnrollmentInvite.parseOrNull(inviteLink(department, expiresInMs, maxUses, autoJoinGroups, allowedGroups))!!

    val serverId: String get() = services.challengeAuth!!.serverId

    /** Rows currently in the hub's mailbox addressed to [deviceId]. */
    fun mailboxRows(deviceId: String): Int = services.database.withConnection { c ->
        c.prepareStatement("SELECT COUNT(*) FROM mailbox_envelopes WHERE target_device_id = ?").use { st ->
            st.setString(1, deviceId)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }
    }
}

/** One client install: its own key, trust store, HTTP client and the real [EnterpriseHub]. */
class E2eDevice(
    val id: String,
    val hub: E2eHub,
    val clock: E2eClock = hub.clock,
    allowCleartext: Boolean = true,
    tickMs: Long = 250L,
    maintenanceEveryMs: Long = 0L,
    val jitterCalls: CopyOnWriteArrayList<Long> = CopyOnWriteArrayList(),
) {
    val crypto = E2eCrypto()
    val trust = MemoryEnterpriseTrustStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 } }

    val received = CopyOnWriteArrayList<RelayFrame.Deliver>()
    val acks = CopyOnWriteArrayList<RelayFrame.Ack>()
    val queueStates = CopyOnWriteArrayList<RelayFrame.QueueState>()
    val metas = CopyOnWriteArrayList<RelayFrame.Meta>()

    private val _recvFlow = MutableSharedFlow<RelayFrame.Deliver>(extraBufferCapacity = 64)
    val recvFlow = _recvFlow.asSharedFlow()

    /** When true, every delivery is acknowledged to the hub (`FLASH_RECV_ACK`) as a chat repository would after storing it. */
    @Volatile
    var autoAck: Boolean = true

    val client: EnterpriseHub = EnterpriseHub(
        deviceId = id,
        friendlyName = "Test $id",
        crypto = crypto,
        trustStore = trust,
        scope = scope,
        httpClient = http,
        ownsHttpClient = false,
        allowCleartext = { allowCleartext },
        clock = clock::now,
        ioDispatcher = Dispatchers.IO,
        relayFactory = { identity ->
            FlashRelayClient(
                deviceId = id,
                friendlyName = "Test $id",
                hubUrl = trust.credentials.value?.hubUrl.orEmpty(),
                identityProvider = identity,
                transport = KtorRelayTransport(client = HttpClient(CIO) { install(io.ktor.client.plugins.websocket.WebSockets) }),
                jitter = { max -> jitterCalls += max; 0L },
            )
        },
        tickMs = tickMs,
        maintenanceEveryMs = maintenanceEveryMs,
    )

    private var collecting = false

    /** Starts the hub client and the collectors for deliveries, acks and queue states. */
    fun begin(): E2eDevice {
        client.start()
        if (!collecting) {
            collecting = true
            scope.launch {
                client.relay.incomingDeliveries.collect { d ->
                    received += d
                    _recvFlow.tryEmit(d)
                    if (autoAck) client.relay.acknowledgeReceipt(d.id)
                }
            }
            scope.launch { client.relay.deliveryAcks.collect { acks += it } }
            scope.launch { client.relay.queueStates.collect { queueStates += it } }
            scope.launch { client.relay.metaEvents.collect { metas += it } }
        }
        return this
    }

    /** Starts, redeems a fresh invite of [department] and waits until the relay handshake is through. */
    suspend fun enrolAndConnect(department: String? = "eng", autoJoinGroups: Set<String> = emptySet()): E2eDevice {
        begin()
        val outcome = client.enroll(hub.invite(department = department, autoJoinGroups = autoJoinGroups))
        check(outcome is com.transfer.flash.core.network.enterprise.EnrollmentOutcome.Enrolled) { "enrol failed: $outcome" }
        awaitConnected()
        return this
    }

    suspend fun awaitConnected(timeoutMs: Long = 10_000L) {
        check(eventually(timeoutMs) { client.relay.state.value == com.transfer.flash.core.network.relay.RelaySessionState.CONNECTED }) {
            "relay of $id did not connect (state=${client.relay.state.value}, failure=${client.relay.failure.value})"
        }
    }

    /** True when the hub holds a live relay session for this device. */
    fun hubSeesOnline(): Boolean = hub.services.engine.connectionPool.isConnected(id)

    fun shutdown() {
        runCatching { client.shutdown() }
        scope.cancel()
        runCatching { http.close() }
    }
}

/** Polls [condition] every 25 ms until it holds or [timeoutMs] passes; true when it held. */
suspend fun eventually(timeoutMs: Long = 8_000L, condition: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(25)
        true
    } ?: false

/** The first element of [list] matching [predicate], waiting up to [timeoutMs]. */
suspend fun <T> awaitItem(list: List<T>, timeoutMs: Long = 8_000L, predicate: (T) -> Boolean): T? {
    var found: T? = null
    eventually(timeoutMs) {
        found = list.firstOrNull(predicate)
        found != null
    }
    return found
}
