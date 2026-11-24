package com.example.bluetooth

import com.squareup.moshi.JsonClass

enum class RadioHardwareState {
    UNAVAILABLE,
    DISABLED,
    PERMISSIONS_REQUIRED,
    READY
}

data class MeshPeer(
    val peerId: String,
    val nickname: String,
    val publicKeyBase64: String,
    val bleAddress: String? = null,
    val rssi: Int? = null,
    val hopCount: Int = 0,
    val isDirectNeighbor: Boolean = false,
    val lastSeenAt: Long = System.currentTimeMillis()
)

data class MeshRadioTelemetry(
    val hardwareState: RadioHardwareState = RadioHardwareState.UNAVAILABLE,
    val isAdvertising: Boolean = false,
    val isScanning: Boolean = false,
    val directNeighborCount: Int = 0,
    val knownMeshPeerCount: Int = 0,
    val packetsRelayed: Int = 0,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val statusDetail: String = "Initializing BLE mesh..."
)

@JsonClass(generateAdapter = true)
data class MeshPacket(
    val id: String,
    val type: String, // ANNOUNCE, PUBLIC, CHANNEL, DM_ENC, PING, LEAVE
    val senderId: String,
    val senderNick: String,
    val senderPubKey: String? = null,
    val targetId: String? = null,
    val channel: String = "#mesh",
    val payload: String = "",
    val ttl: Int = 5,
    val hopCount: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)
