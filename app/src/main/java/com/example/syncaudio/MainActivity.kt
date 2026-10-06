package com.example.syncaudio

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var audioReceiver: AudioReceiver? = null

    private val projectionLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == RESULT_OK && result.data != null) {

                val serviceIntent = Intent(
                    this,
                    AudioCaptureService::class.java
                ).apply {

                    putExtra(
                        AudioCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )

                    putExtra(
                        AudioCaptureService.EXTRA_DATA,
                        result.data
                    )
                }

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            var status by remember {
                mutableStateOf("Ready to connect")
            }

            val permissionLauncher =
                rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->

                    if (granted) {

                        status = "Waiting for capture approval"

                        requestAudioCapture()

                    } else {

                        status = "Microphone permission denied"
                    }
                }

            MaterialTheme {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),

                    verticalArrangement =
                        Arrangement.Center,

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    Text(
                        text = "SyncAudio",
                        style =
                            MaterialTheme.typography.headlineLarge
                    )

                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )

                    Text(text = status)

                    Spacer(
                        modifier = Modifier.height(24.dp)
                    )

                    // SENDER BUTTON

                    Button(
                        onClick = {

                            val granted =
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) ==
                                        PackageManager.PERMISSION_GRANTED

                            if (granted) {

                                status =
                                    "Waiting for capture approval"

                                requestAudioCapture()

                            } else {

                                permissionLauncher.launch(
                                    Manifest.permission.RECORD_AUDIO
                                )
                            }
                        }
                    ) {

                        Text("Start Audio Capture")
                    }

                    Spacer(
                        modifier = Modifier.height(16.dp)
                    )

                    // RECEIVER BUTTON

                    Button(
                        onClick = {

                            status =
                                "Connecting to Phone 1..."

                            audioReceiver =
                                AudioReceiver()

                            audioReceiver?.start()

                            status =
                                "Receiver started"

                        }
                    ) {

                        Text("Start Receiver")
                    }
                }
            }
        }
    }

    override fun onDestroy() {

        audioReceiver?.stop()

        super.onDestroy()
    }
}