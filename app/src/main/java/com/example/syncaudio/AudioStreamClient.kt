package com.example.syncaudio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import java.io.BufferedInputStream
import java.net.Socket

class AudioStreamClient {

    private var socket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var audioTrack: AudioTrack? = null

    @Volatile
    private var running = false

    fun connect(
        host: String,
        port: Int = 50005
    ) {

        if (running) return

        thread(name = "AudioStreamClient") {

            try {

                println(
                    "SyncAudio: Connecting to $host:$port"
                )

                socket = Socket(host, port)

                inputStream = BufferedInputStream(
                    socket!!.getInputStream(),
                    8192
                )

                val sampleRate = 48000

                val channelConfig =
                    AudioFormat.CHANNEL_OUT_STEREO

                val encoding =
                    AudioFormat.ENCODING_PCM_16BIT

                val minBuffer =
                    AudioTrack.getMinBufferSize(
                        sampleRate,
                        channelConfig,
                        encoding
                    )

                audioTrack =
                    AudioTrack.Builder()
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
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelConfig)
                                .setEncoding(encoding)
                                .build()
                        )
                        .setBufferSizeInBytes(
                            minBuffer * 2
                        )
                        .setTransferMode(
                            AudioTrack.MODE_STREAM
                        )
                        .build()

                audioTrack?.play()

                running = true

                val buffer = ByteArray(8192)

                println(
                    "SyncAudio: Receiver started"
                )

                while (running) {

                    val bytesRead =
                        inputStream!!.read(buffer)

                    if (bytesRead == -1) {
                        break
                    }

                    if (bytesRead > 0) {

                        audioTrack?.write(
                            buffer,
                            0,
                            bytesRead
                        )
                    }
                }

            } catch (e: Exception) {

                println(
                    "SyncAudio: Client error: ${e.message}"
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

        audioTrack?.release()

        audioTrack = null
        inputStream = null
        socket = null

        println(
            "SyncAudio: Receiver stopped"
        )
    }
}