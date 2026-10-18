package com.example.syncaudio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var audioReceiver: AudioReceiver? = null
    private var dashboardStatus by mutableStateOf("Ready")
    private var senderStatus by mutableStateOf("Not started")
    private var receiverStatus by mutableStateOf("Not connected")
    private val eventLog = mutableStateListOf<String>()

    private fun addEvent(message: String) {
        val time = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        eventLog.add(0, "$time  $message")
        while (eventLog.size > 30) eventLog.removeAt(eventLog.lastIndex)
    }

    private fun stopReceiver() {
        val receiver = audioReceiver
        audioReceiver = null
        receiver?.stop()
        receiverStatus = "Stopped"
        addEvent("Receiver stopped")
    }

    private fun stopSender() {
        val serviceIntent = Intent(this, AudioCaptureService::class.java)
        if (stopService(serviceIntent)) {
            senderStatus = "Stopped"
            addEvent("Sender service stopped")
        }
    }

    private fun switchToSender() {
        stopReceiver()
        dashboardStatus = "Sender mode selected"
        addEvent("Switched to Sender mode")
    }

    private fun switchToReceiver() {
        stopSender()
        stopReceiver()
        senderStatus = "Stopped"
        dashboardStatus = "Receiver mode selected"
        addEvent("Switched to Receiver mode")
    }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, AudioCaptureService::class.java).apply {
                    action = AudioCaptureService.ACTION_START
                    putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                senderStatus = "Capture service requested"
                dashboardStatus = "Sender starting"
                addEvent("Audio capture service requested")
            } else {
                senderStatus = "Permission not granted"
                dashboardStatus = "Capture cancelled"
                addEvent("Screen capture permission cancelled")
            }
        }

    private fun requestAudioCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSender() {
        stopReceiver()
        stopSender()
        dashboardStatus = "Waiting for capture approval"
        addEvent("Sender capture requested")
        requestAudioCapture()
    }

    private fun startReceiver() {
        stopSender()
        stopReceiver()
        receiverStatus = "Searching for sender"
        dashboardStatus = "Discovering SyncAudio sender"
        addEvent("Automatic sender discovery requested")
        audioReceiver = AudioReceiver(
            context = this,
            onStatus = { status ->
                runOnUiThread {
                    if (audioReceiver != null) {
                        receiverStatus = status
                        dashboardStatus = status
                        addEvent(status)
                    }
                }
            }
        )
        audioReceiver?.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addEvent("SyncAudio dashboard opened")

        setContent {
            MaterialTheme {
                val intercomDevices = remember { mutableStateListOf<IntercomDevice>() }
                val connectedIntercomDevices = remember { mutableStateListOf<String>() }
                val intercomDeviceName = remember { "SyncAudio - ${Build.MODEL}" }
                val intercomStatus = remember { mutableStateOf("Intercom ready") }
                var intercomAudioStarted by remember { mutableStateOf(false) }

                val intercomManager = remember {
                    NearbyIntercomManager(
                        context = this@MainActivity,
                        onStatus = { status ->
                            runOnUiThread { intercomStatus.value = status }
                        },
                        onDevicesChanged = { devices ->
                            runOnUiThread {
                                intercomDevices.clear()
                                intercomDevices.addAll(devices)
                            }
                        },
                        onConnectionsChanged = { endpoints ->
                            runOnUiThread {
                                connectedIntercomDevices.clear()
                                connectedIntercomDevices.addAll(endpoints)
                                if (endpoints.isEmpty() && intercomAudioStarted) {
                                    stopService(
                                        Intent(this@MainActivity, IntercomAudioService::class.java)
                                    )
                                    intercomAudioStarted = false
                                }
                            }
                        },
                        onAudioFrame = { _, audioBytes ->
                            if (intercomAudioStarted) {
                                val playIntent = Intent(
                                    this@MainActivity,
                                    IntercomAudioService::class.java
                                ).apply {
                                    action = IntercomAudioService.ACTION_PLAY_FRAME
                                    putExtra(
                                        IntercomAudioService.EXTRA_AUDIO_BYTES,
                                        audioBytes
                                    )
                                }
                                startService(playIntent)
                            }
                        }
                    )
                }

                DisposableEffect(intercomManager) {
                    val audioFrameReceiver = object : BroadcastReceiver() {
                        override fun onReceive(
                            context: android.content.Context,
                            intent: Intent
                        ) {
                            if (intent.action != IntercomAudioService.ACTION_AUDIO_FRAME) return
                            val audioBytes = intent.getByteArrayExtra(
                                IntercomAudioService.EXTRA_AUDIO_BYTES
                            ) ?: return
                            intercomManager.sendAudioFrame(audioBytes)
                        }
                    }

                    ContextCompat.registerReceiver(
                        this@MainActivity,
                        audioFrameReceiver,
                        IntentFilter(IntercomAudioService.ACTION_AUDIO_FRAME),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )

                    onDispose {
                        try {
                            unregisterReceiver(audioFrameReceiver)
                        } catch (_: IllegalArgumentException) {
                        }
                        intercomManager.stopAll()
                        stopService(
                            Intent(this@MainActivity, IntercomAudioService::class.java)
                        )
                    }
                }

                val intercomPermissions = remember {
                    buildList {
                        add(Manifest.permission.RECORD_AUDIO)
                        if (Build.VERSION.SDK_INT >= 31) {
                            add(Manifest.permission.BLUETOOTH_SCAN)
                            add(Manifest.permission.BLUETOOTH_ADVERTISE)
                            add(Manifest.permission.BLUETOOTH_CONNECT)
                        } else {
                            add(Manifest.permission.ACCESS_COARSE_LOCATION)
                            add(Manifest.permission.ACCESS_FINE_LOCATION)
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    }
                }

                var pendingIntercomAction by remember { mutableStateOf("") }
                val intercomPermissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { results ->
                        if (results.values.all { it }) {
                            when (pendingIntercomAction) {
                                "host" -> intercomManager.startAdvertising(intercomDeviceName)
                                "discover" -> intercomManager.startDiscovery()
                            }
                        } else {
                            intercomStatus.value =
                                "Nearby/microphone permission denied. Check App Settings."
                        }
                        pendingIntercomAction = ""
                    }

                Box(modifier = Modifier.fillMaxSize()) {
                    ParticleFlow(modifier = Modifier.fillMaxSize())
                    var selectedMode by remember { mutableStateOf("Receiver") }

                    val permissionLauncher =
                        rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            if (granted) startSender() else {
                                dashboardStatus = "Audio permission denied"
                                senderStatus = "Permission denied"
                                addEvent("Audio permission denied")
                            }
                        }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("SyncAudio", style = MaterialTheme.typography.headlineLarge)
                        Text("V1.3 • Intercom Audio Prototype", style = MaterialTheme.typography.bodyMedium)

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Dashboard status", style = MaterialTheme.typography.titleMedium)
                                Text(dashboardStatus)
                                HorizontalDivider()
                                Text("Sender: $senderStatus")
                                Text("Receiver: $receiverStatus")
                            }
                        }

                        Text("MUSIC STREAMING", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "These Sender/Receiver controls are for music streaming only. They do not set the intercom role.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Choose this phone's music role", style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedMode == "Sender",
                                onClick = {
                                    switchToSender()
                                    selectedMode = "Sender"
                                },
                                label = { Text("Sender") }
                            )
                            FilterChip(
                                selected = selectedMode == "Receiver",
                                onClick = {
                                    switchToReceiver()
                                    selectedMode = "Receiver"
                                },
                                label = { Text("Receiver") }
                            )
                        }

                        if (selectedMode == "Sender") {
                            Text("Use this phone to capture and send audio.")
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    val granted = ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) startSender()
                                    else {
                                        stopReceiver()
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            ) { Text("Start Audio Capture") }
                        } else {
                            Text("Search this Wi-Fi/hotspot network for a sender and play its audio.")
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { startReceiver() }
                            ) { Text("Find Sender & Start Receiver") }
                            Button(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    stopReceiver()
                                    dashboardStatus = "Receiver stopped"
                                }
                            ) { Text("Stop Receiver") }
                        }

                        HorizontalDivider()
                        Text("INTERCOM (SEPARATE FROM MUSIC)", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Connect two phones first, then start hands-free audio on both.",
                            style = MaterialTheme.typography.bodySmall
                        )

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("Intercom Room Status", style = MaterialTheme.typography.titleMedium)
                                Text(intercomStatus.value)
                                HorizontalDivider()
                                Text("Device name: $intercomDeviceName")
                                Text("Connected devices: ${connectedIntercomDevices.size}")
                                if (connectedIntercomDevices.isEmpty()) {
                                    Text("No active connections")
                                } else {
                                    connectedIntercomDevices.forEach { endpointId ->
                                        val name = intercomDevices
                                            .firstOrNull { it.endpointId == endpointId }
                                            ?.name ?: endpointId
                                        Text("Connected: $name")
                                    }
                                }
                                HorizontalDivider()
                                Text(
                                    if (intercomAudioStarted) "Audio transmission: Starting/active"
                                    else "Audio transmission: Stopped",
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val missing = intercomPermissions.filter {
                                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                                            PackageManager.PERMISSION_GRANTED
                                }
                                if (missing.isEmpty()) {
                                    intercomManager.startAdvertising(intercomDeviceName)
                                } else {
                                    pendingIntercomAction = "host"
                                    intercomPermissionLauncher.launch(missing.toTypedArray())
                                }
                            }
                        ) { Text("Host Intercom Room") }

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val missing = intercomPermissions.filter {
                                    ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                                            PackageManager.PERMISSION_GRANTED
                                }
                                if (missing.isEmpty()) intercomManager.startDiscovery()
                                else {
                                    pendingIntercomAction = "discover"
                                    intercomPermissionLauncher.launch(missing.toTypedArray())
                                }
                            }
                        ) { Text("Find Intercom Devices") }

                        if (intercomDevices.isEmpty()) {
                            Text("No nearby intercom devices found yet.")
                        } else {
                            Text("Available devices", style = MaterialTheme.typography.titleMedium)
                            intercomDevices.forEach { device ->
                                OutlinedButton(
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        intercomManager.requestConnection(
                                            endpointId = device.endpointId,
                                            deviceName = intercomDeviceName
                                        )
                                    }
                                ) { Text("Connect to ${device.name}") }
                            }
                        }

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = connectedIntercomDevices.isNotEmpty() && !intercomAudioStarted,
                            onClick = {
                                val hasMicPermission = ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (!hasMicPermission) {
                                    intercomStatus.value = "Grant microphone permission first"
                                } else {
                                    try {
                                        val serviceIntent = Intent(
                                            this@MainActivity,
                                            IntercomAudioService::class.java
                                        ).setAction(IntercomAudioService.ACTION_START)
                                        ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                        intercomAudioStarted = true
                                        intercomStatus.value = "Starting hands-free audio..."
                                        addEvent("Intercom audio start requested")
                                    } catch (error: Exception) {
                                        intercomStatus.value = "Could not start audio: ${error.message}"
                                    }
                                }
                            }
                        ) { Text("Start Hands-Free Audio") }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = intercomAudioStarted,
                            onClick = {
                                stopService(Intent(this@MainActivity, IntercomAudioService::class.java))
                                intercomAudioStarted = false
                                intercomStatus.value = "Hands-free audio stopped"
                                addEvent("Intercom audio stopped")
                            }
                        ) { Text("Stop Hands-Free Audio") }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                stopService(Intent(this@MainActivity, IntercomAudioService::class.java))
                                intercomAudioStarted = false
                                intercomManager.stopAll()
                            }
                        ) { Text("Stop Intercom") }

                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Recent events", style = MaterialTheme.typography.titleMedium)
                            TextButton(
                                onClick = {
                                    eventLog.clear()
                                    addEvent("Event log cleared")
                                }
                            ) { Text("Clear") }
                        }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (eventLog.isEmpty()) Text("No events yet")
                                else eventLog.take(20).forEach { event ->
                                    Text(event, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        audioReceiver?.stop()
        audioReceiver = null
        super.onDestroy()
    }
}
