package com.example.syncaudio

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

data class IntercomDevice(
    val endpointId: String,
    val name: String
)

class NearbyIntercomManager(
    context: Context,
    private val onStatus: (String) -> Unit = {},
    private val onDevicesChanged: (List<IntercomDevice>) -> Unit = {},
    private val onConnectionsChanged: (List<String>) -> Unit = {},
    private val onAudioFrame: (String, ByteArray) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "SyncAudioIntercom"
        private const val SERVICE_ID = "com.example.syncaudio.intercom"
        private val AUDIO_MAGIC = byteArrayOf(0x53, 0x41, 0x55, 0x44)
    }

    private val client: ConnectionsClient =
        Nearby.getConnectionsClient(context.applicationContext)

    private val discoveredDevices = mutableMapOf<String, IntercomDevice>()
    private val connectedEndpoints = mutableSetOf<String>()

    private var isAdvertising = false
    private var isDiscovering = false

    private fun publishDevices() {
        onDevicesChanged(discoveredDevices.values.toList())
    }

    private fun publishConnections() {
        onConnectionsChanged(connectedEndpoints.toList())
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            when (payload.type) {
                Payload.Type.BYTES -> {
                    val bytes = payload.asBytes() ?: return
                    val isAudioFrame =
                        bytes.size > AUDIO_MAGIC.size &&
                                bytes.copyOfRange(0, AUDIO_MAGIC.size)
                                    .contentEquals(AUDIO_MAGIC)

                    if (isAudioFrame) {
                        val audioBytes = bytes.copyOfRange(
                            AUDIO_MAGIC.size,
                            bytes.size
                        )
                        onAudioFrame(endpointId, audioBytes)
                    } else {
                        val message = bytes.toString(Charsets.UTF_8)
                        Log.d(TAG, "Received data from $endpointId: $message")
                        onStatus("Data received from connected device")
                    }
                }

                Payload.Type.STREAM -> {
                    Log.d(TAG, "Stream payload received from $endpointId")
                }

                Payload.Type.FILE -> {
                    Log.d(TAG, "File payload received from $endpointId")
                }
            }
        }

        override fun onPayloadTransferUpdate(
            endpointId: String,
            update: PayloadTransferUpdate
        ) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS ->
                    Log.d(TAG, "Payload sent to $endpointId")

                PayloadTransferUpdate.Status.FAILURE -> {
                    Log.e(TAG, "Payload transfer failed: $endpointId")
                    onStatus("Data transmission failed")
                }

                PayloadTransferUpdate.Status.IN_PROGRESS -> Unit

                PayloadTransferUpdate.Status.CANCELED ->
                    Log.w(TAG, "Payload transfer cancelled: $endpointId")
            }
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(
            endpointId: String,
            info: DiscoveredEndpointInfo
        ) {
            discoveredDevices[endpointId] =
                IntercomDevice(endpointId, info.endpointName)
            Log.d(TAG, "Found device: ${info.endpointName}")
            publishDevices()
            onStatus("Found device: ${info.endpointName}")
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredDevices.remove(endpointId)
            publishDevices()
            onStatus("Device no longer visible")
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(
            endpointId: String,
            connectionInfo: ConnectionInfo
        ) {
            val deviceName = connectionInfo.endpointName
            discoveredDevices[endpointId] = IntercomDevice(endpointId, deviceName)
            publishDevices()
            onStatus("Connection request from $deviceName")
            client.acceptConnection(
                endpointId,
                payloadCallback
            ).addOnSuccessListener {
                Log.d(
                    TAG,
                    "ACCEPT_SUCCESS: accepted request from $deviceName ($endpointId)"
                )
                onStatus("Request accepted from $deviceName; completing connection...")
            }.addOnFailureListener { error ->
                Log.e(
                    TAG,
                    "ACCEPT_FAILED: could not accept request from $deviceName",
                    error
                )
                onStatus("Accept failed: ${error.message}")
            }
        }

        override fun onConnectionResult(
            endpointId: String,
            result: ConnectionResolution
        ) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                publishConnections()
                val deviceName = discoveredDevices[endpointId]?.name ?: "Nearby device"
                onStatus("Connected to $deviceName")
                Log.d(TAG, "Connected: $endpointId")
            } else {
                onStatus("Connection failed: ${result.status.statusCode}")
                Log.e(
                    TAG,
                    "CONNECTION_RESULT_FAILED: endpoint=$endpointId, " +
                            "statusCode=${result.status.statusCode}"
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            publishConnections()
            onStatus("Device disconnected")
            Log.d(TAG, "Disconnected: $endpointId")
        }
    }

    fun startAdvertising(deviceName: String = "SyncAudio Intercom") {
        if (isAdvertising) {
            onStatus("Already hosting an intercom room")
            return
        }
        isAdvertising = true
        onStatus("Starting intercom host...")
        client.startAdvertising(
            deviceName,
            SERVICE_ID,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        ).addOnSuccessListener {
            onStatus("Hosting intercom room: $deviceName")
        }.addOnFailureListener { error ->
            isAdvertising = false
            Log.e(TAG, "Advertising failed", error)
            onStatus("Advertising failed: ${error.message}")
        }
    }

    fun startDiscovery() {
        // Restart discovery so a previous search cannot block a new one.
        client.stopDiscovery()
        isDiscovering = false

        discoveredDevices.clear()
        publishDevices()

        isDiscovering = true
        onStatus("Searching for nearby intercom devices...")

        client.startDiscovery(
            SERVICE_ID,
            discoveryCallback,
            DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER)
                .build()
        ).addOnSuccessListener {
            onStatus("Search active — looking for SyncAudio hosts")
            Log.d(TAG, "Discovery started successfully")
        }.addOnFailureListener { error ->
            isDiscovering = false

            Log.e(TAG, "Discovery failed", error)
            onStatus("Discovery failed: ${error.message}")
        }
    }

    fun requestConnection(endpointId: String, deviceName: String = "SyncAudio") {
        onStatus("Requesting connection...")
        client.requestConnection(deviceName, endpointId, connectionCallback)
            .addOnFailureListener { error ->
                Log.e(TAG, "Connection request failed", error)
                onStatus("Connection request failed: ${error.message}")
            }
    }

    fun sendText(endpointId: String, message: String) {
        if (endpointId !in connectedEndpoints) {
            onStatus("Cannot send: device is not connected")
            return
        }
        client.sendPayload(
            endpointId,
            Payload.fromBytes(message.toByteArray(Charsets.UTF_8))
        ).addOnFailureListener { error ->
            Log.e(TAG, "Text payload send failed", error)
            onStatus("Data transmission failed: ${error.message}")
        }
    }

    fun sendAudioFrame(audioBytes: ByteArray) {
        if (connectedEndpoints.isEmpty()) return
        val packet = AUDIO_MAGIC + audioBytes
        connectedEndpoints.toList().forEach { endpointId ->
            client.sendPayload(endpointId, Payload.fromBytes(packet))
                .addOnFailureListener { error ->
                    Log.e(TAG, "Audio frame send failed", error)
                }
        }
    }

    fun getDiscoveredDevices(): List<IntercomDevice> =
        discoveredDevices.values.toList()

    fun getConnectedEndpoints(): List<String> = connectedEndpoints.toList()

    fun stopAll() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        isAdvertising = false
        isDiscovering = false
        discoveredDevices.clear()
        connectedEndpoints.clear()
        publishDevices()
        publishConnections()
        onStatus("Intercom stopped")
        Log.d(TAG, "Intercom stopped")
    }
}
