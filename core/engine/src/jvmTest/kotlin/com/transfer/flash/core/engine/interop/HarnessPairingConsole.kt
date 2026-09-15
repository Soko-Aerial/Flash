package com.transfer.flash.core.engine.interop

import com.transfer.flash.core.security.pairing.FlashPairingCoordinator
import com.transfer.flash.core.security.pairing.PairingPhase
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Console front-end for [FlashPairingCoordinator], so the harness can run ladder steps L2/L3 (pairing,
 * both directions) without the desktop GUI.
 *
 * It is deliberately a *front-end only*: the protocol, the wire codec and the begin-flow race
 * handling are the coordinator's, i.e. **the same code `:desktop:run` executes**. A harness that
 * drove its own responder would prove nothing about the app the human actually tests.
 *
 * The trust decision is the operator's, never automatic. The whole point of the numeric-comparison
 * code is that a human compares two screens, so this prints the code and waits for an explicit `y`.
 * Anything else — including no input at all — is a decline. An unattended run therefore cannot
 * silently trust a peer.
 *
 * @param interactive true for the `pair` verb. Other verbs still *answer* pairing frames at the
 *   protocol level but print a pointer instead of stealing the terminal: during `send`/`receive` a
 *   blocking read would sit on stdin in the middle of a run, and silently accepting would defeat the
 *   comparison.
 */
internal class HarnessPairingConsole(
    private val coordinator: FlashPairingCoordinator,
    private val scope: CoroutineScope,
    private val interactive: Boolean,
    /**
     * Accept the first request without prompting.
     *
     * This is the escape hatch for the case where stdin does not reach the harness — notably under
     * the Gradle **daemon**, where a `JavaExec` task's stdin is the client's stream only on some
     * Gradle configurations, so a `readlnOrNull()` prompt can sit there and never see the operator's
     * `y`. Rather than discover that during a hardware run, `pair --accept` accepts and prints a
     * warning that the comparison is now entirely the operator's responsibility.
     *
     * It deliberately does NOT weaken anything structurally: the code is still printed, the peer
     * still shows its own code, and a human still has to assert that they matched. What it removes
     * is the *typing*, not the comparison.
     */
    private val autoAccept: Boolean = false,
) {

    private val prompting = AtomicBoolean(false)

    /** Last (phase, code) printed, so the coordinator's per-second ticks do not spam the console. */
    private var lastKey: Pair<PairingPhase, String>? = null

    fun start() {
        scope.launch { coordinator.messages.collect { println("[pair] $it") } }
        scope.launch {
            coordinator.pairing.collect { ui ->
                if (ui == null) {
                    lastKey = null
                    prompting.set(false)
                    return@collect
                }
                val key = ui.phase to ui.numericCode
                if (key == lastKey) return@collect
                lastKey = key
                when (ui.phase) {
                    PairingPhase.RequestReceived -> onRequest(ui.peerName, ui.numericCode, ui.secondsLeft)
                    PairingPhase.AwaitingPeerConfirmation -> println(
                        "[pair] code = ${ui.numericCode} — COMPARE with the peer's screen, then accept there",
                    )
                    else -> println("[pair] ${ui.phase} peer=${ui.peerName} code=${ui.numericCode}")
                }
            }
        }
        scope.launch {
            coordinator.trustedPeers.collect { peers ->
                println("[pair] trusted (${peers.size}): ${peers.joinToString { it.name }}")
            }
        }
    }

    private fun onRequest(peerName: String, code: String, secondsLeft: Int) {
        println("[pair] ===============================================")
        println("[pair] REQUEST from '$peerName'")
        println("[pair] code = $code   (expires in ${secondsLeft}s)")
        println("[pair] ===============================================")
        if (!interactive) {
            println("[pair] not in pair mode — to answer a request, run: interopHarness --args=\"pair\"")
            return
        }
        if (autoAccept) {
            if (!prompting.compareAndSet(false, true)) return
            println("[pair] --accept: accepting WITHOUT asking. You must have compared $code with the peer's screen.")
            coordinator.acceptLocal()
            return
        }
        if (!prompting.compareAndSet(false, true)) return
        scope.launch {
            try {
                val answer = withContext(Dispatchers.IO) {
                    println("[pair] type 'y' + Enter to accept, anything else to decline:")
                    readlnOrNull()?.trim()?.lowercase()
                }
                if (answer == "y" || answer == "yes") {
                    println("[pair] accepting — the peer must see the same code")
                    coordinator.acceptLocal()
                } else {
                    println("[pair] declining (input was ${answer?.let { "'$it'" } ?: "end-of-input"})")
                    coordinator.declineLocal()
                }
            } finally {
                prompting.set(false)
            }
        }
    }
}
