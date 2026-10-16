package com.transfer.flash.debug.radiospike

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.transfer.flash.core.radio.link.BluetoothPermissions
import com.transfer.flash.core.radio.link.DiscoveredDevice
import com.transfer.flash.core.radio.link.PairResult
import com.transfer.flash.core.radio.link.android.AndroidRadioDiscovery
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Debug-only Android Radio Spike Screen (BT-R11).
 * Resides exclusively in app/src/debug/ and is never shipped in release builds.
 *
 * Safety contract:
 * - Runtime permissions requested strictly at point of use.
 * - Bluetooth addresses truncated to last 3 bytes in UI.
 * - Scan has a 12-second countdown timer and stops immediately when leaving the screen.
 * - In-app pairing triggered via BluetoothDevice.createBond().
 * - NO RFCOMM connect, NO data/byte transmission to any device.
 */
public class RadioSpikeActivity : ComponentActivity() {

    private val TAG = "RadioSpikeActivity"

    private var scanTimer: CountDownTimer? = null
    private val radioDiscovery by lazy { AndroidRadioDiscovery(this) }
    private var scanJob: Job? = null

    // Compose observable state
    private var permissionStatusLine by mutableStateOf("Permissions: Not requested yet")
    private var scanStatusLine by mutableStateOf("Scan: Idle")
    private var pairStatusLine by mutableStateOf("Pairing: Idle")
    private var scanSecondsRemaining by mutableIntStateOf(0)
    private var isScanning by mutableStateOf(false)

    private val bondedDevices = mutableStateListOf<DiscoveredRadioCandidate>()
    private val discoveredDevices = mutableStateListOf<DiscoveredRadioCandidate>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            permissionStatusLine = "Permissions: Granted (Nearby devices / Bluetooth)"
            refreshBondedDevices()
        } else {
            permissionStatusLine = "Permissions: Denied — Allow Nearby devices in Settings to find your radio."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RadioSpikeScreen(
                        permissionStatus = permissionStatusLine,
                        scanStatus = scanStatusLine,
                        pairStatus = pairStatusLine,
                        isScanning = isScanning,
                        scanSecondsRemaining = scanSecondsRemaining,
                        bondedList = bondedDevices,
                        discoveredList = discoveredDevices,
                        onRequestPermissions = { requestPermissionsAtPointOfUse() },
                        onStartScan = { startTimedDiscoveryScan() },
                        onStopScan = { stopDiscoveryScan() },
                        onPairDevice = { candidate -> initiatePairing(candidate) }
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (hasRequiredPermissions()) {
            permissionStatusLine = "Permissions: Granted"
            refreshBondedDevices()
        }
    }

    override fun onStop() {
        super.onStop()
        // Stop scan immediately on leaving screen
        stopDiscoveryScan()
        Log.d(TAG, "Stopped: scan aborted on leaving screen")
    }

    private fun hasRequiredPermissions(): Boolean {
        return BluetoothPermissions.mandatory(Build.VERSION.SDK_INT).all {
            checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissionsAtPointOfUse() {
        val permissions = BluetoothPermissions.runtimePermissions(Build.VERSION.SDK_INT).toTypedArray()
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions)
        } else {
            refreshBondedDevices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedDevices() {
        if (!hasRequiredPermissions()) return
        lifecycleScope.launch {
            val paired = radioDiscovery.pairedDevices()
            bondedDevices.clear()
            for (dev in paired) {
                bondedDevices.add(
                    DiscoveredRadioCandidate(
                        name = dev.name,
                        address = dev.bluetoothAddress,
                        bondState = BluetoothDevice.BOND_BONDED,
                        serviceUuids = emptyList()
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTimedDiscoveryScan() {
        if (!hasRequiredPermissions()) {
            requestPermissionsAtPointOfUse()
            return
        }

        discoveredDevices.clear()
        scanJob?.cancel()
        scanTimer?.cancel()
        isScanning = true
        scanSecondsRemaining = 12
        scanStatusLine = "Scan: Discovering radios (${scanSecondsRemaining}s remaining)..."

        scanTimer = object : CountDownTimer(12_000, 1_000) {
            override fun onTick(millisUntilFinished: Long) {
                scanSecondsRemaining = (millisUntilFinished / 1000).toInt()
                scanStatusLine = "Scan: Discovering radios (${scanSecondsRemaining}s remaining)..."
            }

            override fun onFinish() {
                stopDiscoveryScan()
            }
        }.start()

        scanJob = lifecycleScope.launch {
            radioDiscovery.scan(12_000L).collect { dev ->
                val candidate = DiscoveredRadioCandidate(
                    name = dev.name,
                    address = dev.bluetoothAddress,
                    bondState = if (dev.isPaired) BluetoothDevice.BOND_BONDED else BluetoothDevice.BOND_NONE,
                    rssi = dev.rssi?.toShort(),
                    deviceClass = null
                )
                val existingIdx = discoveredDevices.indexOfFirst { it.address.equals(dev.bluetoothAddress, ignoreCase = true) }
                if (existingIdx >= 0) {
                    discoveredDevices[existingIdx] = candidate
                } else {
                    discoveredDevices.add(candidate)
                }
            }
            isScanning = false
            scanStatusLine = "Scan: Completed. Discovered ${discoveredDevices.size} devices."
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopDiscoveryScan() {
        scanTimer?.cancel()
        scanTimer = null
        scanJob?.cancel()
        scanJob = null
        isScanning = false
        scanSecondsRemaining = 0
    }

    @SuppressLint("MissingPermission")
    private fun initiatePairing(candidate: DiscoveredRadioCandidate) {
        if (!hasRequiredPermissions()) {
            requestPermissionsAtPointOfUse()
            return
        }

        pairStatusLine = "Pairing: Initiating bonding with ${candidate.name}..."
        lifecycleScope.launch {
            val device = DiscoveredDevice(
                name = candidate.name,
                bluetoothAddress = candidate.address,
                isPaired = candidate.bondState == BluetoothDevice.BOND_BONDED,
                rssi = candidate.rssi?.toInt()
            )
            when (val result = radioDiscovery.pair(device)) {
                is PairResult.Success -> {
                    pairStatusLine = "Pairing: Successfully paired with ${candidate.name}."
                    refreshBondedDevices()
                }
                is PairResult.AlreadyPaired -> {
                    pairStatusLine = "Pairing: Already paired with ${candidate.name}."
                    refreshBondedDevices()
                }
                is PairResult.Failed -> {
                    pairStatusLine = "Pairing: Failed (${result.reason.sentence})"
                    refreshBondedDevices()
                }
            }
        }
    }
}

@Composable
public fun RadioSpikeScreen(
    permissionStatus: String,
    scanStatus: String,
    pairStatus: String,
    isScanning: Boolean,
    scanSecondsRemaining: Int,
    bondedList: List<DiscoveredRadioCandidate>,
    discoveredList: List<DiscoveredRadioCandidate>,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onPairDevice: (DiscoveredRadioCandidate) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Radio Spike (BT-R11 Debug Tool)",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Hardware scan & bonding test. Strictly NO RFCOMM connect or data sent.",
            fontSize = 12.sp,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(text = permissionStatus, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = scanStatus, fontSize = 13.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = pairStatus, fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onRequestPermissions,
                modifier = Modifier.weight(1f)
            ) {
                Text(text = "Permissions", fontSize = 12.sp)
            }

            if (isScanning) {
                Button(
                    onClick = onStopScan,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = "Stop (${scanSecondsRemaining}s)", fontSize = 12.sp)
                }
            } else {
                Button(
                    onClick = onStartScan,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "Scan Radios", fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Ranked lists
        val rankedDiscovered = AndroidRadioDeviceRanker.rankCandidates(discoveredList)

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = "Discovered Devices (${rankedDiscovered.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (rankedDiscovered.isEmpty()) {
                item {
                    Text(
                        text = if (isScanning) "Searching for nearby devices..." else "No devices discovered. Press 'Scan Radios'.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                items(rankedDiscovered) { item ->
                    DiscoveredDeviceRow(item = item, onPairDevice = onPairDevice)
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Color.LightGray.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Bonded Devices (${bondedList.size})",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (bondedList.isEmpty()) {
                item {
                    Text(
                        text = "No bonded Bluetooth devices found.",
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            } else {
                val rankedBonded = AndroidRadioDeviceRanker.rankCandidates(bondedList)
                items(rankedBonded) { item ->
                    BondedDeviceRow(item = item)
                }
            }
        }
    }
}

@Composable
private fun DiscoveredDeviceRow(
    item: RankedRadioCandidate,
    onPairDevice: (DiscoveredRadioCandidate) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.category == AndroidRadioCategory.RADIO) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.candidate.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = "Address: ${item.candidate.maskedAddress}  |  Rank ${item.rank} (${item.category})",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
                Text(
                    text = item.reason,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
                if (item.candidate.rssi != null) {
                    Text(
                        text = "RSSI: ${item.candidate.rssi} dBm",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }

            if (item.candidate.bondState == DiscoveredRadioCandidate.BOND_BONDED) {
                Text(
                    text = "Paired",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                OutlinedButton(
                    onClick = { onPairDevice(item.candidate) }
                ) {
                    Text(text = "Pair", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun BondedDeviceRow(item: RankedRadioCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Text(
                text = "${item.candidate.name}  [${item.candidate.maskedAddress}]",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = "Rank ${item.rank} (${item.category}) - ${item.reason}",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            if (item.candidate.serviceUuids.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Service UUIDs (${item.candidate.serviceUuids.size}):",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                for (uuid in item.candidate.serviceUuids) {
                    val isSpp = uuid.equals(AndroidRadioDeviceRanker.SPP_UUID_STRING, ignoreCase = true)
                    Text(
                        text = " • $uuid" + (if (isSpp) " [SPP]" else ""),
                        fontSize = 10.sp,
                        color = if (isSpp) MaterialTheme.colorScheme.primary else Color.Gray
                    )
                }
            }
        }
    }
}
