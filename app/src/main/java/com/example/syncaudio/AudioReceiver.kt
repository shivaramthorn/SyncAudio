package com.example.syncaudio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AudioReceiver(
    context: Context,
    private val onStatus: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SyncAudio"
        private const val SERVICE_TYPE = "_syncaudio._tcp."
        private const val SAMPLE_RATE = 48000

        // Increase receiver loudness moderately.
        // Samples are clamped to prevent integer overflow.
        private const val AUDIO_GAIN = 1.5f

        // Aim for a practical buffer of around 100 ms or more
        // if the device's minimum buffer requires it.
        private const val TARGET_BUFFER_MS = 100
    }

    private val appContext = context.applicationContext

    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    @Volatile
    private var running = false

    @Volatile
    private var connecting = false

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var socket: Socket? = null
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    private val isPlaying = AtomicBoolean(false)

    fun start() {
        if (running) return

        running = true
        connecting = false

        report("Searching for SyncAudio sender…")

        val listener = object : NsdManager.DiscoveryListener {

            override fun onDiscoveryStarted(serviceType: String) {
                report("Searching for sender on this network…")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!running || connecting) return

                if (!serviceInfo.serviceType.contains("_syncaudio._tcp")) {
                    return
                }

                connecting = true
                report("Sender found. Resolving address…")

                try {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {

                            override fun onResolveFailed(
                                serviceInfo: NsdServiceInfo,
                                errorCode: Int
                            ) {
                                Log.e(TAG, "Resolve failed: $errorCode")
                                connecting = false

                                if (running) {
                                    report("Could not resolve sender. Restart receiver to retry.")
                                }
                            }

                            override fun onServiceResolved(
                                resolved: NsdServiceInfo
                            ) {
                                val host = resolved.host?.hostAddress
                                val port = resolved.port

                                if (!running || host.isNullOrBlank() || port <= 0) {
                                    connecting = false
                                    report("Invalid sender address")
                                    return
                                }

                                report("Sender discovered: $host:$port")
                                stopDiscovery()
                                connect(host, port)
                            }
                        }
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Service resolution error", e)
                    connecting = false
                    report("Discovery error: ${e.message}")
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Sender lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "Discovery stopped")
            }

            override fun onStartDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
                Log.e(TAG, "Discovery start failed: $errorCode")
                stopDiscovery()
                running = false
                report("Discovery failed ($errorCode)")
            }

            override fun onStopDiscoveryFailed(
                serviceType: String,
                errorCode: Int
            ) {
                Log.e(TAG, "Discovery stop failed: $errorCode")
            }
        }

        discoveryListener = listener

        try {
            nsdManager.discoverServices(
                SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not start discovery", e)
            running = false
            report("Could not start discovery: ${e.message}")
        }
    }

    private fun connect(host: String, port: Int) {
        playbackThread = thread(name = "SyncAudioReceiver") {
            var track: AudioTrack? = null
            var connectedSocket: Socket? = null

            try {
                report("Connecting to sender…")

                val newSocket = Socket()
                connectedSocket = newSocket
                socket = newSocket

                newSocket.tcpNoDelay = true
                newSocket.connect(
                    InetSocketAddress(host, port),
                    8000
                )

                if (!running) return@thread

                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw IllegalStateException(
                        "Invalid AudioTrack buffer size: $minBuffer"
                    )
                }

                // Stereo PCM16 = 4 bytes per audio frame.
                val targetBufferBytes =
                    SAMPLE_RATE * 4 * TARGET_BUFFER_MS / 1000

                val playbackBufferBytes =
                    maxOf(minBuffer * 2, targetBufferBytes)

                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                AudioAttributes.CONTENT_TYPE_MUSIC
                            )
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(
                                AudioFormat.CHANNEL_OUT_STEREO
                            )
                            .setEncoding(
                                AudioFormat.ENCODING_PCM_16BIT
                            )
                            .build()
                    )
                    .setBufferSizeInBytes(playbackBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    builder.setPerformanceMode(
                        AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                    )
                }

                track = builder.build()

                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    throw IllegalStateException(
                        "AudioTrack initialization failed"
                    )
                }

                // Explicitly set normal Android playback volume.
                track.setVolume(1.0f)

                audioTrack = track
                track.play()

                isPlaying.set(true)
                report("Connected — receiving audio")

                val input = newSocket.getInputStream()
                Log.d(TAG, "V1.3 receiver diagnostics started")
                var diagnosticStartNs = android.os.SystemClock.elapsedRealtimeNanos()
                var diagnosticBytes = 0L
                var diagnosticWriteNs = 0L
                var diagnosticWriteCalls = 0L

                // Preserve partial PCM frames between TCP reads.
                // Stereo PCM16 frames must contain 4 bytes.
                val frameSize = 4
                val networkBuffer = ByteArray(8192)
                val processingBuffer = ByteArray(
                    networkBuffer.size + frameSize
                )

                var leftoverBytes = 0

                while (running) {
                    val count = input.read(
                        processingBuffer,
                        leftoverBytes,
                        networkBuffer.size
                    )

                    if (count < 0) break
                    if (count == 0) continue

                    val totalBytes = leftoverBytes + count
                    diagnosticBytes += count

                    val nowNs = android.os.SystemClock.elapsedRealtimeNanos()

                    if (nowNs - diagnosticStartNs >= 5_000_000_000L) {
                        val elapsedSeconds =
                            (nowNs - diagnosticStartNs) / 1_000_000_000.0

                        val throughputKbps =
                            diagnosticBytes * 8.0 / elapsedSeconds / 1000.0

                        val averageWriteMs =
                            if (diagnosticWriteCalls > 0) {
                                diagnosticWriteNs / 1_000_000.0 / diagnosticWriteCalls
                            } else {
                                0.0
                            }

                        Log.d(
                            TAG,
                            "V1.3 receiver: throughput=${"%.1f".format(throughputKbps)} kbps, " +
                                    "avgWrite=${"%.3f".format(averageWriteMs)} ms, " +
                                    "writeCalls=$diagnosticWriteCalls"
                        )

                        diagnosticStartNs = nowNs
                        diagnosticBytes = 0L
                        diagnosticWriteNs = 0L
                        diagnosticWriteCalls = 0L
                    }
                    val usableBytes =
                        totalBytes - (totalBytes % frameSize)

                    // Amplify each signed 16-bit little-endian sample.
                    // Clamp samples to the valid PCM16 range.
                    var index = 0

                    while (index < usableBytes) {
                        val low =
                            processingBuffer[index].toInt() and 0xFF

                        val high =
                            processingBuffer[index + 1].toInt()

                        val sample =
                            ((high shl 8) or low).toShort().toInt()

                        val amplified = (sample * AUDIO_GAIN)
                            .toInt()
                            .coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt()
                            )

                        processingBuffer[index] =
                            (amplified and 0xFF).toByte()

                        processingBuffer[index + 1] =
                            ((amplified shr 8) and 0xFF).toByte()

                        index += 2
                    }

                    // Write only complete frames to AudioTrack.
                    var writtenOffset = 0

                    while (running && writtenOffset < usableBytes) {
                        val writeStartNs =
                            android.os.SystemClock.elapsedRealtimeNanos()

                        val written = track.write(
                            processingBuffer,
                            writtenOffset,
                            usableBytes - writtenOffset,
                            AudioTrack.WRITE_BLOCKING
                        )

                        diagnosticWriteNs +=
                            android.os.SystemClock.elapsedRealtimeNanos() - writeStartNs
                        diagnosticWriteCalls++

                        if (written < 0) {
                            throw IllegalStateException(
                                "Audio playback error: $written"
                            )
                        }

                        if (written == 0) break

                        writtenOffset += written
                    }

                    // Keep the final 0–3 bytes for the next read.
                    leftoverBytes = totalBytes - usableBytes

                    if (leftoverBytes > 0) {
                        System.arraycopy(
                            processingBuffer,
                            usableBytes,
                            processingBuffer,
                            0,
                            leftoverBytes
                        )
                    }
                }

                if (running) {
                    report("Sender disconnected")
                }

            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Receiver error", e)
                    report("Receiver error: ${e.message}")
                }
            } finally {
                isPlaying.set(false)

                try {
                    track?.pause()
                    track?.flush()
                    track?.release()
                } catch (_: Exception) {
                }

                if (audioTrack === track) {
                    audioTrack = null
                }

                try {
                    connectedSocket?.close()
                } catch (_: Exception) {
                }

                if (socket === connectedSocket) {
                    socket = null
                }

                connecting = false
            }
        }
    }

    private fun stopDiscovery() {
        val listener = discoveryListener ?: return

        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.d(TAG, "Discovery already stopped: ${e.message}")
        }

        discoveryListener = null
    }

    private fun report(message: String) {
        Log.d(TAG, message)
        onStatus(message)
    }

    fun stop() {
        running = false
        connecting = false

        stopDiscovery()

        try {
            socket?.close()
        } catch (_: Exception) {
        }

        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {
        }

        report("Receiver stopped")
    }
}
