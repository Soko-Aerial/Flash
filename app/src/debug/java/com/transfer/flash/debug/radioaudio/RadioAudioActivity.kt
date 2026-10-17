package com.transfer.flash.debug.radioaudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.transfer.flash.core.radio.link.maskBluetoothAddress

/**
 * Diagnostic debug activity for testing Bluetooth SCO audio routing (Phase R4 / BT-R10).
 *
 * SAFETY CONTRACT:
 * - NO audio recording is performed or piped to the radio.
 * - NO tones, chimes, or audio signals are played.
 * - NO RF PTT transmission is keyed.
 * - Audio routing is established strictly on explicit owner button tap.
 * - Audio route is automatically reset to normal when exiting the activity.
 */
public class RadioAudioActivity : ComponentActivity() {

    private companion object {
        private const val TAG = "RadioAudioActivity"
    }

    private lateinit var audioManager: AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothHeadset: BluetoothHeadset? = null

    private var permissionsGranted by mutableStateOf(false)
    private var headsetProfileConnected by mutableStateOf(false)
    private var connectedHeadsetDevices = mutableStateListOf<String>()
    private var isAudioConnectedByHeadset by mutableStateOf(false)
    private var audioDeviceList = mutableStateListOf<AndroidAudioDeviceItem>()
    private var routingStatusText by mutableStateOf("Idle (Normal Audio Mode)")
    private var isScoActive by mutableStateOf(false)
    private var lastErrorText by mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        permissionsGranted = allGranted
        if (allGranted) {
            refreshAudioDevices()
            refreshHeadsetDevices()
        } else {
            lastErrorText = "Required permissions were not granted."
        }
    }

    private val scoStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_ERROR)
                val stateDesc = when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
                    AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
                    else -> "UNKNOWN ($state)"
                }
                Log.d(TAG, "SCO audio state broadcast: $stateDesc")
                routingStatusText = "SCO Broadcast State: $stateDesc"
                isScoActive = (state == AudioManager.SCO_AUDIO_STATE_CONNECTED)
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = proxy as? BluetoothHeadset
                headsetProfileConnected = true
                Log.d(TAG, "BluetoothHeadset proxy connected")
                refreshHeadsetDevices()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HEADSET) {
                bluetoothHeadset = null
                headsetProfileConnected = false
                Log.d(TAG, "BluetoothHeadset proxy disconnected")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter

        checkPermissions()
        bindHeadsetProxy()

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        registerReceiver(scoStateReceiver, filter)

        setContent {
            RadioAudioProbeScreen(
                permissionsGranted = permissionsGranted,
                headsetProfileConnected = headsetProfileConnected,
                connectedHeadsetDevices = connectedHeadsetDevices,
                isAudioConnectedByHeadset = isAudioConnectedByHeadset,
                audioDevices = audioDeviceList,
                routingStatusText = routingStatusText,
                isScoActive = isScoActive,
                lastErrorText = lastErrorText,
                onRequestPermissions = { requestPermissions() },
                onRefresh = {
                    refreshAudioDevices()
                    refreshHeadsetDevices()
                },
                onRouteSco = { routeAudioToSco() },
                onClearRoute = { clearAudioRoute() }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (permissionsGranted) {
            refreshAudioDevices()
            refreshHeadsetDevices()
        }
    }

    override fun onStop() {
        super.onStop()
        // Automatic safe cleanup on leaving the screen
        clearAudioRoute()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(scoStateReceiver)
        } catch (_: Throwable) {}
        try {
            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadset)
        } catch (_: Throwable) {}
        clearAudioRoute()
    }

    private fun checkPermissions() {
        val hasBtConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val hasRecordAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasModifyAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED

        permissionsGranted = hasBtConnect && hasRecordAudio && hasModifyAudio
        if (permissionsGranted) {
            refreshAudioDevices()
            refreshHeadsetDevices()
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        perms.add(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun bindHeadsetProxy() {
        bluetoothAdapter?.getProfileProxy(this, profileListener, BluetoothProfile.HEADSET)
    }

    @SuppressLint("MissingPermission")
    private fun refreshHeadsetDevices() {
        connectedHeadsetDevices.clear()
        val headset = bluetoothHeadset
        if (headset != null && permissionsGranted) {
            try {
                val devices = headset.connectedDevices
                var anyAudio = false
                for (dev in devices) {
                    val masked = maskBluetoothAddress(dev.address)
                    val devName = dev.name ?: "Unknown Device"
                    val isAudio = headset.isAudioConnected(dev)
                    if (isAudio) anyAudio = true
                    connectedHeadsetDevices.add("$devName [$masked] (AudioConnected: $isAudio)")
                }
                isAudioConnectedByHeadset = anyAudio
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to query connected headset devices", t)
            }
        }
    }

    private fun refreshAudioDevices() {
        audioDeviceList.clear()
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in outputs) {
                val isSco = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                audioDeviceList.add(
                    AndroidAudioDeviceItem(
                        id = dev.id,
                        name = dev.productName.toString(),
                        type = dev.type,
                        typeName = AndroidAudioRoutingProbe.resolveAudioDeviceTypeName(dev.type),
                        isSink = dev.isSink,
                        isSource = dev.isSource,
                        isBluetoothSco = isSco
                    )
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to query audio devices", t)
        }
    }

    @SuppressLint("MissingPermission")
    private fun routeAudioToSco() {
        lastErrorText = null
        if (!permissionsGranted) {
            lastErrorText = "Cannot route audio: permissions not granted."
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                val scoDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (scoDevice == null) {
                    lastErrorText = "No AudioDeviceInfo.TYPE_BLUETOOTH_SCO found in audio outputs."
                    routingStatusText = "Failed: No Bluetooth SCO device available"
                    return
                }

                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                val success = audioManager.setCommunicationDevice(scoDevice)
                if (success) {
                    isScoActive = true
                    routingStatusText = "Active: Routed via setCommunicationDevice(${scoDevice.productName})"
                    Log.d(TAG, "Successfully set communication device to: ${scoDevice.productName}")
                } else {
                    isScoActive = false
                    lastErrorText = "setCommunicationDevice returned false for ${scoDevice.productName}."
                    routingStatusText = "Failed: setCommunicationDevice returned false"
                }
            } else {
                // Legacy path (API < 31)
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                routingStatusText = "Requested: startBluetoothSco() invoked (waiting for broadcast)"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during SCO routing", t)
            lastErrorText = "Exception during SCO routing: ${t.message}"
            routingStatusText = "Error: ${t.message}"
        }
    }

    private fun clearAudioRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            isScoActive = false
            routingStatusText = "Idle (Normal Audio Mode - Route Cleared)"
            Log.d(TAG, "Audio route cleared and returned to MODE_NORMAL")
        } catch (t: Throwable) {
            Log.e(TAG, "Exception during clearAudioRoute", t)
        }
    }
}

@Composable
private fun RadioAudioProbeScreen(
    permissionsGranted: Boolean,
    headsetProfileConnected: Boolean,
    connectedHeadsetDevices: List<String>,
    isAudioConnectedByHeadset: Boolean,
    audioDevices: List<AndroidAudioDeviceItem>,
    routingStatusText: String,
    isScoActive: Boolean,
    lastErrorText: String?,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
    onRouteSco: () -> Unit,
    onClearRoute: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Text(
                text = "Radio Audio Probe (BT-R10)",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Diagnostic tool for Bluetooth SCO / HFP audio routing. Read-only by default. NO tones played, NO audio recorded, NO PTT keyed.",
                fontSize = 12.sp,
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Safety notice card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2411)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "SAFETY WARNING",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFB74D),
                        fontSize = 13.sp
                    )
                    Text(
                        text = "Do not play audio to the radio or speak on VOX without verifying your operating frequency and authorization. This tool tests SCO routing only on your explicit tap.",
                        color = Color(0xFFFFE0B2),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Status: $routingStatusText",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isScoActive) Color(0xFF81C784) else Color(0xFF64B5F6),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Permissions: " + if (permissionsGranted) "Granted" else "Missing",
                        color = if (permissionsGranted) Color.Green else Color.Red,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Headset Profile Proxy: " + if (headsetProfileConnected) "Connected" else "Disconnected",
                        color = if (headsetProfileConnected) Color.Green else Color.Gray,
                        fontSize = 12.sp
                    )
                    Text(
                        text = "Headset Audio Connected: " + if (isAudioConnectedByHeadset) "YES (SCO Link Up)" else "NO",
                        color = if (isAudioConnectedByHeadset) Color.Green else Color.Yellow,
                        fontSize = 12.sp
                    )
                    if (lastErrorText != null) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error: $lastErrorText",
                            color = Color(0xFFE57373),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!permissionsGranted) {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Grant Perms", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onRouteSco,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Route SCO", fontSize = 12.sp)
                    }
                    Button(
                        onClick = onClearRoute,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Route", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onRefresh,
                    modifier = Modifier.weight(0.8f)
                ) {
                    Text("Refresh", fontSize = 12.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Connected Headset Devices (${connectedHeadsetDevices.size}):",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.LightGray
            )
            if (connectedHeadsetDevices.isEmpty()) {
                Text(
                    text = "  (No Bluetooth Headset devices connected)",
                    fontSize = 12.sp,
                    color = Color.DarkGray
                )
            } else {
                for (dev in connectedHeadsetDevices) {
                    Text(
                        text = "  • $dev",
                        fontSize = 12.sp,
                        color = Color(0xFF81D4FA)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Audio Devices (${audioDevices.size}):",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.LightGray
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(audioDevices) { dev ->
                    val isSco = dev.isBluetoothSco
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSco) Color(0xFF1B3A24) else Color(0xFF252525)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = dev.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "Type: ${dev.typeName} (ID ${dev.id})",
                                    fontSize = 11.sp,
                                    color = if (isSco) Color(0xFF81C784) else Color.Gray
                                )
                            }
                            if (isSco) {
                                Text(
                                    text = "SCO READY",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF81C784),
                                    modifier = Modifier
                                        .background(Color(0xFF0E2214), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
