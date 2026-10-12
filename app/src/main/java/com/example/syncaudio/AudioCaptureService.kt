
package com.example.syncaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "SyncAudioCapture"
        private const val CHANNEL_ID = "syncaudio_capture"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.syncaudio.START_CAPTURE"
        const val ACTION_STOP = "com.example.syncaudio.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioServer: AudioStreamServer? = null

    @Volatile
    private var isCapturing = false

    private var captureThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {

            ACTION_STOP -> {
                stopCapture()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                // Enter foreground immediately.
                startForeground(
                    NOTIFICATION_ID,
                    createNotification()
                )

                Log.d(TAG, "Foreground service started")

                val resultCode =
                    intent.getIntExtra(EXTRA_RESULT_CODE, 0)

                val resultData: Intent? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            EXTRA_RESULT_DATA,
                            Intent::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(EXTRA_RESULT_DATA)
                    }

                if (resultData == null) {
                    Log.e(TAG, "MediaProjection permission data is missing")
                    stopSelf()
                    return START_NOT_STICKY
                }

                startCapture(resultCode, resultData)
            }

            else -> {
                Log.e(TAG, "Unknown service action: ${intent?.action}")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        return START_NOT_STICKY
    }

    private fun startCapture(
        resultCode: Int,
        resultData: Intent
    ) {
        if (isCapturing) {
            stopCapture()
        }

        try {
            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection =
                projectionManager.getMediaProjection(
                    resultCode,
                    resultData
                )

            val projection = mediaProjection
                ?: throw IllegalStateException(
                    "MediaProjection could not be created"
                )

            val config =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

            val format =
                AudioFormat.Builder()
                    .setEncoding(AUDIO_ENCODING)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

            val minBuffer =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_ENCODING
                )

            if (minBuffer <= 0) {
                throw IllegalStateException(
                    "Invalid AudioRecord buffer size: $minBuffer"
                )
            }

            val configuredBufferBytes = minBuffer * 2

            val bufferDurationMs =
                configuredBufferBytes * 1000.0 /
                        (SAMPLE_RATE * 2 * 2)

            Log.d(
                TAG,
                "V1.3 Capture diagnostics: " +
                        "minBuffer=$minBuffer bytes, " +
                        "configuredBuffer=${configuredBufferBytes} bytes, " +
                        "approxBufferDuration=${"%.1f".format(bufferDurationMs)} ms"
            )

            val recorder =
                AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(configuredBufferBytes)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException(
                    "AudioRecord initialization failed"
                )
            }

            audioRecord = recorder

            recorder.startRecording()
            isCapturing = true

            audioServer = AudioStreamServer(applicationContext)
            audioServer?.start()

            startCaptureThread(recorder)

            Log.d(TAG, "Audio capture started")

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied while starting capture", e)
            stopCapture()
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio capture", e)
            stopCapture()
            stopSelf()
        }
    }

    private fun startCaptureThread(recorder: AudioRecord) {
        val minBuffer =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_ENCODING
            )

        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid capture thread buffer size: $minBuffer")
            stopCapture()
            stopSelf()
            return
        }

        captureThread = Thread({
            val buffer = ByteArray(minBuffer * 2)

            var totalBytesSent = 0L
            var totalSendTimeNs = 0L
            var sendCalls = 0L
            var diagnosticStartNs =
                SystemClock.elapsedRealtimeNanos()

            Log.d(TAG, "V1.3 sender diagnostics started")

            while (isCapturing) {
                val bytesRead =
                    recorder.read(buffer, 0, buffer.size)

                if (bytesRead > 0) {
                    val sendStartNs =
                        SystemClock.elapsedRealtimeNanos()

                    try {
                        audioServer?.sendAudio(buffer, bytesRead)
                    } catch (e: Exception) {
                        Log.e(TAG, "Audio streaming failed", e)
                        break
                    }

                    val sendDurationNs =
                        SystemClock.elapsedRealtimeNanos() - sendStartNs

                    totalBytesSent += bytesRead
                    totalSendTimeNs += sendDurationNs
                    sendCalls++

                    val nowNs =
                        SystemClock.elapsedRealtimeNanos()

                    if (nowNs - diagnosticStartNs >= 5_000_000_000L) {
                        val elapsedSeconds =
                            (nowNs - diagnosticStartNs) / 1_000_000_000.0

                        val averageSendMs =
                            if (sendCalls > 0) {
                                totalSendTimeNs / 1_000_000.0 / sendCalls
                            } else {
                                0.0
                            }

                        val throughputKbps =
                            totalBytesSent * 8.0 / elapsedSeconds / 1000.0

                        Log.d(
                            TAG,
                            "V1.3 sender: " +
                                    "throughput=${"%.1f".format(throughputKbps)} kbps, " +
                                    "avgSend=${"%.3f".format(averageSendMs)} ms, " +
                                    "sendCalls=$sendCalls"
                        )

                        totalBytesSent = 0L
                        totalSendTimeNs = 0L
                        sendCalls = 0L
                        diagnosticStartNs = nowNs
                    }
                } else if (bytesRead < 0) {
                    if (isCapturing) {
                        Log.e(TAG, "Audio read error: $bytesRead")
                    }
                    break
                }
            }

            Log.d(TAG, "Audio capture thread ended")
        }, "SyncAudio-Capture")

        captureThread?.start()
    }

    private fun stopCapture() {
        isCapturing = false

        try {
            audioServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio server", e)
        }
        audioServer = null

        try {
            audioRecord?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
                recorder.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord", e)
        }
        audioRecord = null

        try {
            captureThread?.let { thread ->
                if (thread !== Thread.currentThread() && thread.isAlive) {
                    thread.join(500)
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null

        try {
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaProjection", e)
        }
        mediaProjection = null

        Log.d(TAG, "Audio capture stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SyncAudio capture",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setContentTitle("SyncAudio")
            .setContentText("Capturing and streaming audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
