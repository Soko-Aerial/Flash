package com.transfer.flash.core.network.radio.diag

import com.transfer.flash.core.common.time.FlashTimeSource
import com.transfer.flash.core.common.time.SystemTimeSource
import com.transfer.flash.core.network.radio.ByteLink
import com.transfer.flash.core.network.radio.JdkRadioCrypto
import com.transfer.flash.core.network.radio.JvmSerialPortCatalog
import com.transfer.flash.core.network.radio.KissTncConfig
import com.transfer.flash.core.network.radio.KissTncDriver
import com.transfer.flash.core.network.radio.LinkException
import com.transfer.flash.core.network.radio.PortInfo
import com.transfer.flash.core.network.radio.RadioEvidenceLog
import com.transfer.flash.core.network.radio.SerialPortCatalog
import com.transfer.flash.core.network.radio.SerialSettings
import com.transfer.flash.core.network.radio.TncLinkState
import com.transfer.flash.core.network.radio.TxPacingPolicy
import com.transfer.flash.core.network.radio.sim.FakeAirChannel
import com.transfer.flash.core.network.radio.sim.FakeTnc
import com.transfer.flash.core.network.radio.sim.inMemoryLinkPair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** How fast the test tool paces its own transmissions. */
public enum class PacingPreset(public val label: String, public val policy: TxPacingPolicy) {
    /** Radio-friendly defaults from the plan (1200 baud air, 300 ms keyup, 500 ms gap, up to 1.5 s jitter). */
    RADIO("Radio (1200 baud air, gap + jitter)", TxPacingPolicy()),

    /** No waiting: for a cable-only loopback or to see how fast the TNC accepts bytes (do NOT use on a live channel). */
    NONE("None (cable test only)", TxPacingPolicy.NONE),
}

/** What the harness is doing now. */
public sealed interface HarnessState {
    /** Nothing open. */
    public data object Disconnected : HarnessState

    /** A driver is running on [description]. [tnc] is the driver's own state text. */
    public data class Running(val description: String, val tnc: String) : HarnessState

    /** The last open attempt failed with [message]. */
    public data class Failed(val message: String) : HarnessState
}

/**
 * Owns a serial port (or the built-in simulated radio), a [KissTncDriver] and a [RadioLinkTester] for the BT-00 hardware
 * spike. UI-free: the desktop window and the headless CLI both drive this class.
 *
 * Nothing here is verified against a real radio. If the port exists but the radio does not answer, the harness still reports
 * "Running": a KISS TNC sends nothing on its own, so a silent port is indistinguishable from a quiet channel until a frame is
 * heard or the TX counters show writes being accepted.
 */
public class RadioLinkTestHarness(
    private val catalog: SerialPortCatalog = JvmSerialPortCatalog(),
    private val clock: FlashTimeSource = SystemTimeSource,
) {
    private var scope: CoroutineScope? = null
    private val stateFlow = MutableStateFlow<HarnessState>(HarnessState.Disconnected)

    /** Everything that happened, with raw TX/RX hex. Survives reconnects; [exportLog] writes it out. */
    public val log: RadioEvidenceLog = RadioEvidenceLog()

    /** Current state. */
    public val state: StateFlow<HarnessState> get() = stateFlow.asStateFlow()

    /** The driver while running, else null. */
    public var driver: KissTncDriver? = null
        private set

    /** The tester while running, else null. */
    public var tester: RadioLinkTester? = null
        private set

    private var description: String = ""
    private var headerLines: List<String> = emptyList()

    /**
     * Ports the OS reports now (empty is a valid answer: "no ports"). Runs on [Dispatchers.IO]: enumerating a Bluetooth
     * virtual port is a blocking native call that can take seconds, and the desktop window calls this from its UI thread (R5).
     */
    public suspend fun listPorts(): List<PortInfo> = withContext(Dispatchers.IO) { catalog.listPorts() }

    /** Opens [portName] at [baud] and starts the driver. Replaces a running session. */
    public suspend fun connect(
        portName: String,
        baud: Int,
        role: TestRole,
        pacing: PacingPreset = PacingPreset.RADIO,
        config: KissTncConfig = KissTncConfig(),
    ) {
        disconnect()
        val settings = try {
            SerialSettings(baud = baud)
        } catch (e: IllegalArgumentException) {
            stateFlow.value = HarnessState.Failed("bad serial settings: ${e.message}")
            return
        }
        // Open once now so a busy or missing port is reported immediately; the driver's reconnect loop reopens after a drop.
        val first: ByteLink = try {
            withContext(Dispatchers.IO) { catalog.open(portName, settings) } // blocking native open, never on the UI thread (R5)
        } catch (e: LinkException) {
            stateFlow.value = HarnessState.Failed(e.message ?: "cannot open $portName")
            log.note(clock.nowMs(), "harness.open_failed", listOf("port" to portName, "baud" to baud, "reason" to e.message))
            return
        }
        var pending: ByteLink? = first
        start(
            desc = "$portName @$baud",
            role = role,
            pacing = pacing,
            config = config,
            openLink = {
                val p = pending
                pending = null
                p ?: withContext(Dispatchers.IO) { catalog.open(portName, settings) }
            },
        )
    }

    /**
     * Starts a self-contained simulation instead of a real port: two fake TNCs on one fake channel, so the whole screen can be
     * exercised with no radio. The remote station answers PINGs.
     */
    public suspend fun connectSimulated(role: TestRole, pacing: PacingPreset = PacingPreset.NONE) {
        disconnect()
        val s = newScope()
        val air = FakeAirChannel()
        val (host, radio) = inMemoryLinkPair("sim-a")
        FakeTnc(radio, air, s)
        val (remoteHost, remoteRadio) = inMemoryLinkPair("sim-b")
        FakeTnc(remoteRadio, air, s)
        val remoteLog = RadioEvidenceLog()
        val remoteDriver = KissTncDriver(s, { remoteHost }, KissTncConfig(), TxPacingPolicy.NONE, clock, { 0.5 }, remoteLog)
        val remote = RadioLinkTester(s, remoteDriver, remoteLog, clock, JdkRadioCrypto(), role.peer)
        remoteDriver.start()
        remote.start()
        start("simulated radio (no hardware)", role, pacing, KissTncConfig(), { host }, existingScope = s)
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }

    private fun start(
        desc: String,
        role: TestRole,
        pacing: PacingPreset,
        config: KissTncConfig,
        openLink: suspend () -> ByteLink,
        existingScope: CoroutineScope? = null,
    ) {
        val s = existingScope ?: newScope()
        description = desc
        headerLines = listOf(
            "Flash radio link test (BT-00 hardware spike), not verified against a real radio unless the log shows RX from one",
            "started ${java.time.Instant.ofEpochMilli(clock.nowMs())}",
            "link: $desc  role: ${role.name}  pacing: ${pacing.name}",
            "os: ${System.getProperty("os.name")} ${System.getProperty("os.version")}  java: ${System.getProperty("java.version")}",
            "jSerialComm: ${runCatching { com.fazecast.jSerialComm.SerialPort.getVersion() }.getOrDefault("unknown")}",
            "test key: public (RadioLinkTester.TEST_PASSPHRASE): frames prove the format, not confidentiality",
        )
        val d = KissTncDriver(s, openLink, config, pacing.policy, clock, { Math.random() }, log)
        val t = RadioLinkTester(s, d, log, clock, JdkRadioCrypto(), role)
        driver = d
        tester = t
        d.start()
        t.start()
        stateFlow.value = HarnessState.Running(desc, "starting")
        s.launch {
            d.state.collect { st ->
                val text = when (st) {
                    is TncLinkState.Idle -> "idle"
                    is TncLinkState.Connecting -> "connecting (attempt ${st.attempt})"
                    is TncLinkState.Connected -> "connected"
                    is TncLinkState.Waiting -> "waiting ${st.retryInMs} ms to retry: ${st.reason}"
                    is TncLinkState.Stopped -> "stopped"
                }
                if (stateFlow.value is HarnessState.Running) stateFlow.value = HarnessState.Running(description, text)
            }
        }
    }

    /** Stops the driver and releases the port. The log is kept. */
    public suspend fun disconnect() {
        tester?.stop()
        driver?.stop()
        scope?.cancel()
        scope = null
        driver = null
        tester = null
        if (stateFlow.value is HarnessState.Running) stateFlow.value = HarnessState.Disconnected
    }

    /** The exported text: a header describing the run, then every log line. */
    public fun renderLog(): String = log.render(headerLines + "exported ${java.time.Instant.ofEpochMilli(clock.nowMs())}")

    /** Writes [renderLog] to [file] (UTF-8) and returns the file. */
    public fun exportLog(file: File): File {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(renderLog(), Charsets.UTF_8)
        return file
    }

    /** A default file name such as `flash-radio-test-20261009-101500.txt` in the working directory. */
    public fun defaultLogFile(dir: File = File(".")): File {
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return File(dir, "flash-radio-test-$stamp.txt")
    }
}
