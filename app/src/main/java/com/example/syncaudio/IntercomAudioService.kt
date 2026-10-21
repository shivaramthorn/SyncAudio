package com.example.syncaudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioDeviceCallback
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.IBinder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.sqrt

class IntercomAudioService : Service() {

    companion object {
        const val ACTION_START =
            "com.example.syncaudio.intercom.START"
        const val ACTION_STOP =
            "com.example.syncaudio.intercom.STOP"
        const val ACTION_PLAY_FRAME =
            "com.example.syncaudio.intercom.PLAY_FRAME"
        const val ACTION_AUDIO_FRAME =
            "com.example.syncaudio.intercom.AUDIO_FRAME"
        const val EXTRA_AUDIO_BYTES = "audio_bytes"

        const val ACTION_SET_AUDIO_ROUTE = "com.example.syncaudio.intercom.SET_AUDIO_ROUTE"
        const val ACTION_AUDIO_ROUTES_CHANGED = "com.example.syncaudio.intercom.AUDIO_ROUTES_CHANGED"
        const val ACTION_AUDIO_ROUTE_CHANGED = "com.example.syncaudio.intercom.AUDIO_ROUTE_CHANGED"
        const val EXTRA_AUDIO_ROUTE_TYPE = "audio_route_type"
        const val EXTRA_AUDIO_ROUTE_ID = "audio_route_id"
        const val EXTRA_AUDIO_ROUTE_NAME = "audio_route_name"
        const val EXTRA_AUDIO_ROUTES = "audio_routes"
        const val EXTRA_SELECTED_ROUTE = "selected_audio_route"
        const val ROUTE_EARPIECE = "earpiece"
        const val ROUTE_SPEAKER = "speaker"
        const val ROUTE_BLUETOOTH = "bluetooth"

        private const val CHANNEL_ID = "syncaudio_intercom"
        private const val NOTIFICATION_ID = 2101
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280
        private const val TAG = "IntercomAudioService"
    }

    private val running = AtomicBoolean(false)
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()

    private val capturedFrames = AtomicInteger(0)
    private val receivedFrames = AtomicInteger(0)
    private val playedFrames = AtomicInteger(0)

    private val audioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }

    @Volatile
    private var recorder: AudioRecord? = null

    @Volatile
    private var player: AudioTrack? = null

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphone = false
    private var routeConfigured = false

    @Volatile
    private var selectedRoute = ROUTE_SPEAKER

    @Volatile
    private var selectedRouteId = -1

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (running.get()) broadcastAvailableRoutes()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (running.get()) {
                // If the selected Bluetooth device vanished, return to speaker.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    selectedRoute == ROUTE_BLUETOOTH &&
                    audioManager.availableCommunicationDevices.none {
                        it.id == selectedRouteId && isBluetoothCommunicationDevice(it)
                    }
                ) {
                    selectAudioRoute(ROUTE_SPEAKER, -1, null)
                }
                broadcastAvailableRoutes()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        audioManager.registerAudioDeviceCallback(
            audioDeviceCallback,
            Handler(Looper.getMainLooper())
        )
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_START -> startAudio()

            ACTION_STOP -> {
                stopAudio()
                return START_NOT_STICKY
            }

            ACTION_PLAY_FRAME -> {
                val bytes = intent.getByteArrayExtra(EXTRA_AUDIO_BYTES)
                if (bytes != null && running.get()) {
                    if (bytes.size != FRAME_SIZE) {
                        Log.w(TAG, "Unexpected playback frame size: ${bytes.size}")
                    } else {
                        if (playbackQueue.size >= 10) playbackQueue.poll()
                        playbackQueue.offer(bytes)
                        val count = receivedFrames.incrementAndGet()
                        if (count % 25 == 0) {
                            val metrics = measurePcm16(bytes)
                            Log.d(TAG, "PLAY_FRAME_RECEIVED: frames=$count, bytes=${bytes.size}, rms=${metrics.first}, peak=${metrics.second}, queue=${playbackQueue.size}")
                        }
                    }
                }
            }

            ACTION_SET_AUDIO_ROUTE -> {
                val type = intent.getStringExtra(EXTRA_AUDIO_ROUTE_TYPE)
                val id = intent.getIntExtra(EXTRA_AUDIO_ROUTE_ID, -1)
                val name = intent.getStringExtra(EXTRA_AUDIO_ROUTE_NAME)
                if (type != null) selectAudioRoute(type, id, name)
            }
        }

        return START_NOT_STICKY
    }

    private fun startAudio() {
        if (!running.compareAndSet(false, true)) {
            return
        }

        try {
            startForegroundCompat()
            configureCommunicationAudio()

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
                "Unsupported audio configuration: " +
                        "record=$minRecord, play=$minPlay"
            }

            val newRecorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minRecord, FRAME_SIZE * 8)
            )

            check(
                newRecorder.state == AudioRecord.STATE_INITIALIZED
            ) {
                newRecorder.release()
                "Microphone could not be initialized"
            }

            val newPlayer = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(
                            AudioAttributes.USAGE_VOICE_COMMUNICATION
                        )
                        .setContentType(
                            AudioAttributes.CONTENT_TYPE_SPEECH
                        )
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(
                            AudioFormat.CHANNEL_OUT_MONO
                        )
                        .setEncoding(
                            AudioFormat.ENCODING_PCM_16BIT
                        )
                        .build()
                )
                .setBufferSizeInBytes(
                    maxOf(minPlay, FRAME_SIZE * 8)
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            check(newPlayer.state == AudioTrack.STATE_INITIALIZED) {
                newRecorder.release()
                newPlayer.release()
                "Audio playback could not be initialized"
            }

            // Explicit playback volume; this is not a property read.
            newPlayer.setVolume(1.0f)

            recorder = newRecorder
            player = newPlayer
            playbackQueue.clear()

            capturedFrames.set(0)
            receivedFrames.set(0)
            playedFrames.set(0)

            newPlayer.play()
            newRecorder.startRecording()

            playbackThread = thread(
                name = "IntercomPlayback",
                isDaemon = true
            ) {
                playbackLoop(newPlayer)
            }

            captureThread = thread(
                name = "IntercomCapture",
                isDaemon = true
            ) {
                captureLoop(newRecorder)
            }

            Log.i(TAG, "Intercom audio started: sampleRate=$SAMPLE_RATE, frameSize=$FRAME_SIZE, audioMode=${audioManager.mode}")
            broadcastAvailableRoutes()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start audio", error)
            running.set(false)
            cleanupAudio()
            restoreCommunicationAudio()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun configureCommunicationAudio() {
        previousAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        run { previousSpeakerphone = audioManager.isSpeakerphoneOn }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        routeConfigured = true
        // Keep the existing default behavior: start on loudspeaker.
        selectAudioRoute(ROUTE_SPEAKER, -1, null)
    }

    private fun selectAudioRoute(type: String, requestedId: Int, requestedName: String?) {
        if (!routeConfigured && !running.get()) {
            Log.w(TAG, "Ignoring route request because intercom audio is not running")
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val available = audioManager.availableCommunicationDevices
                val target = when (type) {
                    ROUTE_EARPIECE -> available.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }
                    ROUTE_SPEAKER -> available.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    ROUTE_BLUETOOTH -> available.firstOrNull {
                        it.id == requestedId && isBluetoothCommunicationDevice(it) &&
                                (requestedName.isNullOrBlank() || it.productName.toString() == requestedName)
                    } ?: available.firstOrNull { isBluetoothCommunicationDevice(it) }
                    else -> null
                }

                if (target == null) {
                    Log.w(TAG, "Requested audio route unavailable: type=$type id=$requestedId")
                    broadcastAvailableRoutes()
                    return
                }

                if (audioManager.setCommunicationDevice(target)) {
                    selectedRoute = type
                    selectedRouteId = target.id
                    Log.i(TAG, "Selected route: ${target.productName}, type=${target.type}, id=${target.id}")
                    broadcastSelectedRoute(target)
                } else {
                    Log.w(TAG, "Android rejected audio route: ${target.productName}")
                }
            } else {
                @Suppress("DEPRECATION")
                when (type) {
                    ROUTE_EARPIECE -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = false
                        selectedRoute = ROUTE_EARPIECE
                        selectedRouteId = -1
                    }
                    ROUTE_SPEAKER -> {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                        audioManager.isSpeakerphoneOn = true
                        selectedRoute = ROUTE_SPEAKER
                        selectedRouteId = -1
                    }
                    ROUTE_BLUETOOTH -> {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        selectedRoute = ROUTE_BLUETOOTH
                        selectedRouteId = requestedId
                    }
                }
                broadcastSelectedRoute(null)
            }
            broadcastAvailableRoutes()
        } catch (error: SecurityException) {
            Log.e(TAG, "Permission denied while changing audio route", error)
        } catch (error: Exception) {
            Log.e(TAG, "Could not change audio route", error)
        }
    }

    private fun isBluetoothCommunicationDevice(device: AudioDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
    }

    private fun availableRouteStrings(): Array<String> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return audioManager.availableCommunicationDevices.mapNotNull { device ->
                val type = when {
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> ROUTE_EARPIECE
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> ROUTE_SPEAKER
                    isBluetoothCommunicationDevice(device) -> ROUTE_BLUETOOTH
                    else -> null
                } ?: return@mapNotNull null

                val name = when (type) {
                    ROUTE_EARPIECE -> "Phone earpiece"
                    ROUTE_SPEAKER -> "Phone loudspeaker"
                    else -> device.productName.toString().ifBlank { "Bluetooth audio" }
                }
                "${device.id}|$type|$name"
            }.toTypedArray()
        }

        @Suppress("DEPRECATION")
        val bluetoothConnected = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

        return buildList {
            add("-1|$ROUTE_EARPIECE|Phone earpiece")
            add("-2|$ROUTE_SPEAKER|Phone loudspeaker")
            if (bluetoothConnected) add("-3|$ROUTE_BLUETOOTH|Bluetooth headset")
        }.toTypedArray()
    }

    private fun broadcastAvailableRoutes() {
        sendBroadcast(
            Intent(ACTION_AUDIO_ROUTES_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_AUDIO_ROUTES, availableRouteStrings())
                .putExtra(EXTRA_SELECTED_ROUTE, selectedRoute)
                .putExtra(EXTRA_AUDIO_ROUTE_ID, selectedRouteId)
        )
    }

    private fun broadcastSelectedRoute(device: AudioDeviceInfo?) {
        val name = when (selectedRoute) {
            ROUTE_EARPIECE -> "Phone earpiece"
            ROUTE_SPEAKER -> "Phone loudspeaker"
            else -> device?.productName?.toString() ?: "Bluetooth audio"
        }
        sendBroadcast(
            Intent(ACTION_AUDIO_ROUTE_CHANGED)
                .setPackage(packageName)
                .putExtra(EXTRA_AUDIO_ROUTE_TYPE, selectedRoute)
                .putExtra(EXTRA_AUDIO_ROUTE_ID, selectedRouteId)
                .putExtra(EXTRA_AUDIO_ROUTE_NAME, name)
        )
    }

    private fun restoreCommunicationAudio() {
        if (!routeConfigured) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                run { audioManager.isSpeakerphoneOn = previousSpeakerphone }
            }
            audioManager.mode = previousAudioMode
        } catch (error: Exception) {
            Log.w(TAG, "Could not restore audio route", error)
        }
        routeConfigured = false
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val frame = ByteArray(FRAME_SIZE)

        while (
            running.get() &&
            !Thread.currentThread().isInterrupted
        ) {
            val count = try {
                audioRecord.read(
                    frame,
                    0,
                    frame.size,
                    AudioRecord.READ_BLOCKING
                )
            } catch (error: Exception) {
                Log.e(TAG, "Microphone read failed", error)
                break
            }

            if (count == frame.size && running.get()) {
                val output = frame.copyOf()
                val total = capturedFrames.incrementAndGet()

                if (total % 25 == 0) {
                    val (rms, peak) = measurePcm16(output)

                    Log.d(
                        TAG,
                        "MIC_CAPTURED: frames=$total, " +
                                "bytes=${output.size}, " +
                                "rms=$rms, peak=$peak"
                    )
                }

                sendBroadcast(
                    Intent(ACTION_AUDIO_FRAME)
                        .setPackage(packageName)
                        .putExtra(EXTRA_AUDIO_BYTES, output)
                )
            } else if (count < 0) {
                Log.e(TAG, "Microphone read error: $count")
                break
            }
        }

        if (running.get()) {
            stopAudio()
        }
    }

    private fun playbackLoop(audioTrack: AudioTrack) {
        while (
            running.get() &&
            !Thread.currentThread().isInterrupted
        ) {
            val frame = try {
                playbackQueue.take()
            } catch (_: InterruptedException) {
                break
            }

            var offset = 0

            while (
                running.get() &&
                offset < frame.size
            ) {
                val written = try {
                    audioTrack.write(
                        frame,
                        offset,
                        frame.size - offset,
                        AudioTrack.WRITE_BLOCKING
                    )
                } catch (error: Exception) {
                    Log.e(TAG, "Playback write failed", error)
                    break
                }

                if (written < 0) {
                    Log.e(
                        TAG,
                        "AudioTrack write error: $written"
                    )
                    break
                }

                if (written == 0) {
                    Log.w(TAG, "AudioTrack wrote zero bytes")
                    break
                }

                offset += written
            }

            if (offset == frame.size) {
                val total = playedFrames.incrementAndGet()

                if (total % 25 == 0) {
                    val (rms, peak) = measurePcm16(frame)

                    Log.d(
                        TAG,
                        "AUDIO_PLAYED: frames=$total, " +
                                "bytes=${frame.size}, " +
                                "rms=$rms, peak=$peak, " +
                                "queue=${playbackQueue.size}, " +
                                "playState=${audioTrack.playState}"
                    )
                }
            } else if (running.get()) {
                Log.w(
                    TAG,
                    "Incomplete playback write: " +
                            "$offset/${frame.size}"
                )
            }
        }
    }

    private fun measurePcm16(bytes: ByteArray): Pair<Int, Int> {
        if (bytes.size < 2) return Pair(0, 0)

        var sumSquares = 0.0
        var peak = 0
        var samples = 0
        var index = 0

        while (index + 1 < bytes.size) {
            val low = bytes[index].toInt() and 0xFF
            val high = bytes[index + 1].toInt()
            val sample = ((high shl 8) or low).toShort().toInt()

            val absolute = kotlin.math.abs(sample)
            if (absolute > peak) peak = absolute

            sumSquares += sample.toDouble() * sample.toDouble()
            samples++
            index += 2
        }

        val rms = if (samples > 0) {
            sqrt(sumSquares / samples).toInt()
        } else {
            0
        }

        return Pair(rms, peak)
    }

    private fun stopAudio() {
        running.set(false)
        cleanupAudio()
        restoreCommunicationAudio()
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
                if (
                    it.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    it.stop()
                }
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
                description =
                    "Active hands-free intercom session"
            }

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (error: Exception) {
            Log.w(TAG, "Could not unregister audio device callback", error)
        }
        cleanupAudio()
        restoreCommunicationAudio()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
