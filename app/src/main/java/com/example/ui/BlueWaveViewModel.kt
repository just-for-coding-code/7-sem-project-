package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.bluetooth.BleMeshManager
import com.example.bluetooth.MeshPeer
import com.example.bluetooth.MeshRadioTelemetry
import com.example.data.BlueWaveRepository
import com.example.data.local.BlueWaveDatabase
import com.example.data.local.MessageEntity
import com.example.data.local.NodeSettings
import com.example.data.local.PeerEntity
import com.example.data.local.SettingsDataStore
import com.example.data.local.ThemeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MeshRoomTab(
    val roomId: String,
    val label: String,
    val isDm: Boolean,
    val unreadCount: Int = 0
)

data class BlueWaveUiState(
    val telemetry: MeshRadioTelemetry = MeshRadioTelemetry(),
    val settings: NodeSettings = NodeSettings(),
    val activeRoomId: String = "#mesh",
    val roomTabs: List<MeshRoomTab> = listOf(
        MeshRoomTab("#mesh", "#mesh", isDm = false),
        MeshRoomTab("#general", "#general", isDm = false),
        MeshRoomTab("#sos", "#sos", isDm = false)
    ),
    val messages: List<MessageEntity> = emptyList(),
    val livePeers: List<MeshPeer> = emptyList(),
    val knownPeers: List<PeerEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
class BlueWaveViewModel(application: Application) : AndroidViewModel(application) {
    private val database = BlueWaveDatabase.getInstance(application)
    private val settingsStore = SettingsDataStore(application)
    private val meshManager = BleMeshManager(application)
    private val repository = BlueWaveRepository(
        context = application,
        dao = database.blueWaveDao(),
        settingsStore = settingsStore,
        meshManager = meshManager
    )

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val toastEvents = _toastEvents.asSharedFlow()

    private val activeMessagesFlow = repository.activeRoomId.flatMapLatest { roomId ->
        repository.observeMessagesForRoom(roomId)
    }

    private val radioAndPeersFlow = combine(
        repository.telemetry,
        repository.meshPeers,
        repository.knownPeers,
        repository.settingsFlow
    ) { telemetry, liveMap, knownList, settings ->
        RadioPeersSnapshot(telemetry, liveMap, knownList, settings)
    }

    private val roomsAndMessagesFlow = combine(
        repository.activeRoomId,
        repository.dmRoomIds,
        repository.unreadByRoom,
        activeMessagesFlow
    ) { activeRoom, dmRooms, unreadMap, messages ->
        RoomsMessagesSnapshot(activeRoom, dmRooms, unreadMap, messages)
    }

    val uiState: StateFlow<BlueWaveUiState> = combine(
        radioAndPeersFlow,
        roomsAndMessagesFlow
    ) { rp, rm ->
        val channelTabs = rp.settings.joinedChannels.map { channel ->
            MeshRoomTab(
                roomId = channel,
                label = channel,
                isDm = false,
                unreadCount = rm.unreadMap[channel] ?: 0
            )
        }

        val activeDmRoom = rm.activeRoom.takeIf { it.startsWith("dm:") }
        val allDmRooms = (rm.dmRooms + listOfNotNull(activeDmRoom)).distinct()
        val dmTabs = allDmRooms.map { dmRoom ->
            val peerId = dmRoom.removePrefix("dm:")
            val peerName = rp.liveMap[peerId]?.nickname
                ?: rp.knownList.firstOrNull { it.peerId == peerId }?.displayName
                ?: peerId.take(6)
            MeshRoomTab(
                roomId = dmRoom,
                label = "@$peerName",
                isDm = true,
                unreadCount = rm.unreadMap[dmRoom] ?: 0
            )
        }

        val sortedLivePeers = rp.liveMap.values.sortedWith(
            compareBy<MeshPeer> { it.hopCount }.thenByDescending { it.lastSeenAt }
        )

        BlueWaveUiState(
            telemetry = rp.telemetry,
            settings = rp.settings,
            activeRoomId = rm.activeRoom,
            roomTabs = channelTabs + dmTabs,
            messages = rm.messages,
            livePeers = sortedLivePeers,
            knownPeers = rp.knownList
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = BlueWaveUiState()
    )

    fun refreshMeshState() {
        repository.refreshMeshState()
    }

    fun selectRoom(roomId: String) {
        repository.setActiveRoom(roomId)
    }

    fun openDmWithPeer(peerId: String) {
        repository.setActiveRoom("dm:$peerId")
    }

    fun joinChannel(rawChannel: String) {
        viewModelScope.launch {
            val normalized = repository.joinChannel(rawChannel)
            repository.setActiveRoom(normalized)
            repository.insertLocalSystemNotice(normalized, "Joined channel $normalized")
        }
    }

    fun updateNickname(nickname: String) {
        viewModelScope.launch {
            repository.setNickname(nickname)
            _toastEvents.tryEmit("Handle updated")
        }
    }

    fun setVibrateOnPing(enabled: Boolean) {
        viewModelScope.launch { repository.setVibrateOnPing(enabled) }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repository.setThemeMode(mode) }
    }

    fun cycleThemeMode(currentlyDark: Boolean) {
        val next = if (currentlyDark) ThemeMode.LIGHT else ThemeMode.DARK
        setThemeMode(next)
    }

    fun sendPing() {
        val currentRoom = uiState.value.activeRoomId
        viewModelScope.launch {
            repository.sendMeshPing(currentRoom)
        }
    }

    fun panicWipe() {
        viewModelScope.launch {
            repository.panicWipe()
            _toastEvents.tryEmit("Ephemeral identity & history wiped")
        }
    }

    fun clearCurrentRoom() {
        val currentRoom = uiState.value.activeRoomId
        viewModelScope.launch {
            repository.clearRoomMessages(currentRoom)
            _toastEvents.tryEmit("Conversation cleared")
        }
    }

    fun submitInput(rawText: String) {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return
        val currentRoom = uiState.value.activeRoomId

        viewModelScope.launch {
            if (trimmed.startsWith("/")) {
                handleSlashCommand(trimmed, currentRoom)
            } else {
                repository.sendRoomMessage(currentRoom, trimmed)
            }
        }
    }

    private suspend fun handleSlashCommand(commandLine: String, currentRoom: String) {
        val parts = commandLine.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val arg = parts.getOrNull(1)?.trim().orEmpty()

        when (cmd) {
            "/help" -> {
                repository.insertLocalSystemNotice(
                    currentRoom,
                    "Commands: /nick <name>, /join #channel, /msg @peer <text>, /who, /ping, /clear, /wipe"
                )
            }
            "/nick" -> {
                if (arg.isBlank()) {
                    repository.insertLocalSystemNotice(currentRoom, "Usage: /nick <handle>")
                } else {
                    repository.setNickname(arg)
                    repository.insertLocalSystemNotice(currentRoom, "Handle changed to @${arg.replace(" ", "_").take(20)}")
                }
            }
            "/join" -> {
                if (arg.isBlank()) {
                    repository.insertLocalSystemNotice(currentRoom, "Usage: /join #channel")
                } else {
                    val channel = repository.joinChannel(arg)
                    repository.setActiveRoom(channel)
                    repository.insertLocalSystemNotice(channel, "Joined $channel on BLE mesh")
                }
            }
            "/who" -> {
                val peers = uiState.value.livePeers
                val summary = if (peers.isEmpty()) {
                    "No mesh peers discovered nearby yet."
                } else {
                    "Online mesh peers (${peers.size}): " + peers.joinToString(", ") {
                        "@${it.nickname} (${it.hopCount}h)"
                    }
                }
                repository.insertLocalSystemNotice(currentRoom, summary)
            }
            "/ping" -> {
                repository.sendMeshPing(currentRoom)
            }
            "/clear" -> {
                repository.clearRoomMessages(currentRoom)
            }
            "/wipe" -> {
                repository.panicWipe()
            }
            "/msg", "/dm" -> {
                val dmParts = arg.split(" ", limit = 2)
                val targetNickOrId = dmParts.getOrNull(0)?.removePrefix("@").orEmpty()
                val body = dmParts.getOrNull(1)?.trim().orEmpty()
                if (targetNickOrId.isBlank() || body.isBlank()) {
                    repository.insertLocalSystemNotice(currentRoom, "Usage: /msg @peer <message>")
                    return
                }
                val match = uiState.value.livePeers.firstOrNull {
                    it.nickname.equals(targetNickOrId, ignoreCase = true) ||
                        it.peerId.startsWith(targetNickOrId, ignoreCase = true)
                } ?: uiState.value.knownPeers.firstOrNull {
                    it.nickname.equals(targetNickOrId, ignoreCase = true) ||
                        it.peerId.startsWith(targetNickOrId, ignoreCase = true)
                }?.let {
                    MeshPeer(it.peerId, it.nickname, it.publicKeyBase64)
                }

                if (match == null) {
                    repository.insertLocalSystemNotice(currentRoom, "Peer @$targetNickOrId not found in mesh directory.")
                } else {
                    val dmRoom = "dm:${match.peerId}"
                    repository.setActiveRoom(dmRoom)
                    repository.sendRoomMessage(dmRoom, body)
                }
            }
            else -> {
                repository.insertLocalSystemNotice(currentRoom, "Unknown command '$cmd'. Type /help for commands.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.close()
    }

    private data class RadioPeersSnapshot(
        val telemetry: MeshRadioTelemetry,
        val liveMap: Map<String, MeshPeer>,
        val knownList: List<PeerEntity>,
        val settings: NodeSettings
    )

    private data class RoomsMessagesSnapshot(
        val activeRoom: String,
        val dmRooms: List<String>,
        val unreadMap: Map<String, Int>,
        val messages: List<MessageEntity>
    )
}
