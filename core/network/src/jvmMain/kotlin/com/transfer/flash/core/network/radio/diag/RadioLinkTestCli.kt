package com.transfer.flash.core.network.radio.diag

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.system.exitProcess

/**
 * Headless BT-00 tool: `RadioLinkTestCliKt` (run it with the Gradle task `:desktop:radioLinkTestCli`, arguments after
 * `--args`). Works with no radio: `--list` prints "no ports", `--simulated` runs the whole sequence against a fake radio.
 *
 * Exit code 0 when the requested steps ran (this says nothing about whether a radio heard anything: read the output),
 * 2 on a usage error, 3 when the port could not be opened.
 */
public fun main(args: Array<String>) {
    val opts = parse(args)
    if (opts.help) {
        println(USAGE)
        return
    }
    val harness = RadioLinkTestHarness()
    if (opts.list) {
        val ports = runBlocking { harness.listPorts() }
        if (ports.isEmpty()) println("no ports") else ports.forEach { println("${it.systemName}\t${it.kind}\t${it.description}") }
        return
    }
    runBlocking {
        val role = if (opts.role.equals("b", true)) TestRole.STATION_B else TestRole.STATION_A
        val pacing = if (opts.noPacing) PacingPreset.NONE else PacingPreset.RADIO
        if (opts.simulated) {
            harness.connectSimulated(role, PacingPreset.NONE)
        } else {
            val port = opts.port ?: run {
                System.err.println("missing --port (use --list to see ports, or --simulated)")
                exitProcess(2)
            }
            harness.connect(port, opts.baud, role, pacing)
            val st = harness.state.value
            if (st is HarnessState.Failed) {
                System.err.println("FAILED: ${st.message}")
                println(harness.exportLog(opts.export ?: harness.defaultLogFile()).absolutePath)
                exitProcess(3)
            }
        }
        val tester = harness.tester!!
        // Print feed lines as they appear.
        val printer = launch {
            var seen = 0
            while (true) {
                val feed = tester.feed.value
                if (feed.size < seen) seen = 0
                for (i in seen until feed.size) println(feed[i])
                seen = feed.size
                delay(200)
            }
        }
        val connected = withTimeoutOrNull(15_000) {
            harness.driver!!.state.first { it is com.transfer.flash.core.network.radio.TncLinkState.Connected }
        }
        println(if (connected != null) "link: connected" else "link: NOT connected after 15 s (see log)")
        if (connected != null) {
            if (opts.kiss) println("kiss transparency test -> ${tester.sendKissTransparencyTest()}")
            if (opts.ax25) println("ax25 text test -> ${tester.sendAx25TextTest()}")
            if (opts.flash) println("flash 220-byte frame -> ${tester.sendFlashFramePayload()}")
            if (opts.burst > 0) println(tester.runBurst(opts.burst, opts.body, opts.ackWaitSeconds * 1000L).summary())
            if (opts.listenSeconds > 0) {
                println("listening for ${opts.listenSeconds} s (responder=${tester.responder})")
                delay(opts.listenSeconds * 1000L)
            }
        }
        delay(300)
        printer.cancel()
        val out = harness.exportLog(opts.export ?: harness.defaultLogFile())
        println("log written: ${out.absolutePath}")
        harness.disconnect()
    }
}

private const val USAGE = """Flash radio link test (BT-00). Experimental; never verified against a real radio.
  --list                     list serial ports ("no ports" if none)
  --simulated                run against a built-in fake radio (needs no hardware)
  --port COM5 --baud 115200  open this port (Bluetooth virtual COM port of the radio)
  --role a|b                 station label for this machine (default a); the other machine uses the other letter
  --kiss                     send the KISS transparency test frame (bytes 0..219, contains C0 and DB)
  --ax25                     send a readable AX.25 UI test frame
  --flash                    send one full-size (220 byte) Flash radio frame
  --burst N [--body B]       send N PING frames of B body bytes (default 192), report RTT and goodput
  --ack-wait S               seconds to wait for acknowledgements after a burst (default 60)
  --listen S                 stay up S seconds answering PINGs (run this on the second station)
  --no-pacing                disable pacing (cable test only; never on a live channel)
  --export FILE              where to write the log (default flash-radio-test-<time>.txt)
"""

private class Opts(
    var help: Boolean = false,
    var list: Boolean = false,
    var simulated: Boolean = false,
    var port: String? = null,
    var baud: Int = 115200,
    var role: String = "a",
    var kiss: Boolean = false,
    var ax25: Boolean = false,
    var flash: Boolean = false,
    var burst: Int = 0,
    var body: Int = 192,
    var ackWaitSeconds: Int = 60,
    var listenSeconds: Int = 0,
    var noPacing: Boolean = false,
    var export: File? = null,
)

private fun parse(args: Array<String>): Opts {
    val o = Opts()
    var i = 0
    fun value(): String {
        if (i + 1 >= args.size) {
            System.err.println("missing value after ${args[i]}\n$USAGE")
            exitProcess(2)
        }
        return args[++i]
    }
    fun int(): Int = value().toIntOrNull() ?: run {
        System.err.println("expected a number after ${args[i - 1]}\n$USAGE")
        exitProcess(2)
    }
    while (i < args.size) {
        when (args[i]) {
            "--help", "-h" -> o.help = true
            "--list" -> o.list = true
            "--simulated" -> o.simulated = true
            "--port" -> o.port = value()
            "--baud" -> o.baud = int()
            "--role" -> o.role = value()
            "--kiss" -> o.kiss = true
            "--ax25" -> o.ax25 = true
            "--flash" -> o.flash = true
            "--burst" -> o.burst = int()
            "--body" -> o.body = int()
            "--ack-wait" -> o.ackWaitSeconds = int()
            "--listen" -> o.listenSeconds = int()
            "--no-pacing" -> o.noPacing = true
            "--export" -> o.export = File(value())
            else -> {
                System.err.println("unknown argument ${args[i]}\n$USAGE")
                exitProcess(2)
            }
        }
        i++
    }
    return o
}
