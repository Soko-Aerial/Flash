package com.transfer.flash.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.transfer.flash.core.common.model.FlashDevice
import com.transfer.flash.core.common.result.FlashResult
import com.transfer.flash.core.discovery.FlashDiscoveryState
import com.transfer.flash.core.discovery.core.CompositeDiscovery
import com.transfer.flash.core.discovery.core.FlashDiscoveryMode
import com.transfer.flash.core.network.FlashConnectionHealth
import com.transfer.flash.core.network.FlashNetworkState
import com.transfer.flash.core.network.FlashSession
import com.transfer.flash.core.network.util.LocalNetworkAddresses
import com.transfer.flash.core.discovery.FlashDiscoveredEndpoint
import com.transfer.flash.core.transfer.model.FlashTransfer
import com.transfer.flash.core.transfer.model.FlashTransferDirection
import com.transfer.flash.core.transfer.model.FlashTransferState
import com.transfer.flash.ui.theme.FlashText
import kotlinx.coroutines.launch

/**
 * PROVISIONAL debug-only Dev Console (P3.5/E). Tabbed layout for exercising discovery,
 * connections, chat, and chunked transfers on real devices before PART 2 exists.
 *
 * Tabs: PEERS (sessions + endpoints) Â· TRANSFERS (progress + controls) Â· NET (mode/gateway).
 * Entry point: floating chip in MainActivity, debug builds only.
 */
@Composable
fun FlashDevConsoleScreen(
    context: android.content.Context,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var engine by remember { mutableStateOf<CompositeDiscovery?>(null) }
    var logLines by remember { mutableStateOf(listOf("Idle")) }
    var started by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(FlashDiscoveryMode.STANDARD) }
    var tab by remember { mutableIntStateOf(0) }

    fun log(message: String) {
        logLines = (listOf(message) + logLines).take(30)
    }

    val endpointsFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<FlashDiscoveredEndpoint>())
    }
    val endpoints by (engine?.discoveredEndpoints ?: endpointsFallback).collectAsState()
    val statusFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(FlashDiscoveryState())
    }
    val status by (engine?.state ?: statusFallback).collectAsState()
    val networkFallbackState = remember { kotlinx.coroutines.flow.MutableStateFlow(FlashNetworkState()) }
    val networkFallbackHealth = remember { kotlinx.coroutines.flow.MutableStateFlow(FlashConnectionHealth.Offline) }
    val currentNetwork = DiscoveryEngineHolder.currentNetwork()
    val networkState by (currentNetwork?.networkState ?: networkFallbackState).collectAsState()
    val health by (currentNetwork?.connectionHealth ?: networkFallbackHealth).collectAsState()

    val transferFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyList<FlashTransfer>())
    }
    val currentTransfers = DiscoveryEngineHolder.currentTransfers()
    val activeTransfers by (currentTransfers?.activeTransfers ?: transferFallback).collectAsState()

    val sessionsFallback = remember {
        kotlinx.coroutines.flow.MutableStateFlow(emptyMap<com.transfer.flash.core.common.model.FlashDeviceId, FlashSession>())
    }
    val activeSessionsMap by (currentNetwork?.activeSessions ?: sessionsFallback).collectAsState()
    val activeSessions = activeSessionsMap.values.toList()

    var targetDeviceForPick by remember { mutableStateOf<FlashDevice?>(null) }
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        val targetDevice = targetDeviceForPick
        if (uri != null && targetDevice != null) {
            val repo = DiscoveryEngineHolder.currentTransfers() ?: return@rememberLauncherForActivityResult
            scope.launch {
                var fileName = "selected_file"
                var fileSize = 1024 * 1024L
                runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                        val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                        if (cursor.moveToFirst()) {
                            if (nameIdx != -1) fileName = cursor.getString(nameIdx)
                            if (sizeIdx != -1) fileSize = cursor.getLong(sizeIdx)
                        }
                    }
                }
                when (val result = repo.sendFile(targetDevice, uri.toString(), fileName, fileSize)) {
                    is FlashResult.Success -> log("Send started: $fileName (${fileSize / 1024} KB)")
                    is FlashResult.Failure -> log("Send failed: ${result.error}")
                }
            }
        }
    }

    fun start() {
        scope.launch {
            runCatching {
                engine = DiscoveryEngineHolder.ensureStarted(context)
                started = true
                DiscoveryEngineHolder.current()?.setMode(mode)
                log("Engine started (${mode.name})")
            }.onFailure { log("Start failed: ${it.message}") }
        }
    }

    fun sendTestFile(peerName: String, target: FlashDevice, sizeBytes: Long = 10L * 1024 * 1024) {
        val repo = DiscoveryEngineHolder.currentTransfers() ?: return
        scope.launch {
            val result = repo.sendFile(
                targetDevice = target,
                fileUri = "file:///dummy/test_payload.bin",
                displayName = "test_${sizeBytes / (1024 * 1024)}mb.bin",
                fileSize = sizeBytes,
            )
            log(when (result) {
                is FlashResult.Success -> "10MB test â†’ $peerName started"
                is FlashResult.Failure -> "10MB test failed: ${result.error}"
            })
        }
    }

    fun sendPing(peerName: String, deviceId: String) {
        val chatRepo = DiscoveryEngineHolder.currentChats() ?: return
        scope.launch {
            chatRepo.openConversation(deviceId)
            chatRepo.sendText("Hello from Flash Dev Console! Ping: ${System.currentTimeMillis()}")
            log("Ping sent to $peerName")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp),
    ) {
        // ---- Header -------------------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlashText("Dev Console", style = MaterialTheme.typography.titleLarge)
            DevButton("Close") { onClose() }
        }

        Spacer(Modifier.height(8.dp))

        // Status card
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                .padding(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HealthDot(health == FlashConnectionHealth.Connected)
                Spacer(Modifier.width(6.dp))
                FlashText(
                    "${health.name} Â· ${networkState.activePeerCount} peer(s) Â· port ${networkState.localPort}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(4.dp))
            FlashText(
                status.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevButton(if (started) "Restart" else "Start", highlighted = !started) {
                    if (started) {
                        scope.launch {
                            DiscoveryEngineHolder.stopAll()
                            engine = null
                            started = false
                            start()
                        }
                    } else {
                        start()
                    }
                }
                DevButton("Stop", enabled = started) {
                    scope.launch {
                        runCatching {
                            DiscoveryEngineHolder.stopAll()
                            engine = null
                            started = false
                            log("Stopped")
                        }.onFailure { log("Stop failed: ${it.message}") }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // ---- Tab bar ------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("PEERS", "TRANSFERS", "NET").forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .clickable { tab = index }
                        .background(
                            if (tab == index) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    FlashText(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (tab == index) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- Tab content ----------------------------------------------------------
        when (tab) {
            0 -> PeersTab(
                sessions = activeSessions,
                endpoints = endpoints,
                onPing = ::sendPing,
                onSendFile = { peer ->
                    targetDeviceForPick = peer
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onTest10MB = ::sendTestFile,
                onDisconnect = { session, name ->
                    session.disconnect("Disconnected via Dev Console")
                    log("Disconnected $name")
                },
                onConnect = { endpoint ->
                    val net = DiscoveryEngineHolder.currentNetwork() ?: return@PeersTab
                    scope.launch {
                        when (val result = net.connect(endpoint.toFlashDevice())) {
                            is FlashResult.Success -> log("Connected ${endpoint.friendlyName}")
                            is FlashResult.Failure -> log("Connect failed: ${result.error}")
                        }
                    }
                },
            )

            1 -> TransfersTab(
                transfers = activeTransfers,
                onPauseResume = { transfer ->
                    val repo = DiscoveryEngineHolder.currentTransfers() ?: return@TransfersTab
                    scope.launch {
                        val res = if (transfer.state == FlashTransferState.Paused) {
                            repo.resumeTransfer(transfer.id)
                        } else {
                            repo.pauseTransfer(transfer.id)
                        }
                        log("${transfer.fileName}: ${if (res is FlashResult.Success) "ok" else "$res"}")
                    }
                },
                onCancel = { transfer ->
                    val repo = DiscoveryEngineHolder.currentTransfers() ?: return@TransfersTab
                    scope.launch {
                        repo.cancelTransfer(transfer.id)
                        log("${transfer.fileName}: cancelled")
                    }
                },
            )

            2 -> NetTab(
                mode = mode,
                onModeChanged = { newMode ->
                    mode = newMode
                    val e = engine
                    if (e == null) {
                        log("Mode ${newMode.name} applies at start")
                    } else {
                        scope.launch {
                            e.setMode(newMode)
                            log("Mode â†’ ${newMode.name}")
                        }
                    }
                },
                onProbeGateway = {
                    val net = DiscoveryEngineHolder.currentNetwork()
                    if (net == null) {
                        log("Start engine first")
                        return@NetTab
                    }
                    // Real gateways from LinkProperties.routes, not a hardcoded 192.168.43.1: that is
                    // only one of at least five tethering subnets OEMs use, and wrong outright for a
                    // device on an ordinary router (ERROR-035).
                    val gateways = LocalNetworkAddresses(context).ipv4Gateways()
                    if (gateways.isEmpty()) {
                        log("No IPv4 gateway on any LAN network - hosting a hotspot, or no LAN at all")
                        return@NetTab
                    }
                    scope.launch {
                        log("Probing ${gateways.size} gateway(s): ${gateways.joinToString()}")
                        var connected = false
                        for (gateway in gateways) {
                            when (val result = net.connectManual(gateway, 0)) {
                                is FlashResult.Success -> {
                                    log("Connected to $gateway")
                                    connected = true
                                }
                                is FlashResult.Failure -> log("$gateway failed: ${result.error}")
                            }
                        }
                        if (!connected) log("No gateway answered - it may not be running Flash")
                    }
                },
            )
        }

        Spacer(Modifier.height(6.dp))

        // ---- Log strip -------------------------------------------------------------
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(8.dp),
        ) {
            items(logLines) { line ->
                FlashText(line, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
    }
}

// ---- PEERS tab -----------------------------------------------------------------

@Composable
private fun PeersTab(
    sessions: List<FlashSession>,
    endpoints: List<FlashDiscoveredEndpoint>,
    onPing: (String, String) -> Unit,
    onSendFile: (FlashDevice) -> Unit,
    onTest10MB: (String, FlashDevice) -> Unit,
    onDisconnect: (FlashSession, String) -> Unit,
    onConnect: (FlashDiscoveredEndpoint) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SectionTitle("Connected (${sessions.size})")
        }
        if (sessions.isEmpty()) {
            item { EmptyHint("No sessions. Connect from discovered peers below.") }
        }
        items(sessions, key = { "s-${it.peerDeviceId.value}" }) { session ->
            CardSurface(container = true) {
                FlashText(session.peer.friendlyName, style = MaterialTheme.typography.titleSmall)
                FlashText(
                    "${session.peerDeviceId.value.take(10)}â€¦ Â· ${session.transportType}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DevButton("Ping") { onPing(session.peer.friendlyName, session.peerDeviceId.value) }
                    DevButton("Fileâ€¦") { onSendFile(session.peer) }
                    DevButton("10MB") { onTest10MB(session.peer.friendlyName, session.peer) }
                    DevButton("âœ•") { onDisconnect(session, session.peer.friendlyName) }
                }
            }
        }

        item {
            SectionTitle("Discovered (${endpoints.size})")
        }
        if (endpoints.isEmpty()) {
            item { EmptyHint("Nothing discovered yet.") }
        }
        items(endpoints, key = { "e-${it.deviceId.value}" }) { ep ->
            val connected = sessions.any { it.peerDeviceId == ep.deviceId }
            CardSurface {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FlashText(ep.friendlyName, style = MaterialTheme.typography.titleSmall)
                    if (connected) {
                        FlashText("CONNECTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                FlashText(
                    "${ep.deviceId.value.take(10)}â€¦ Â· ${ep.hostAddress}:${ep.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                if (!connected) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DevButton("Connect", highlighted = true) { onConnect(ep) }
                        DevButton("Ping") { onPing(ep.friendlyName, ep.deviceId.value) }
                        DevButton("Fileâ€¦") { onSendFile(ep.toFlashDevice()) }
                        DevButton("10MB") { onTest10MB(ep.friendlyName, ep.toFlashDevice()) }
                    }
                }
            }
        }
    }
}

// ---- TRANSFERS tab ---------------------------------------------------------------

@Composable
private fun TransfersTab(
    transfers: List<FlashTransfer>,
    onPauseResume: (FlashTransfer) -> Unit,
    onCancel: (FlashTransfer) -> Unit,
) {
    if (transfers.isEmpty()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyHint("No transfers yet.\nUse PEERS â†’ 10MB or Fileâ€¦ to start one.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(transfers, key = { it.id.value }) { transfer ->
            val percent = if (transfer.bytesTotal > 0) {
                (transfer.bytesDone * 100 / transfer.bytesTotal).toInt().coerceIn(0, 100)
            } else 0
            val terminal = transfer.state == FlashTransferState.Completed ||
                transfer.state == FlashTransferState.Cancelled ||
                transfer.state == FlashTransferState.Failed
            CardSurface {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DirectionBadge(transfer.direction)
                        Spacer(Modifier.width(6.dp))
                        FlashText(transfer.fileName, style = MaterialTheme.typography.titleSmall)
                    }
                    FlashText(
                        "$percent%",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    FlashText(
                        "${transfer.state}${transfer.errorMessage?.let { " Â· $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                    FlashText(
                        formatBytes(transfer.bytesDone) + " / " + formatBytes(transfer.bytesTotal) +
                            if (transfer.speedBytesPerSec > 0) " Â· ${formatBytes(transfer.speedBytesPerSec)}/s" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                if (!terminal) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DevButton(
                            if (transfer.state == FlashTransferState.Paused) "â–¶ Resume" else "â¸ Pause",
                            highlighted = true,
                        ) { onPauseResume(transfer) }
                        DevButton("Cancel") { onCancel(transfer) }
                    }
                }
            }
        }
    }
}

// ---- NET tab -----------------------------------------------------------------

@Composable
private fun NetTab(
    mode: FlashDiscoveryMode,
    onModeChanged: (FlashDiscoveryMode) -> Unit,
    onProbeGateway: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { SectionTitle("Discovery Mode") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlashDiscoveryMode.entries.forEach { m ->
                    DevButton(
                        label = m.name.lowercase().replaceFirstChar { it.uppercase() },
                        highlighted = m == mode,
                    ) { onModeChanged(m) }
                }
            }
        }
        item { SectionTitle("Diagnostics") }
        item {
            CardSurface {
                FlashText("Gateway probe", style = MaterialTheme.typography.titleSmall)
                FlashText(
                    "Reads this device's real IPv4 gateways and tries a direct WS connect to each. " +
                        "Use when mDNS can't see the hotspot host or router-side peer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                DevButton("Probe Gateway") { onProbeGateway() }
            }
        }
    }
}

// ---- Shared pieces ------------------------------------------------------------

private fun FlashDiscoveredEndpoint.toFlashDevice(): FlashDevice = FlashDevice(
    id = deviceId,
    friendlyName = friendlyName,
    transportType = transportType,
)

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024f * 1024f))
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
private fun HealthDot(connected: Boolean) {
    Box(
        Modifier
            .width(10.dp)
            .height(10.dp)
            .background(
                if (connected) Color(0xFF4CAF50) else Color(0xFFFF9800),
                RoundedCornerShape(5.dp),
            ),
    )
}

@Composable
private fun DirectionBadge(direction: FlashTransferDirection) {
    val label = if (direction == FlashTransferDirection.Receiving) "RX" else "TX"
    Box(
        Modifier
            .background(
                if (direction == FlashTransferDirection.Receiving) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        FlashText(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SectionTitle(text: String) {
    FlashText(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp, start = 2.dp),
    )
}

@Composable
private fun EmptyHint(text: String) {
    FlashText(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray,
    )
}

@Composable
private fun CardSurface(
    container: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (container) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                RoundedCornerShape(10.dp),
            )
            .padding(10.dp),
    ) { content() }
}

@Composable
fun DevConsoleChip(onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                RoundedCornerShape(16.dp),
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        FlashText(
            "Dev",
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun DevButton(
    label: String,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        highlighted -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val fg = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .background(bg, RoundedCornerShape(10.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        FlashText(label, color = fg, style = MaterialTheme.typography.labelLarge)
    }
}

