package com.example.syncaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class IntercomAudioService : Service() {
    companion object {
        const val ACTION_START = "com.example.syncaudio.intercom.START"
        const val ACTION_STOP = "com.example.syncaudio.intercom.STOP"
        const val ACTION_PLAY_FRAME = "com.example.syncaudio.intercom.PLAY_FRAME"
        const val ACTION_AUDIO_FRAME = "com.example.syncaudio.intercom.AUDIO_FRAME"
        const val EXTRA_AUDIO_BYTES = "audio_bytes"

        private const val CHANNEL_ID = "syncaudio_intercom"
        private const val NOTIFICATION_ID = 2101
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280 // 40 ms, mono, PCM 16-bit
        private const val TAG = "IntercomAudioService"
    }

    private val running = AtomicBoolean(false)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var player: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAudio()
            ACTION_STOP -> {
                stopAudio()
                return START_NOT_STICKY
            }
            ACTION_PLAY_FRAME -> {
                val bytes = intent.getByteArrayExtra(EXTRA_AUDIO_BYTES)
                if (bytes != null && running.get()) {
                    // Drop old frames if playback falls behind.
                    if (playbackQueue.size >= 10) playbackQueue.poll()
                    playbackQueue.offer(bytes)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startAudio() {
        if (running.get()) return

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }

            val minRecord = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val minPlay = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minRecord > 0 && minPlay > 0) {
                "Unsupported audio configuration"
            }

            val newRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRecord, FRAME_SIZE * 4)
            )
            check(newRecorder.state == AudioRecord.STATE_INITIALIZED) {
                newRecorder.release()
                "Microphone could not be initialized"
            }

            val newPlayer = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minPlay, FRAME_SIZE * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            check(newPlayer.state == AudioTrack.STATE_INITIALIZED) {
                newRecorder.release()
                newPlayer.release()
                "Audio playback could not be initialized"
            }

            recorder = newRecorder
            player = newPlayer
            playbackQueue.clear()
            running.set(true)
            newPlayer.play()
            newRecorder.startRecording()

            playbackThread = thread(name = "IntercomPlayback", isDaemon = true) {
                playbackLoop(newPlayer)
            }
            captureThread = thread(name = "IntercomCapture", isDaemon = true) {
                captureLoop(newRecorder)
            }
            Log.i(TAG, "Intercom audio started")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start audio", error)
            cleanupAudio()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val frame = ByteArray(FRAME_SIZE)
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val count = try {
                audioRecord.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
            } catch (error: Exception) {
                Log.e(TAG, "Microphone read failed", error)
                break
            }

            if (count == frame.size && running.get()) {
                sendBroadcast(
                    Intent(ACTION_AUDIO_FRAME)
                        .setPackage(packageName)
                        .putExtra(EXTRA_AUDIO_BYTES, frame.copyOf())
                )
            } else if (count < 0) {
                Log.e(TAG, "Microphone read error: $count")
                break
            }
        }
        if (running.get()) stopAudio()
    }

    private fun playbackLoop(audioTrack: AudioTrack) {
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = try {
                playbackQueue.take()
            } catch (_: InterruptedException) {
                break
            }
            try {
                audioTrack.write(frame, 0, frame.size, AudioTrack.WRITE_BLOCKING)
            } catch (error: Exception) {
                Log.e(TAG, "Playback write failed", error)
            }
        }
    }

    private fun stopAudio() {
        running.set(false)
        cleanupAudio()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Intercom audio stopped")
    }

    private fun cleanupAudio() {
        captureThread?.interrupt()
        playbackThread?.interrupt()
        captureThread = null
        playbackThread = null

        try {
            recorder?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                it.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Recorder cleanup issue", error)
        }
        try {
            player?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.pause()
                    it.flush()
                }
                it.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Player cleanup issue", error)
        }
        recorder = null
        player = null
        playbackQueue.clear()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SyncAudio Intercom",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active hands-free intercom session"
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SyncAudio Intercom")
            .setContentText("Hands-free audio is active")
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        running.set(false)
        cleanupAudio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
