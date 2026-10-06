package com.example.syncaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.io.BufferedInputStream
import java.net.Socket
import kotlin.concurrent.thread

class AudioReceiver {

    companion object {
        private const val TAG = "SyncAudioReceiver"

        private const val SERVER_IP = "192.168.0.8"
        private const val SERVER_PORT = 50005

        private const val SAMPLE_RATE = 48000

        private const val CHANNEL_CONFIG =
            AudioFormat.CHANNEL_OUT_STEREO

        private const val AUDIO_ENCODING =
            AudioFormat.ENCODING_PCM_16BIT
    }

    private var socket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var running = false

    fun start() {

        if (running) return

        running = true

        thread(name = "AudioReceiver") {

            try {

                Log.d(TAG, "Connecting to $SERVER_IP:$SERVER_PORT")

                socket = Socket(
                    SERVER_IP,
                    SERVER_PORT
                )

                socket?.tcpNoDelay = true

                Log.d(TAG, "Connected to sender")

                inputStream = BufferedInputStream(
                    socket!!.getInputStream(),
                    8192
                )

                val minBufferSize =
                    AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_ENCODING
                    )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(
                                AudioAttributes.USAGE_MEDIA
                            )
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build()
                    )
                    .setBufferSizeInBytes(
                        minBufferSize * 2
                    )
                    .setTransferMode(
                        AudioTrack.MODE_STREAM
                    )
                    .build()

                audioTrack?.play()

                Log.d(TAG, "AudioTrack started")

                val buffer = ByteArray(8192)

                while (running) {

                    val bytesRead =
                        inputStream?.read(buffer) ?: -1

                    if (bytesRead <= 0) {
                        Log.d(TAG, "Connection closed")
                        break
                    }

                    audioTrack?.write(
                        buffer,
                        0,
                        bytesRead
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Receiver error: ${e.message}",
                    e
                )

            } finally {

                stop()
            }
        }
    }

    fun stop() {

        running = false

        try {
            inputStream?.close()
        } catch (_: Exception) {
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }

        inputStream = null
        socket = null
        audioTrack = null

        Log.d(TAG, "Receiver stopped")
    }
}