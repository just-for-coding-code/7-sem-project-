package com.example.data

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.bluetooth.BleMeshManager
import com.example.bluetooth.MeshCrypto
import com.example.bluetooth.MeshPacket
import com.example.bluetooth.MeshPeer
import com.example.bluetooth.MeshRadioTelemetry
import com.example.data.local.BlueWaveDao
import com.example.data.local.MessageEntity
import com.example.data.local.NodeSettings
import com.example.data.local.PeerEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.ThemeMode
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BlueWaveRepository(
    private val context: Context,
    private val dao: BlueWaveDao,
    private val settingsStore: SettingsDataStore,
    private val meshManager: BleMeshManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _activeRoomId = MutableStateFlow("#mesh")
    val activeRoomId: StateFlow<String> = _activeRoomId.asStateFlow()

    private val _unreadByRoom = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadByRoom: StateFlow<Map<String, Int>> = _unreadByRoom.asStateFlow()

    val telemetry: StateFlow<MeshRadioTelemetry> = meshManager.telemetry
    val meshPeers: StateFlow<Map<String, MeshPeer>> = meshManager.peers
    val settingsFlow: Flow<NodeSettings> = settingsStore.settingsFlow
    val dmRoomIds: Flow<List<String>> = dao.observeDmRoomIds()
    val knownPeers: Flow<List<PeerEntity>> = dao.observeKnownPeers()

    init {
        scope.launch {
            settingsStore.ensureIdentityCreated()
            settingsStore.settingsFlow.collect { settings ->
                meshManager.updateIdentity(settings.identity, settings.nickname)
            }
        }
        scope.launch {
            meshManager.incomingPackets.collect { packet ->
                handleIncomingPacket(packet)
            }
        }
    }

    fun observeMessagesForRoom(roomId: String): Flow<List<MessageEntity>> =
        dao.observeMessagesForRoom(roomId)

    fun setActiveRoom(roomId: String) {
        _activeRoomId.value = roomId
        _unreadByRoom.update { it - roomId }
    }

    fun refreshMeshState() {
        meshManager.refreshMeshState()
    }

    suspend fun sendRoomMessage(roomId: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val settings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()
        val packetId = UUID.randomUUID().toString()

        if (roomId.startsWith("dm:")) {
            val targetPeerId = roomId.removePrefix("dm:")
            val peerPubKey = meshManager.peers.value[targetPeerId]?.publicKeyBase64
                ?: dao.getPeerById(targetPeerId)?.publicKeyBase64

            if (peerPubKey.isNullOrBlank()) {
                insertLocalSystemNotice(
                    roomId = roomId,
                    text = "Cannot encrypt DM: peer @$targetPeerId public key not yet received over BLE mesh."
                )
                return
            }

            val aad = "${settings.identity.peerId}->$targetPeerId".toByteArray(Charsets.UTF_8)
            val encrypted = MeshCrypto.encryptDm(
                plaintext = trimmed,
                myPrivateKeyBase64 = settings.identity.privateKeyBase64,
                peerPublicKeyBase64 = peerPubKey,
                associatedData = aad
            )
            if (encrypted == null) {
                insertLocalSystemNotice(roomId, "Encryption failed for DM.")
                return
            }

            dao.insertMessage(
                MessageEntity(
                    packetId = packetId,
                    roomId = roomId,
                    senderId = settings.identity.peerId,
                    senderNick = settings.nickname,
                    isOutgoing = true,
                    isEncrypted = true,
                    hopCount = 0,
                    body = trimmed,
                    timestamp = now
                )
            )

            meshManager.sendBroadcastPacket(
                MeshPacket(
                    id = packetId,
                    type = "DM_ENC",
                    senderId = settings.identity.peerId,
                    senderNick = settings.nickname,
                    senderPubKey = settings.identity.publicKeyBase64,
                    targetId = targetPeerId,
                    channel = "DM",
                    payload = encrypted,
                    ttl = 5,
                    hopCount = 0,
                    timestamp = now
                )
            )
        } else {
            val channel = SettingsDataStore.normalizeChannel(roomId)
            settingsStore.joinChannel(channel)

            dao.insertMessage(
                MessageEntity(
                    packetId = packetId,
                    roomId = channel,
                    senderId = settings.identity.peerId,
                    senderNick = settings.nickname,
                    isOutgoing = true,
                    isEncrypted = false,
                    hopCount = 0,
                    body = trimmed,
                    timestamp = now
                )
            )

            meshManager.sendBroadcastPacket(
                MeshPacket(
                    id = packetId,
                    type = if (channel == "#mesh") "PUBLIC" else "CHANNEL",
                    senderId = settings.identity.peerId,
                    senderNick = settings.nickname,
                    senderPubKey = settings.identity.publicKeyBase64,
                    targetId = null,
                    channel = channel,
                    payload = trimmed,
                    ttl = 5,
                    hopCount = 0,
                    timestamp = now
                )
            )
        }
    }

    suspend fun sendMeshPing(roomId: String) {
        val settings = settingsStore.settingsFlow.first()
        val now = System.currentTimeMillis()
        val packetId = UUID.randomUUID().toString()
        val channel = if (roomId.startsWith("#")) roomId else "#mesh"

        dao.insertMessage(
            MessageEntity(
                packetId = packetId,
                roomId = roomId,
                senderId = settings.identity.peerId,
                senderNick = settings.nickname,
                isOutgoing = true,
                isSystem = true,
                body = "Mesh ping sent by @${settings.nickname}",
                timestamp = now
            )
        )

        meshManager.sendBroadcastPacket(
            MeshPacket(
                id = packetId,
                type = "PING",
                senderId = settings.identity.peerId,
                senderNick = settings.nickname,
                senderPubKey = settings.identity.publicKeyBase64,
                channel = channel,
                payload = "PING",
                ttl = 5,
                hopCount = 0,
                timestamp = now
            )
        )
    }

    suspend fun insertLocalSystemNotice(roomId: String, text: String) {
        val settings = settingsStore.settingsFlow.first()
        dao.insertMessage(
            MessageEntity(
                packetId = "sys_${UUID.randomUUID()}",
                roomId = roomId,
                senderId = settings.identity.peerId,
                senderNick = "mesh",
                isOutgoing = false,
                isSystem = true,
                body = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun setNickname(nickname: String) {
        settingsStore.setNickname(nickname)
    }

    suspend fun joinChannel(rawChannel: String): String {
        return settingsStore.joinChannel(rawChannel)
    }

    suspend fun setVibrateOnPing(enabled: Boolean) {
        settingsStore.setVibrateOnPing(enabled)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        settingsStore.setThemeMode(mode)
    }

    suspend fun clearRoomMessages(roomId: String) {
        dao.clearRoomMessages(roomId)
    }

    suspend fun panicWipe() {
        dao.wipeAllMessages()
        dao.wipeAllPeers()
        meshManager.clearRuntimeCaches()
        val fresh = settingsStore.panicResetIdentity()
        _activeRoomId.value = "#mesh"
        _unreadByRoom.value = emptyMap()
        insertLocalSystemNotice(
            roomId = "#mesh",
            text = "Identity reset. New handle @anon_${fresh.peerId.take(4)} (${fresh.peerId})."
        )
    }

    private suspend fun handleIncomingPacket(packet: MeshPacket) {
        val settings = settingsStore.settingsFlow.first()

        val existingPeer = dao.getPeerById(packet.senderId)
        val suppliedPubKey = packet.senderPubKey?.takeIf { it.isNotBlank() }
        if (suppliedPubKey != null) {
            if (!MeshCrypto.verifyPeerPublicKey(packet.senderId, suppliedPubKey)) return
            if (existingPeer != null && existingPeer.publicKeyBase64.isNotBlank() &&
                existingPeer.publicKeyBase64 != suppliedPubKey
            ) {
                return
            }
            dao.upsertPeer(
                PeerEntity(
                    peerId = packet.senderId,
                    nickname = packet.senderNick.ifBlank { existingPeer?.nickname ?: packet.senderId },
                    publicKeyBase64 = existingPeer?.publicKeyBase64?.takeIf { it.isNotBlank() }
                        ?: suppliedPubKey,
                    customAlias = existingPeer?.customAlias,
                    lastSeenAt = System.currentTimeMillis()
                )
            )
        }

        when (packet.type) {
            "ANNOUNCE" -> Unit
            "PING" -> {
                val room = SettingsDataStore.normalizeChannel(packet.channel)
                val targetRoom = if (room in settings.joinedChannels) room else "#mesh"
                dao.insertMessage(
                    MessageEntity(
                        packetId = packet.id,
                        roomId = targetRoom,
                        senderId = packet.senderId,
                        senderNick = packet.senderNick,
                        isOutgoing = false,
                        isSystem = true,
                        hopCount = packet.hopCount,
                        body = "@${packet.senderNick} pinged the mesh",
                        timestamp = packet.timestamp
                    )
                )
                triggerPingVibrationIfEnabled(settings)
            }
            "DM_ENC" -> {
                if (packet.targetId != settings.identity.peerId) return
                val senderPubKey = existingPeer?.publicKeyBase64?.takeIf { it.isNotBlank() }
                    ?: meshManager.peers.value[packet.senderId]?.publicKeyBase64?.takeIf { it.isNotBlank() }
                    ?: suppliedPubKey
                    ?: return

                val aad = "${packet.senderId}->${settings.identity.peerId}".toByteArray(Charsets.UTF_8)
                val plaintext = MeshCrypto.decryptDm(
                    encryptedPayload = packet.payload,
                    myPrivateKeyBase64 = settings.identity.privateKeyBase64,
                    peerPublicKeyBase64 = senderPubKey,
                    associatedData = aad
                ) ?: return

                val dmRoom = "dm:${packet.senderId}"
                dao.insertMessage(
                    MessageEntity(
                        packetId = packet.id,
                        roomId = dmRoom,
                        senderId = packet.senderId,
                        senderNick = packet.senderNick,
                        isOutgoing = false,
                        isEncrypted = true,
                        hopCount = packet.hopCount,
                        body = plaintext,
                        timestamp = packet.timestamp
                    )
                )
                incrementUnreadIfNotActive(dmRoom)
            }
            "PUBLIC", "CHANNEL" -> {
                val room = SettingsDataStore.normalizeChannel(packet.channel)
                if (room !in settings.joinedChannels) return
                dao.insertMessage(
                    MessageEntity(
                        packetId = packet.id,
                        roomId = room,
                        senderId = packet.senderId,
                        senderNick = packet.senderNick,
                        isOutgoing = false,
                        isEncrypted = false,
                        hopCount = packet.hopCount,
                        body = packet.payload,
                        timestamp = packet.timestamp
                    )
                )
                incrementUnreadIfNotActive(room)
            }
        }
    }

    private fun incrementUnreadIfNotActive(roomId: String) {
        if (_activeRoomId.value != roomId) {
            _unreadByRoom.update { map ->
                map + (roomId to ((map[roomId] ?: 0) + 1))
            }
        }
    }

    private fun triggerPingVibrationIfEnabled(settings: NodeSettings) {
        if (!settings.vibrateOnPing) return
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(VibrationEffect.createOneShot(140L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(140L)
                }
            }
        } catch (_: SecurityException) {
        }
    }

    fun close() {
        meshManager.close()
        scope.cancel()
    }
}
