package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.squareup.moshi.Moshi
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@SuppressLint("MissingPermission")
class BleMeshManager(
    private val context: Context
) : Closeable {

    companion object {
        val MESH_SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val MESH_CHAR_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_SEEN_PACKETS = 500
        private const val STORE_FORWARD_MAX = 30
        private const val STORE_FORWARD_WINDOW_MS = 5 * 60 * 1000L
        private const val DEFAULT_TTL = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val moshi = Moshi.Builder().build()
    private val packetAdapter = moshi.adapter(MeshPacket::class.java)
    private val framer = MeshPacketFramer()

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _telemetry = MutableStateFlow(MeshRadioTelemetry())
    val telemetry: StateFlow<MeshRadioTelemetry> = _telemetry.asStateFlow()

    private val _peers = MutableStateFlow<Map<String, MeshPeer>>(emptyMap())
    val peers: StateFlow<Map<String, MeshPeer>> = _peers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<MeshPacket>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<MeshPacket> = _incomingPackets.asSharedFlow()

    private val seenPacketIds = LinkedHashSet<String>()
    private val seenLock = Any()

    private val relayBuffer = ArrayDeque<MeshPacket>()
    private val bufferLock = Any()

    private class ClientLink(
        val address: String,
        val gatt: BluetoothGatt,
        var characteristic: BluetoothGattCharacteristic? = null,
        var maxPayloadBytes: Int = 20,
        val writeMutex: Mutex = Mutex(),
        @Volatile var pendingWrite: CompletableDeferred<Int>? = null
    )

    private class ServerSubscriber(
        val device: BluetoothDevice,
        var maxPayloadBytes: Int = 20,
        val notifyMutex: Mutex = Mutex(),
        @Volatile var pendingNotify: CompletableDeferred<Int>? = null
    )

    private val clientLinks = ConcurrentHashMap<String, ClientLink>()
    private val connectingAddresses = ConcurrentHashMap.newKeySet<String>()
    private val serverSubscribers = ConcurrentHashMap<String, ServerSubscriber>()
    private val rssiByAddress = ConcurrentHashMap<String, Int>()

    private var gattServer: BluetoothGattServer? = null
    private var serverMeshChar: BluetoothGattCharacteristic? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private var announceHeartbeatJob: Job? = null
    private var receiverRegistered = false

    @Volatile
    private var localIdentity: MeshIdentity = MeshCrypto.generateIdentity()

    @Volatile
    private var localNickname: String = "anon_${localIdentity.peerId.take(4)}"

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                refreshMeshState()
            }
        }
    }

    init {
        registerAdapterReceiver()
        refreshMeshState()
    }

    fun updateIdentity(identity: MeshIdentity, nickname: String) {
        localIdentity = identity
        localNickname = nickname.ifBlank { "anon_${identity.peerId.take(4)}" }
        if (_telemetry.value.hardwareState == RadioHardwareState.READY) {
            scope.launch { broadcastAnnounce() }
        }
    }

    fun refreshMeshState() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            stopMeshRadio()
            _telemetry.update {
                it.copy(
                    hardwareState = RadioHardwareState.UNAVAILABLE,
                    isAdvertising = false,
                    isScanning = false,
                    statusDetail = "No BLE radio hardware on this device"
                )
            }
            return
        }

        if (!BluetoothPermissionHelper.hasAllPermissions(context)) {
            stopMeshRadio()
            _telemetry.update {
                it.copy(
                    hardwareState = RadioHardwareState.PERMISSIONS_REQUIRED,
                    isAdvertising = false,
                    isScanning = false,
                    statusDetail = "Bluetooth permissions required to join BLE mesh"
                )
            }
            return
        }

        if (!adapter.isEnabled) {
            stopMeshRadio()
            _telemetry.update {
                it.copy(
                    hardwareState = RadioHardwareState.DISABLED,
                    isAdvertising = false,
                    isScanning = false,
                    statusDetail = "Bluetooth radio is turned off"
                )
            }
            return
        }

        _telemetry.update {
            it.copy(
                hardwareState = RadioHardwareState.READY,
                statusDetail = "BLE mesh radio active"
            )
        }
        startMeshRadio()
    }

    fun sendBroadcastPacket(packet: MeshPacket) {
        recordSeen(packet.id)
        bufferForStoreAndForward(packet)
        _telemetry.update { it.copy(packetsSent = it.packetsSent + 1) }
        scope.launch {
            sendPacketToAllNeighbors(packet, excludeAddress = null)
        }
    }

    fun clearRuntimeCaches() {
        synchronized(seenLock) { seenPacketIds.clear() }
        synchronized(bufferLock) { relayBuffer.clear() }
        framer.clear()
        _peers.value = emptyMap()
        _telemetry.update {
            it.copy(
                knownMeshPeerCount = 0,
                packetsRelayed = 0,
                packetsSent = 0,
                packetsReceived = 0
            )
        }
    }

    private fun startMeshRadio() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled || !BluetoothPermissionHelper.hasAllPermissions(context)) return

        setupGattServerIfNeeded()
        startAdvertisingIfNeeded(adapter)
        startScanningIfNeeded(adapter)
        startAnnounceHeartbeat()
    }

    private fun stopMeshRadio() {
        announceHeartbeatJob?.cancel()
        announceHeartbeatJob = null

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {
        }
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        clientLinks.values.forEach { link ->
            try {
                link.gatt.disconnect()
                link.gatt.close()
            } catch (_: Exception) {
            }
        }
        clientLinks.clear()
        connectingAddresses.clear()
        serverSubscribers.clear()

        try {
            gattServer?.close()
        } catch (_: Exception) {
        } finally {
            gattServer = null
            serverMeshChar = null
        }

        updateNeighborTelemetry()
    }

    private fun setupGattServerIfNeeded() {
        if (gattServer != null) return
        val mgr = bluetoothManager ?: return
        try {
            val server = mgr.openGattServer(context, gattServerCallback) ?: return
            val service = BluetoothGattService(
                MESH_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )
            val char = BluetoothGattCharacteristic(
                MESH_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_WRITE or
                    BluetoothGattCharacteristic.PERMISSION_READ
            )
            val cccd = BluetoothGattDescriptor(
                CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            char.addDescriptor(cccd)
            service.addCharacteristic(char)
            server.addService(service)
            gattServer = server
            serverMeshChar = char
        } catch (_: Exception) {
        }
    }

    private fun startAdvertisingIfNeeded(adapter: BluetoothAdapter) {
        if (_telemetry.value.isAdvertising) return
        if (!BluetoothPermissionHelper.hasAdvertisePermission(context)) return
        val adv = adapter.bluetoothLeAdvertiser ?: run {
            _telemetry.update { it.copy(statusDetail = "BLE peripheral advertising unsupported on this hardware") }
            return
        }
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (_: Exception) {
        }
    }

    private fun startScanningIfNeeded(adapter: BluetoothAdapter) {
        if (_telemetry.value.isScanning) return
        if (!BluetoothPermissionHelper.hasScanPermission(context)) return
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MESH_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            leScanner.startScan(listOf(filter), settings, scanCallback)
            _telemetry.update { it.copy(isScanning = true) }
        } catch (_: Exception) {
            _telemetry.update { it.copy(isScanning = false) }
        }
    }

    private fun startAnnounceHeartbeat() {
        if (announceHeartbeatJob?.isActive == true) return
        announceHeartbeatJob = scope.launch {
            while (isActive) {
                if (clientLinks.isNotEmpty() || serverSubscribers.isNotEmpty()) {
                    broadcastAnnounce()
                }
                delay(20_000L)
            }
        }
    }

    private suspend fun broadcastAnnounce() {
        val packet = MeshPacket(
            id = UUID.randomUUID().toString(),
            type = "ANNOUNCE",
            senderId = localIdentity.peerId,
            senderNick = localNickname,
            senderPubKey = localIdentity.publicKeyBase64,
            channel = "#mesh",
            payload = "",
            ttl = DEFAULT_TTL,
            hopCount = 0
        )
        recordSeen(packet.id)
        sendPacketToAllNeighbors(packet, excludeAddress = null)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            _telemetry.update { it.copy(isAdvertising = true) }
        }

        override fun onStartFailure(errorCode: Int) {
            _telemetry.update { it.copy(isAdvertising = false) }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            rssiByAddress[address] = result.rssi
            updatePeerRssiIfKnown(address, result.rssi)

            if (!clientLinks.containsKey(address) && !connectingAddresses.contains(address)) {
                connectToBleNeighbor(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _telemetry.update { it.copy(isScanning = false) }
        }
    }

    private fun connectToBleNeighbor(device: BluetoothDevice) {
        val address = device.address ?: return
        if (!BluetoothPermissionHelper.hasConnectPermission(context)) return
        if (!connectingAddresses.add(address)) return

        try {
            device.connectGatt(context, false, gattClientCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: Exception) {
            connectingAddresses.remove(address)
        }
    }

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            connectingAddresses.remove(address)

            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                val link = ClientLink(address = address, gatt = gatt)
                clientLinks[address] = link
                updateNeighborTelemetry()
                try {
                    gatt.requestMtu(512)
                } catch (_: Exception) {
                    gatt.discoverServices()
                }
            } else {
                clientLinks.remove(address)
                try {
                    gatt.close()
                } catch (_: Exception) {
                }
                updateNeighborTelemetry()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val link = clientLinks[gatt.device.address]
            if (link != null && status == BluetoothGatt.GATT_SUCCESS) {
                link.maxPayloadBytes = (mtu - 3).coerceIn(20, 480)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service = gatt.getService(MESH_SERVICE_UUID) ?: return
            val char = service.getCharacteristic(MESH_CHAR_UUID) ?: return
            val link = clientLinks[gatt.device.address] ?: return
            link.characteristic = char

            gatt.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(cccd)
                }
            } else {
                onNeighborLinkReady(link.address)
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                onNeighborLinkReady(gatt.device.address)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            clientLinks[gatt.device.address]?.pendingWrite?.complete(status)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                val bytes = characteristic.value ?: return
                handleIncomingChunk(gatt.device.address, bytes)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingChunk(gatt.device.address, value)
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val address = device.address
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverSubscribers.remove(address)
                updateNeighborTelemetry()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            serverSubscribers[device.address]?.maxPayloadBytes = (mtu - 3).coerceIn(20, 480)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val enabled = value != null &&
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                if (enabled) {
                    serverSubscribers.putIfAbsent(device.address, ServerSubscriber(device = device))
                    updateNeighborTelemetry()
                    onNeighborLinkReady(device.address)
                } else {
                    serverSubscribers.remove(device.address)
                    updateNeighborTelemetry()
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (characteristic.uuid == MESH_CHAR_UUID && value != null) {
                handleIncomingChunk(device.address, value)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            serverSubscribers[device.address]?.pendingNotify?.complete(status)
        }
    }

    private fun onNeighborLinkReady(address: String) {
        scope.launch {
            val announce = MeshPacket(
                id = UUID.randomUUID().toString(),
                type = "ANNOUNCE",
                senderId = localIdentity.peerId,
                senderNick = localNickname,
                senderPubKey = localIdentity.publicKeyBase64,
                channel = "#mesh",
                payload = "",
                ttl = DEFAULT_TTL,
                hopCount = 0
            )
            recordSeen(announce.id)
            sendPacketToNeighborAddress(address, announce)

            // Store-and-forward: replay recent non-expired packets to newly joined neighbor
            val buffered = synchronized(bufferLock) {
                val cutoff = System.currentTimeMillis() - STORE_FORWARD_WINDOW_MS
                relayBuffer.filter { it.timestamp >= cutoff && it.ttl > 0 }
            }
            for (pkt in buffered) {
                sendPacketToNeighborAddress(address, pkt)
            }
        }
    }

    private fun handleIncomingChunk(sourceAddress: String, frame: ByteArray) {
        val fullBytes = framer.onChunkReceived(sourceAddress, frame) ?: return
        val json = String(fullBytes, Charsets.UTF_8)
        val packet = runCatching { packetAdapter.fromJson(json) }.getOrNull() ?: return

        if (packet.senderId == localIdentity.peerId) return
        if (!recordSeen(packet.id)) return

        _telemetry.update { it.copy(packetsReceived = it.packetsReceived + 1) }

        // Track sender in peer directory with hop distance & public key
        val existing = _peers.value[packet.senderId]
        val rssi = rssiByAddress[sourceAddress]
        val updatedPeer = MeshPeer(
            peerId = packet.senderId,
            nickname = packet.senderNick.ifBlank { existing?.nickname ?: "anon_${packet.senderId.take(4)}" },
            publicKeyBase64 = packet.senderPubKey ?: existing?.publicKeyBase64.orEmpty(),
            bleAddress = if (packet.hopCount == 0) sourceAddress else existing?.bleAddress,
            rssi = if (packet.hopCount == 0) rssi ?: existing?.rssi else existing?.rssi,
            hopCount = minOf(packet.hopCount, existing?.hopCount ?: packet.hopCount),
            isDirectNeighbor = packet.hopCount == 0 || (existing?.isDirectNeighbor == true),
            lastSeenAt = System.currentTimeMillis()
        )
        _peers.update { map ->
            val next = map + (packet.senderId to updatedPeer)
            _telemetry.update { t -> t.copy(knownMeshPeerCount = next.size) }
            next
        }

        if (packet.type != "ANNOUNCE") {
            bufferForStoreAndForward(packet)
        }

        _incomingPackets.tryEmit(packet)

        // Multi-hop mesh relay if TTL remains and packet isn't a DM strictly for us
        val isDmForUs = packet.type == "DM_ENC" && packet.targetId == localIdentity.peerId
        if (!isDmForUs && packet.ttl > 1) {
            val relayed = packet.copy(
                ttl = packet.ttl - 1,
                hopCount = packet.hopCount + 1
            )
            _telemetry.update { it.copy(packetsRelayed = it.packetsRelayed + 1) }
            scope.launch {
                sendPacketToAllNeighbors(relayed, excludeAddress = sourceAddress)
            }
        }
    }

    private suspend fun sendPacketToAllNeighbors(packet: MeshPacket, excludeAddress: String?) {
        val allAddresses = (clientLinks.keys + serverSubscribers.keys).distinct()
        for (addr in allAddresses) {
            if (addr != excludeAddress) {
                sendPacketToNeighborAddress(addr, packet)
            }
        }
    }

    private suspend fun sendPacketToNeighborAddress(address: String, packet: MeshPacket) {
        val json = packetAdapter.toJson(packet) ?: return
        val bytes = json.toByteArray(Charsets.UTF_8)

        val client = clientLinks[address]
        if (client != null && client.characteristic != null) {
            val char = client.characteristic ?: return
            val chunks = framer.fragment(bytes, client.maxPayloadBytes)
            client.writeMutex.withLock {
                for (chunk in chunks) {
                    val deferred = CompletableDeferred<Int>()
                    client.pendingWrite = deferred
                    val queued = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            client.gatt.writeCharacteristic(
                                char,
                                chunk,
                                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = chunk
                            @Suppress("DEPRECATION")
                            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            @Suppress("DEPRECATION")
                            client.gatt.writeCharacteristic(char)
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (!queued) {
                        client.pendingWrite = null
                        break
                    }
                    withTimeoutOrNull(2_000L) { deferred.await() }
                    client.pendingWrite = null
                }
            }
            return
        }

        val subscriber = serverSubscribers[address]
        val server = gattServer
        val serverChar = serverMeshChar
        if (subscriber != null && server != null && serverChar != null) {
            val chunks = framer.fragment(bytes, subscriber.maxPayloadBytes)
            subscriber.notifyMutex.withLock {
                for (chunk in chunks) {
                    val deferred = CompletableDeferred<Int>()
                    subscriber.pendingNotify = deferred
                    val sent = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(
                                subscriber.device,
                                serverChar,
                                false,
                                chunk
                            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            serverChar.value = chunk
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(subscriber.device, serverChar, false)
                        }
                    } catch (_: Exception) {
                        false
                    }
                    if (!sent) {
                        subscriber.pendingNotify = null
                        break
                    }
                    withTimeoutOrNull(2_000L) { deferred.await() }
                    subscriber.pendingNotify = null
                }
            }
        }
    }

    private fun recordSeen(packetId: String): Boolean {
        synchronized(seenLock) {
            if (seenPacketIds.contains(packetId)) return false
            seenPacketIds.add(packetId)
            while (seenPacketIds.size > MAX_SEEN_PACKETS) {
                val iter = seenPacketIds.iterator()
                if (iter.hasNext()) {
                    iter.next()
                    iter.remove()
                }
            }
            return true
        }
    }

    private fun bufferForStoreAndForward(packet: MeshPacket) {
        synchronized(bufferLock) {
            relayBuffer.addLast(packet)
            while (relayBuffer.size > STORE_FORWARD_MAX) {
                relayBuffer.removeFirst()
            }
        }
    }

    private fun updatePeerRssiIfKnown(address: String, rssi: Int) {
        _peers.update { map ->
            val match = map.values.firstOrNull { it.bleAddress == address } ?: return@update map
            map + (match.peerId to match.copy(rssi = rssi, lastSeenAt = System.currentTimeMillis()))
        }
    }

    private fun updateNeighborTelemetry() {
        val uniqueNeighbors = (clientLinks.keys + serverSubscribers.keys).distinct().size
        _telemetry.update { it.copy(directNeighborCount = uniqueNeighbors) }
    }

    private fun registerAdapterReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            stateReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    override fun close() {
        stopMeshRadio()
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(stateReceiver)
            } catch (_: IllegalArgumentException) {
            }
            receiverRegistered = false
        }
        scope.cancel()
    }
}
