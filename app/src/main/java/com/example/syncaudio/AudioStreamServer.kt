
package com.example.syncaudio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AudioStreamServer(
    private val context: Context,
    private val port: Int = 50005
) {
    companion object {
        private const val TAG = "SyncAudio"
        private const val SERVICE_TYPE = "_syncaudio._tcp."
    }

    private data class ClientConnection(
        val socket: Socket,
        val output: BufferedOutputStream
    )

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<ClientConnection>()

    @Volatile
    private var running = false

    private val nsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private var registrationListener:
            NsdManager.RegistrationListener? = null

    fun start() {
        if (running) return
        running = true

        thread(name = "AudioStreamServer") {
            try {
                val server = ServerSocket(port)
                serverSocket = server

                Log.d(TAG, "Server started on port ${server.localPort}")
                registerService(server.localPort)

                while (running) {
                    val socket = server.accept()
                    socket.tcpNoDelay = true

                    val client = ClientConnection(
                        socket,
                        BufferedOutputStream(
                            socket.getOutputStream(),
                            8192
                        )
                    )

                    clients.add(client)

                    Log.d(
                        TAG,
                        "Receiver connected: ${socket.inetAddress.hostAddress}; " +
                                "receivers=${clients.size}"
                    )

                    // Detect a receiver disconnecting.
                    thread(name = "AudioClientMonitor") {
                        try {
                            val input = socket.getInputStream()
                            val discard = ByteArray(256)

                            while (running && !socket.isClosed) {
                                val count = input.read(discard)
                                if (count < 0) break
                            }
                        } catch (_: Exception) {
                        } finally {
                            removeClient(client)
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) {
                    Log.e(TAG, "Server error: ${e.message}", e)
                }
            }
        }
    }

    private fun registerService(actualPort: Int) {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "SyncAudio-${Build.MODEL}"
                serviceType = SERVICE_TYPE
                port = actualPort
            }

            val listener =
                object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(
                        serviceInfo: NsdServiceInfo
                    ) {
                        Log.d(TAG, "Discoverable as ${serviceInfo.serviceName}")
                    }

                    override fun onRegistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int
                    ) {
                        Log.e(TAG, "NSD registration failed: $errorCode")
                    }

                    override fun onServiceUnregistered(
                        serviceInfo: NsdServiceInfo
                    ) {
                        Log.d(TAG, "NSD service unregistered")
                    }

                    override fun onUnregistrationFailed(
                        serviceInfo: NsdServiceInfo,
                        errorCode: Int
                    ) {
                        Log.e(TAG, "NSD unregistration failed: $errorCode")
                    }
                }

            registrationListener = listener

            nsdManager.registerService(
                info,
                NsdManager.PROTOCOL_DNS_SD,
                listener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not register local service: ${e.message}", e)
        }
    }

    fun sendAudio(buffer: ByteArray, length: Int) {
        if (!running || length <= 0) return

        for (client in clients) {
            try {
                synchronized(client) {
                    client.output.write(buffer, 0, length)
                    client.output.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Send failed: ${e.message}")
                removeClient(client)
            }
        }
    }

    private fun removeClient(client: ClientConnection) {
        if (clients.remove(client)) {
            try {
                client.socket.close()
            } catch (_: Exception) {
            }

            Log.d(TAG, "Receiver disconnected; remaining=${clients.size}")
        }
    }

    fun stop() {
        running = false

        try {
            registrationListener?.let {
                nsdManager.unregisterService(it)
            }
        } catch (_: Exception) {
        }

        registrationListener = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        for (client in clients) {
            removeClient(client)
        }

        serverSocket = null
        Log.d(TAG, "Server stopped")
    }
}
