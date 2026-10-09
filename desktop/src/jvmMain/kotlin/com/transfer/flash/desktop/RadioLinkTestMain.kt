package com.transfer.flash.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.transfer.flash.core.network.radio.PortInfo
import com.transfer.flash.core.network.radio.diag.HarnessState
import com.transfer.flash.core.network.radio.diag.PacingPreset
import com.transfer.flash.core.network.radio.diag.RadioLinkTestHarness
import com.transfer.flash.core.network.radio.diag.TestRole
import com.transfer.flash.ui.theme.FlashShapes
import com.transfer.flash.ui.theme.FlashSpacing
import com.transfer.flash.ui.theme.FlashText
import com.transfer.flash.ui.theme.FlashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * "Experimental: Radio link test" (BT-00). A separate window, NOT part of the normal app: run it with the Gradle task
 * `:desktop:radioLinkTest`. Spec: `docs/ui/radio-link-test.md`. Never verified against a real radio.
 */
object RadioLinkTestApp {
    @JvmStatic
    fun main(args: Array<String>) {
        application {
            Window(
                onCloseRequest = ::exitApplication,
                title = "Flash - Experimental: Radio link test",
                state = rememberWindowState(width = 1100.dp, height = 820.dp),
            ) {
                FlashTheme(darkTheme = true, hapticsEnabled = false) {
                    RadioLinkTestScreen()
                }
            }
        }
    }
}

@Composable
private fun RadioLinkTestScreen() {
    val harness = remember { RadioLinkTestHarness() }
    val scope = rememberCoroutineScope()
    val colors = FlashTheme.colors
    val type = FlashTheme.typography
    var ports by remember { mutableStateOf<List<PortInfo>>(emptyList()) }
    var portsLoaded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("") }
    var baud by remember { mutableStateOf("115200") }
    var role by remember { mutableStateOf(TestRole.STATION_A) }
    var pacing by remember { mutableStateOf(PacingPreset.RADIO) }
    var burstCount by remember { mutableStateOf("5") }
    var burstBody by remember { mutableStateOf("192") }
    var result by remember { mutableStateOf("") }
    var exportedTo by remember { mutableStateOf("") }
    var rawTail by remember { mutableStateOf<List<String>>(emptyList()) }
    val state by harness.state.collectAsState()
    var feed by remember { mutableStateOf<List<String>>(emptyList()) }

    fun refreshPorts() {
        scope.launch {
            ports = harness.listPorts()
            portsLoaded = true
            if (selected.isEmpty() || ports.none { it.systemName == selected }) selected = ports.firstOrNull()?.systemName ?: ""
        }
    }

    LaunchedEffect(Unit) { refreshPorts() }
    LaunchedEffect(Unit) {
        while (true) {
            rawTail = harness.log.snapshot().takeLast(60)
            feed = harness.tester?.feed?.value ?: feed
            delay(400)
        }
    }

    Column(
        Modifier.fillMaxSize().background(colors.backgroundApp).padding(FlashSpacing.space16),
        verticalArrangement = Arrangement.spacedBy(FlashSpacing.space12),
    ) {
        FlashText("Experimental: Radio link test", style = type.headingLarge)
        FlashText(
            "Hardware spike BT-00. Pair the radio in Windows first (it appears as a virtual COM port). Frames use a public " +
                "test key: they prove the format and the link, not secrecy. Nothing here is verified against a real radio.",
            style = type.captionDefault,
            color = colors.textSecondary,
        )

        // Port selection
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(FlashSpacing.space4)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    FlashText("Serial ports", style = type.bodyEmphasis)
                    Pill("Refresh") { refreshPorts() }
                }
                if (portsLoaded && ports.isEmpty()) {
                    FlashText("no ports", style = type.bodyDefault, color = colors.textTertiary)
                } else {
                    ports.forEach { p ->
                        val on = p.systemName == selected
                        Box(
                            Modifier.fillMaxWidth()
                                .background(if (on) colors.backgroundSurfaceStrong else colors.backgroundSurface, FlashShapes.chip)
                                .border(1.dp, if (on) colors.accentPrimary else colors.borderSubtle, FlashShapes.chip)
                                .clickable { selected = p.systemName }
                                .padding(FlashSpacing.space8),
                        ) {
                            FlashText("${p.systemName}   [${p.kind}]   ${p.description}", style = type.metadataDefault, maxLines = 2)
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    LabeledField("Port", selected, 120.dp) { selected = it }
                    LabeledField("Baud", baud, 100.dp) { baud = it.filter(Char::isDigit) }
                    Pill("Role: ${if (role == TestRole.STATION_A) "A" else "B"}") { role = role.peer }
                    Pill("Pacing: ${pacing.name}") { pacing = if (pacing == PacingPreset.RADIO) PacingPreset.NONE else PacingPreset.RADIO }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
                    Pill("Connect", accent = true) {
                        scope.launch { harness.connect(selected, baud.toIntOrNull() ?: 115200, role, pacing) }
                    }
                    Pill("Simulated radio") { scope.launch { harness.connectSimulated(role) } }
                    Pill("Disconnect") { scope.launch { harness.disconnect() } }
                }
                val statusText = when (val s = state) {
                    is HarnessState.Disconnected -> "Not connected"
                    is HarnessState.Running -> "${s.description}: ${s.tnc}"
                    is HarnessState.Failed -> "Failed: ${s.message}"
                }
                FlashText(
                    statusText,
                    style = type.bodyEmphasis,
                    color = when (state) {
                        is HarnessState.Failed -> colors.textError
                        is HarnessState.Running -> colors.textSuccess
                        else -> colors.textSecondary
                    },
                )
            }
        }

        // Test buttons
        val t = harness.tester
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            Pill("KISS test frame") { t?.let { tt -> scope.launch { result = "kiss test: ${tt.sendKissTransparencyTest()}" } } }
            Pill("AX.25 UI test frame") { t?.let { tt -> scope.launch { result = "ax25 test: ${tt.sendAx25TextTest()}" } } }
            Pill("Flash 220-byte payload") { t?.let { tt -> scope.launch { result = "flash payload: ${tt.sendFlashFramePayload()}" } } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space8)) {
            LabeledField("Burst frames", burstCount, 110.dp) { burstCount = it.filter(Char::isDigit) }
            LabeledField("Body bytes", burstBody, 110.dp) { burstBody = it.filter(Char::isDigit) }
            Pill("Run timed burst", accent = true) {
                t?.let { tt ->
                    scope.launch {
                        result = "burst running..."
                        result = tt.runBurst(burstCount.toIntOrNull() ?: 5, burstBody.toIntOrNull() ?: 192).summary()
                    }
                }
            }
            Pill(if (t?.responder != false) "Responder: ON" else "Responder: off") { t?.let { it.responder = !it.responder } }
            Pill("Export log") {
                scope.launch { exportedTo = runCatching { harness.exportLog(harness.defaultLogFile(File("."))).absolutePath }.getOrElse { "export failed: ${it.message}" } }
            }
        }
        if (result.isNotEmpty()) FlashText(result, style = type.numericDefault, color = colors.accentPrimary)
        if (exportedTo.isNotEmpty()) FlashText("Log: $exportedTo (send this file back)", style = type.captionDefault, color = colors.textSecondary)

        // Live panes
        Row(Modifier.fillMaxWidth().weightedFill(), horizontalArrangement = Arrangement.spacedBy(FlashSpacing.space12)) {
            LogPane("Decoded (KISS / AX.25 / Flash)", feed, Modifier.weightedHalf())
            LogPane("Raw hex (TX/RX/EVT)", rawTail, Modifier.weightedHalf())
        }
    }
}

@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val c = FlashTheme.colors
    Box(
        Modifier
            .background(if (accent) c.accentPrimary else c.backgroundSurfaceStrong, FlashShapes.chip)
            .clickable(onClick = onClick)
            .padding(horizontal = FlashSpacing.space12, vertical = FlashSpacing.space8),
    ) {
        FlashText(label, style = FlashTheme.typography.bodyEmphasis, color = if (accent) c.textOnAccent else c.textPrimary)
    }
}

@Composable
private fun LabeledField(label: String, value: String, width: androidx.compose.ui.unit.Dp, onChange: (String) -> Unit) {
    val c = FlashTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(FlashSpacing.space2)) {
        FlashText(label, style = FlashTheme.typography.metadataDefault, color = c.textSecondary)
        Box(
            Modifier.width(width)
                .background(c.composerInputBackground, FlashShapes.chip)
                .border(1.dp, c.borderDefault, FlashShapes.chip)
                .padding(horizontal = FlashSpacing.space8, vertical = FlashSpacing.space8),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = FlashTheme.typography.bodyDefault.copy(color = c.textPrimary),
                cursorBrush = SolidColor(c.accentPrimary),
            )
        }
    }
}

@Composable
private fun LogPane(title: String, lines: List<String>, modifier: Modifier) {
    val c = FlashTheme.colors
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
    Column(modifier.background(c.backgroundSurface, FlashShapes.chip).padding(FlashSpacing.space8)) {
        FlashText(title, style = FlashTheme.typography.bodyEmphasis)
        LazyColumn(Modifier.fillMaxSize().padding(top = FlashSpacing.space4), state = listState) {
            items(lines.size) { i ->
                FlashText(lines[i], style = FlashTheme.typography.metadataDefault, color = c.textSecondary)
            }
        }
    }
}

// Tiny layout helpers so the two panes share the leftover height and width.
private fun Modifier.weightedFill(): Modifier = this.height(420.dp)

private fun Modifier.weightedHalf(): Modifier = this.width(520.dp).height(420.dp)
