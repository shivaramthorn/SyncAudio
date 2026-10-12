package com.example.syncaudio

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

        while (eventLog.size > 30) {
            eventLog.removeAt(eventLog.lastIndex)
        }
    }

    private fun stopReceiver() {
        val receiver = audioReceiver
        audioReceiver = null

        receiver?.stop()

        receiverStatus = "Stopped"
        addEvent("Receiver stopped")
    }

    private fun stopSender() {
        val serviceIntent = Intent(
            this,
            AudioCaptureService::class.java
        )

        val wasRunning = stopService(serviceIntent)

        if (wasRunning) {
            senderStatus = "Stopped"
            addEvent("Sender service stopped")
        }
    }

    private fun switchToSender() {
        // Do not let the receiver play while this phone
        // is being prepared to capture audio.
        stopReceiver()

        dashboardStatus = "Sender mode selected"
        addEvent("Switched to Sender mode")
    }

    private fun switchToReceiver() {
        // Stop audio capture and streaming before receiving.
        stopSender()
        stopReceiver()

        senderStatus = "Stopped"
        dashboardStatus = "Receiver mode selected"
        addEvent("Switched to Receiver mode")
    }

    private val projectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK && result.data != null) {
                val serviceIntent = Intent(
                    this,
                    AudioCaptureService::class.java
                ).apply {
                    action = AudioCaptureService.ACTION_START

                    putExtra(
                        AudioCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )
                    putExtra(
                        AudioCaptureService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )

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
        val manager = getSystemService(
            MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        projectionLauncher.launch(
            manager.createScreenCaptureIntent()
        )
    }

    private fun startSender() {
        // Ensure the receiver is completely stopped first.
        stopReceiver()

        // Avoid starting a second capture service.
        stopSender()

        dashboardStatus = "Waiting for capture approval"
        addEvent("Sender capture requested")

        requestAudioCapture()
    }

    private fun startReceiver() {
        // Sender and receiver must not run together on this phone.
        stopSender()
        stopReceiver()

        receiverStatus = "Searching for sender"
        dashboardStatus = "Discovering SyncAudio sender"
        addEvent("Automatic sender discovery requested")

        audioReceiver = AudioReceiver(
            context = this,
            onStatus = { status ->
                runOnUiThread {
                    // Ignore callbacks from an old receiver.
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

                var selectedMode by remember {
                    mutableStateOf("Receiver")
                }

                val permissionLauncher =
                    rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { granted ->
                        if (granted) {
                            startSender()
                        } else {
                            dashboardStatus = "Audio permission denied"
                            senderStatus = "Permission denied"
                            addEvent("Audio permission denied")
                        }
                    }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {

                    Text(
                        text = "SyncAudio",
                        style = MaterialTheme.typography.headlineLarge
                    )

                    Text(
                        text = "V1.2 • Role Switching Test",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Dashboard status",
                                style = MaterialTheme.typography.titleMedium
                            )

                            Text(dashboardStatus)
                            HorizontalDivider()
                            Text("Sender: $senderStatus")
                            Text("Receiver: $receiverStatus")
                        }
                    }

                    Text(
                        text = "Choose this phone's role",
                        style = MaterialTheme.typography.titleMedium
                    )

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

                        Text(
                            text = "Use this phone to capture and send audio."
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val granted =
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED

                                if (granted) {
                                    startSender()
                                } else {
                                    // Stop receiver before requesting permission.
                                    stopReceiver()
                                    permissionLauncher.launch(
                                        Manifest.permission.RECORD_AUDIO
                                    )
                                }
                            }
                        ) {
                            Text("Start Audio Capture")
                        }

                    } else {

                        Text(
                            text = "Search this Wi-Fi/hotspot network for a sender and play its audio."
                        )

                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                startReceiver()
                            }
                        ) {
                            Text("Find Sender & Start Receiver")
                        }

                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                stopReceiver()
                                dashboardStatus = "Receiver stopped"
                            }
                        ) {
                            Text("Stop Receiver")
                        }
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Recent events",
                            style = MaterialTheme.typography.titleMedium
                        )

                        TextButton(
                            onClick = {
                                eventLog.clear()
                                addEvent("Event log cleared")
                            }
                        ) {
                            Text("Clear")
                        }
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        if (eventLog.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No events yet")
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                items(eventLog) { event ->
                                    Text(
                                        text = event,
                                        style = MaterialTheme.typography.bodySmall
                                    )
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
