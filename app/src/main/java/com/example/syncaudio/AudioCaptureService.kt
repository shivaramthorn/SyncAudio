package com.example.syncaudio

import android.Manifest
import android.media.AudioAttributes
import androidx.annotation.RequiresPermission
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.media.AudioPlaybackCaptureConfiguration
import android.media.MediaRecorder
import android.content.Context
import android.content.pm.ServiceInfo
import kotlin.concurrent.thread

class AudioCaptureService : Service() {

    private val audioServer = AudioStreamServer(50005)
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile private var isCapturing = false

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        private const val CHANNEL_ID = "sync_audio_capture"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "SyncAudio"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SyncAudio")
            .setContentText("Capturing permitted app audio")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData =
            intent.getParcelableExtra<Intent>(EXTRA_DATA)

        if (resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            mediaProjection = manager.getMediaProjection(
                resultCode,
                resultData
            )

            mediaProjection?.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                        stopSelf()
                    }
                },
                android.os.Handler(mainLooper)
            )

            startAudioCapture()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start capture", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioCapture() {
        val projection = mediaProjection ?: return

        val config = AudioPlaybackCaptureConfiguration.Builder(
            projection
        )
            .addMatchingUsage(
                AudioAttributes.USAGE_MEDIA
            )
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(48000)
            .setChannelMask(
                AudioFormat.CHANNEL_IN_STEREO
            )
            .build()

        val minBuffer = AudioRecord.getMinBufferSize(
            48000,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBuffer <= 0) {
            Log.e(TAG, "Invalid audio buffer size")
            stopSelf()
            return
        }

        val bufferSize = minBuffer * 2

        val recorder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            recorder.release()
            stopSelf()
            return
        }

        audioRecord = recorder
        recorder.startRecording()
        isCapturing = true

        Log.d(TAG, "Starting audio server...")
        audioServer.start()


        captureThread = thread(
            start = true,
            name = "SyncAudioCapture"
        ) {
            val buffer = ByteArray(bufferSize)

            while (isCapturing) {
                val bytesRead = recorder.read(
                    buffer,
                    0,
                    buffer.size
                )
                if (bytesRead > 0) {

                    audioServer.sendAudio(
                        buffer,
                        bytesRead
                    )

                    Log.d(
                        TAG,
                        "Captured and sent $bytesRead bytes"
                    )
                } else if (bytesRead < 0) {
                    Log.e(TAG, "Audio read error: $bytesRead")
                    break
                }
            }
        }

        Log.d(TAG, "Audio capture started")
    }

    private fun stopCapture() {
        isCapturing = false

        audioServer.stop()

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        captureThread?.join(500)
        captureThread = null

        audioRecord?.release()
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SyncAudio Capture",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(
                NotificationManager::class.java
            )
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}