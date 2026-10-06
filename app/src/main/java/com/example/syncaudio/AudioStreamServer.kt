package com.example.syncaudio

import java.io.BufferedOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import android.util.Log


class AudioStreamServer(
    private val port: Int = 50005
) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: BufferedOutputStream? = null

    @Volatile
    private var running = false

    @Volatile
    private var clientConnected = false

    fun start() {
        if (running) return

        running = true

        thread(name = "AudioStreamServer") {
            try {
                serverSocket = ServerSocket(port)

                println("SyncAudio: Server started on port $port")

                clientSocket = serverSocket!!.accept()

                clientSocket!!.tcpNoDelay = true

                println(
                    "SyncAudio: Client connected: " +
                            clientSocket!!.inetAddress.hostAddress
                )

                outputStream = BufferedOutputStream(
                    clientSocket!!.getOutputStream(),
                    8192
                )

                clientConnected = true

                println("SyncAudio: Ready to send audio")

            } catch (e: Exception) {

                if (running) {
                    println(
                        "SyncAudio: Server error: ${e.message}"
                    )
                }
            }
        }
    }

    fun sendAudio(
        buffer: ByteArray,
        length: Int
    ) {
        if (!running || !clientConnected) {
            return
        }

        try {
            outputStream?.write(buffer, 0, length)
            outputStream?.flush()

        } catch (e: Exception) {

            println(
                "SyncAudio: Send error: ${e.message}"
            )

            clientConnected = false
            stop()
        }
    }

    fun stop() {

        running = false
        clientConnected = false

        try {
            outputStream?.close()
        } catch (_: Exception) {
        }

        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }

        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }

        outputStream = null
        clientSocket = null
        serverSocket = null

        println("SyncAudio: Server stopped")
    }
}