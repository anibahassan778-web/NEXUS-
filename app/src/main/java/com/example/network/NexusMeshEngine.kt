package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.example.domain.model.ActiveMeshPath
import com.example.domain.model.MeshMode
import com.example.domain.model.MeshRoute
import com.example.domain.model.NetworkStats
import com.example.domain.model.PeerNode
import com.example.domain.model.RadioHardwareState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.selects.select
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NexusMeshEngine(private val context: Context) {

    companion object {
        private const val SCAN_AUTO_TIMEOUT_MS = 45_000L
        private const val HEARTBEAT_FOREGROUND_MS = 5_000L
        private const val HEARTBEAT_BACKGROUND_MS = 15_000L
        private const val PING_TIMEOUT_MS = 15_000L
        private const val CONTROL_QUEUE_CAPACITY = 256
        private const val REALTIME_QUEUE_CAPACITY = 16
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val router = MeshRouter()

    @Volatile
    private var currentProfile = UserProfile()

    @Volatile
    private var appInForeground = true

    @Volatile
    private var hasActiveCallOrPtt = false

    private val _discoveredPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val discoveredPeers: StateFlow<List<PeerNode>> = _discoveredPeers.asStateFlow()

    private val _connectedPeers = MutableStateFlow<List<PeerNode>>(emptyList())
    val connectedPeers: StateFlow<List<PeerNode>> = _connectedPeers.asStateFlow()

    val multiHopPeers: StateFlow<List<PeerNode>> = router.multiHopPeers
    val routes: StateFlow<Map<String, MeshRoute>> = router.routes
    val topologyEdges: StateFlow<List<Pair<String, String>>> = router.topologyEdges
    val activePaths: StateFlow<List<ActiveMeshPath>> = router.activePaths

    private val _radioState = MutableStateFlow(RadioHardwareState())
    val radioState: StateFlow<RadioHardwareState> = _radioState.asStateFlow()

    private val _networkStats = MutableStateFlow(NetworkStats())
    val networkStats: StateFlow<NetworkStats> = _networkStats.asStateFlow()

    private val _typingPeers = MutableStateFlow<Map<String, String>>(emptyMap())
    val typingPeers: StateFlow<Map<String, String>> = _typingPeers.asStateFlow()

    private val _incomingPackets = MutableSharedFlow<Pair<WireEnvelope, TransportType>>(extraBufferCapacity = 128)
    val incomingPackets: SharedFlow<Pair<WireEnvelope, TransportType>> = _incomingPackets.asSharedFlow()

    private val _realtimeMediaPackets = MutableSharedFlow<Pair<WireEnvelope, TransportType>>(extraBufferCapacity = 256)
    val realtimeMediaPackets: SharedFlow<Pair<WireEnvelope, TransportType>> = _realtimeMediaPackets.asSharedFlow()

    private val _routeFlushTrigger = MutableSharedFlow<Set<String>>(extraBufferCapacity = 16)
    val routeFlushTrigger: SharedFlow<Set<String>> = _routeFlushTrigger.asSharedFlow()

    private val _statusMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val statusMessages: SharedFlow<String> = _statusMessages.asSharedFlow()

    private class ActiveSession(
        val sessionId: String,
        @Volatile var peerId: String,
        val transport: TransportType,
        val remoteHint: String?,
        val outputStream: OutputStream,
        val closeAction: () -> Unit
    ) {
        val closed = AtomicBoolean(false)
        val controlQueue = Channel<WireEnvelope>(
            capacity = CONTROL_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
        val realtimeQueue = Channel<WireEnvelope>(
            capacity = REALTIME_QUEUE_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        var writerJob: Job? = null

        fun enqueue(envelope: WireEnvelope): Boolean {
            if (closed.get()) return false
            val isRealtime = envelope.type == PacketType.AUDIO_FRAME.name ||
                envelope.type == PacketType.VIDEO_FRAME.name ||
                envelope.type == PacketType.PTT_AUDIO_FRAME.name
            val result = if (isRealtime) {
                realtimeQueue.trySend(envelope)
            } else {
                controlQueue.trySend(envelope)
            }
            return result.isSuccess
        }

        fun shutdown() {
            if (closed.compareAndSet(false, true)) {
                writerJob?.cancel()
                controlQueue.close()
                realtimeQueue.close()
                runCatching { closeAction() }
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, ActiveSession>()
    private val pendingPings = ConcurrentHashMap<String, Long>()
    private var scanTimeoutJob: Job? = null
    private var lastRouteAdvertAtMs = 0L
    private var topologyDirty = false

    private val socketCallback = TransportSocketCallback { transport, input, output, remoteHint, close ->
        attachSession(transport, remoteHint, input, output, close)
    }

    private val peerCallback = TransportPeerCallback { peer ->
        upsertDiscoveredPeer(peer)
    }

    private val bluetoothTransport = BluetoothTransport(
        context = context,
        scope = scope,
        onPeerDiscovered = peerCallback,
        onSocketConnected = socketCallback,
        onScanningChanged = { scanning ->
            _radioState.update { it.copy(isBluetoothScanning = scanning) }
        },
        onRadioStateChanged = { refreshHardwareStatus() }
    )

    private val wifiDirectTransport = WifiDirectTransport(
        context = context,
        scope = scope,
        onPeerDiscovered = peerCallback,
        onSocketConnected = socketCallback,
        onGroupStateChanged = { isHost, groupName ->
            _networkStats.update {
                it.copy(
                    isHostingWifiDirectGroup = isHost,
                    wifiDirectGroupName = groupName,
                    hostDisplayName = if (isHost) currentProfile.nickname else it.hostDisplayName
                )
            }
        },
        onScanningChanged = { scanning ->
            _radioState.update { it.copy(isWifiDirectScanning = scanning) }
        },
        onRadioStateChanged = { refreshHardwareStatus() }
    )

    private val hotspotLanTransport: HotspotLanTransport = HotspotLanTransport(
        context = context,
        scope = scope,
        localBeaconProvider = {
            LanBeacon(
                nodeId = currentProfile.nodeId,
                nickname = currentProfile.nickname,
                deviceName = Build.MODEL ?: "Android",
                statusText = currentProfile.statusMessage,
                avatarIndex = currentProfile.avatarIndex,
                tcpPort = HotspotLanTransport.TCP_PORT,
                isHotspotHost = _networkStats.value.isHostingHotspot
            )
        },
        onPeerDiscovered = peerCallback,
        onSocketConnected = socketCallback,
        onHotspotStateChanged = { isHosting, ssid, passphrase ->
            _networkStats.update {
                it.copy(
                    isHostingHotspot = isHosting,
                    hotspotSsid = ssid,
                    hotspotPassphrase = passphrase,
                    hostDisplayName = if (isHosting) currentProfile.nickname else null,
                    localIpAddress = hotspotLanTransport.detectLocalIpv4Address()
                )
            }
        },
        onScanningChanged = { scanning ->
            _radioState.update { it.copy(isLanScanning = scanning) }
        },
        onRadioStateChanged = { refreshHardwareStatus() }
    )

    private val allTransports: List<NexusTransport> = listOf(
        wifiDirectTransport,
        hotspotLanTransport,
        bluetoothTransport
    )

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private var networkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshHardwareStatus()
        }

        override fun onLost(network: Network) {
            refreshHardwareStatus()
        }
    }

    init {
        refreshHardwareStatus()
        allTransports.forEach { it.startServer() }
        registerNetworkCallback()

        // Periodic heartbeat, route advertisement, ping pruning, and route expiration loop
        scope.launch {
            while (isActive) {
                refreshHardwareStatus()
                val now = System.currentTimeMillis()
                pendingPings.entries.removeIf { now - it.value > PING_TIMEOUT_MS }
                router.pruneExpiredRoutes(currentProfile.nodeId, _connectedPeers.value)

                if (sessions.isNotEmpty()) {
                    sendPingToAll()
                    val advertCooldown = if (appInForeground) 5_000L else 15_000L
                    if (currentProfile.meshMode != MeshMode.DIRECT_ONLY &&
                        (topologyDirty || now - lastRouteAdvertAtMs >= advertCooldown)
                    ) {
                        topologyDirty = false
                        lastRouteAdvertAtMs = now
                        broadcastRouteAdvertisement()
                    }
                }
                _networkStats.update {
                    it.copy(reachableMultiHopCount = router.multiHopPeers.value.size)
                }
                delay(if (appInForeground || hasActiveCallOrPtt) HEARTBEAT_FOREGROUND_MS else HEARTBEAT_BACKGROUND_MS)
            }
        }
    }

    fun setAppInForeground(inForeground: Boolean) {
        appInForeground = inForeground
        hotspotLanTransport.setForegroundMode(inForeground)
        if (inForeground) {
            refreshHardwareStatus()
            allTransports.forEach { it.startServer() }
        } else if (!hasActiveCallOrPtt && !_networkStats.value.isHostingHotspot) {
            // Pause battery-intensive BT/P2P radio discovery while backgrounded
            bluetoothTransport.stopDiscovery()
            wifiDirectTransport.stopDiscovery()
        }
    }

    fun setActiveRealtimeSession(active: Boolean) {
        hasActiveCallOrPtt = active
    }

    fun updateLocalProfile(profile: UserProfile) {
        currentProfile = profile
        _networkStats.update { it.copy(meshMode = profile.meshMode) }
        if (profile.preferredTransport != TransportType.AUTO) {
            _networkStats.update { it.copy(activeTransport = profile.preferredTransport) }
        } else {
            recomputeOptimalTransport()
        }
        router.onDirectNeighborsChanged(profile.nodeId, _connectedPeers.value)
    }

    fun updateQueuedRelayCount(count: Int) {
        _networkStats.update { it.copy(queuedRelayCount = count) }
    }

    fun refreshHardwareStatus() {
        val localIp = hotspotLanTransport.detectLocalIpv4Address()
        _radioState.update {
            it.copy(
                bluetoothSupported = bluetoothTransport.isSupported(),
                bluetoothEnabled = bluetoothTransport.isEnabled(),
                wifiEnabled = hotspotLanTransport.isEnabled(),
                wifiDirectSupported = wifiDirectTransport.isSupported(),
                hasBluetoothPermissions = bluetoothTransport.hasPermissions(),
                hasWifiNearbyPermissions = wifiDirectTransport.hasPermissions()
            )
        }
        _networkStats.update {
            it.copy(
                localIpAddress = localIp,
                connectedPeerCount = _connectedPeers.value.size,
                reachableMultiHopCount = router.multiHopPeers.value.size
            )
        }
        bluetoothTransport.startServer()
    }

    fun startUnifiedScan(filter: TransportType = TransportType.AUTO) {
        refreshHardwareStatus()
        when (filter) {
            TransportType.AUTO -> {
                allTransports.forEach { it.startDiscovery() }
            }
            TransportType.WIFI_DIRECT -> wifiDirectTransport.startDiscovery()
            TransportType.HOTSPOT -> hotspotLanTransport.startDiscovery()
            TransportType.BLUETOOTH -> bluetoothTransport.startDiscovery()
        }
        // Auto-stop intensive radio discovery after 45s to preserve battery
        scanTimeoutJob?.cancel()
        scanTimeoutJob = scope.launch {
            delay(SCAN_AUTO_TIMEOUT_MS)
            if (!_networkStats.value.isHostingHotspot) {
                stopAllScans()
            } else {
                bluetoothTransport.stopDiscovery()
                wifiDirectTransport.stopDiscovery()
            }
        }
    }

    fun stopAllScans() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        allTransports.forEach { it.stopDiscovery() }
    }

    fun selectTransportMode(mode: TransportType) {
        _networkStats.update {
            it.copy(
                activeTransport = if (mode == TransportType.AUTO) determineBestAvailableTransport() else mode
            )
        }
    }

    fun connectToPeer(peer: PeerNode) {
        if (peer.hopCount > 1) {
            scope.launch {
                _statusMessages.emit("Reachable via ${peer.hopCount}-hop mesh route (${peer.viaPeerId})")
            }
            return
        }
        if (sessions.values.any { it.peerId == peer.peerId && it.transport == peer.transport && !it.closed.get() }) {
            scope.launch { _statusMessages.emit("Already connected to ${peer.displayName}") }
            return
        }
        updatePeerConnectingState(peer.peerId, connecting = true)
        when (peer.transport) {
            TransportType.BLUETOOTH -> {
                val addr = peer.bluetoothAddress ?: run {
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    return
                }
                bluetoothTransport.connectToPeer(addr) { result ->
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    result.onFailure { err ->
                        scope.launch { _statusMessages.emit(err.message ?: "Bluetooth connection failed") }
                    }
                }
            }
            TransportType.WIFI_DIRECT -> {
                val addr = peer.wifiP2pAddress ?: run {
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    return
                }
                wifiDirectTransport.connectToPeer(addr) { result ->
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    result.onFailure { err ->
                        scope.launch { _statusMessages.emit(err.message ?: "Wi-Fi Direct connection failed") }
                    }
                }
            }
            TransportType.HOTSPOT, TransportType.AUTO -> {
                val ip = peer.ipAddress ?: run {
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    return
                }
                hotspotLanTransport.connectByIp(ip, peer.port) { result ->
                    updatePeerConnectingState(peer.peerId, connecting = false)
                    result.onFailure { err ->
                        scope.launch { _statusMessages.emit(err.message ?: "Hotspot socket connection failed") }
                    }
                }
            }
        }
    }

    fun connectByManualIp(ip: String, port: Int = HotspotLanTransport.TCP_PORT) {
        hotspotLanTransport.connectByIp(ip, port) { result ->
            scope.launch {
                result.onSuccess {
                    _statusMessages.emit("Connected to $ip:$port")
                }.onFailure { err ->
                    _statusMessages.emit("Could not reach $ip:$port — ${err.message ?: "Timeout"}")
                }
            }
        }
    }

    fun toggleLocalHotspot() {
        if (_networkStats.value.isHostingHotspot) {
            hotspotLanTransport.stopLocalHotspot()
            scope.launch { _statusMessages.emit("Local Hotspot stopped") }
        } else {
            hotspotLanTransport.startLocalHotspot { result ->
                scope.launch {
                    result.onSuccess { (ssid, _) ->
                        _statusMessages.emit("Hotspot active: $ssid")
                    }.onFailure { err ->
                        _statusMessages.emit(err.message ?: "Could not start local hotspot")
                    }
                }
            }
        }
    }

    fun toggleWifiDirectGroup() {
        if (_networkStats.value.isHostingWifiDirectGroup) {
            wifiDirectTransport.removeGroup()
            scope.launch { _statusMessages.emit("Wi-Fi Direct group closed") }
        } else {
            wifiDirectTransport.createGroup { result ->
                scope.launch {
                    result.onSuccess {
                        _statusMessages.emit("Wi-Fi Direct group hosted")
                    }.onFailure { err ->
                        _statusMessages.emit(err.message ?: "Failed to host P2P group")
                    }
                }
            }
        }
    }

    fun disconnectAllPeers() {
        sessions.values.forEach { it.shutdown() }
        sessions.clear()
        _connectedPeers.value = emptyList()
        _discoveredPeers.update { list -> list.map { it.copy(isConnected = false, isConnecting = false) } }
        router.onDirectNeighborsChanged(currentProfile.nodeId, emptyList())
        _networkStats.update { it.copy(connectedPeerCount = 0, reachableMultiHopCount = 0) }
    }

    fun isPeerReachable(peerId: String): Boolean {
        if (sessions.values.any { it.peerId == peerId && !it.closed.get() }) return true
        return router.resolveNextHop(peerId, currentProfile.meshMode) != null
    }

    /**
     * Unified mesh-aware packet dispatcher backed by per-session ordered writer queues.
     * Returns true if at least one direct session or multi-hop route accepted the packet.
     */
    fun sendEnvelope(envelope: WireEnvelope, targetPeerIds: List<String> = emptyList()): Boolean {
        router.markPacketSeenIfNew(envelope.packetId)
        val tracedEnvelope = if (envelope.routeTraceCsv.isBlank()) {
            envelope.copy(routeTraceCsv = currentProfile.nodeId)
        } else {
            envelope
        }

        val targetSessions = mutableListOf<ActiveSession>()
        if (targetPeerIds.isEmpty()) {
            sessions.values
                .filter { !it.closed.get() }
                .groupBy { it.peerId }
                .values
                .forEach { peerSessions ->
                    pickPreferredSession(peerSessions)?.let { targetSessions.add(it) }
                }
        } else {
            targetPeerIds.forEach { destId ->
                val direct = sessions.values.filter { it.peerId == destId && !it.closed.get() }
                if (direct.isNotEmpty()) {
                    pickPreferredSession(direct)?.let { targetSessions.add(it) }
                } else {
                    val route = router.resolveNextHop(destId, currentProfile.meshMode)
                    if (route != null) {
                        val nextHopSessions = sessions.values.filter {
                            it.peerId == route.nextHopPeerId && !it.closed.get()
                        }
                        pickPreferredSession(nextHopSessions)?.let { session ->
                            targetSessions.add(session)
                            router.recordActivePath(route.path, session.transport)
                        }
                    }
                }
            }
        }

        if (targetSessions.isEmpty()) return false

        var anyAccepted = false
        val distinctSessions = targetSessions.distinctBy { it.sessionId }
        distinctSessions.forEach { session ->
            val wired = tracedEnvelope.copy(transportName = session.transport.name)
            if (session.enqueue(wired)) {
                anyAccepted = true
                if (targetPeerIds.isEmpty() && wired.type != PacketType.PING.name) {
                    router.recordActivePath(listOf(currentProfile.nodeId, session.peerId), session.transport)
                }
            }
        }
        return anyAccepted
    }

    fun sendUnicastToPeer(peerId: String, envelope: WireEnvelope): Boolean {
        return sendEnvelope(
            envelope.copy(targetId = peerId),
            targetPeerIds = listOf(peerId)
        )
    }

    private fun broadcastRouteAdvertisement() {
        val neighbors = _connectedPeers.value.map {
            RouteNeighborAdvert(
                peerId = it.peerId,
                displayName = it.displayName,
                transportName = it.transport.name,
                bandwidthMbps = it.bandwidthMbps,
                avatarIndex = it.avatarIndex
            )
        }
        val advert = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.ROUTE_ADVERT.name,
            senderId = currentProfile.nodeId,
            senderName = currentProfile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            senderAvatarIndex = currentProfile.avatarIndex,
            maxHops = 4,
            neighbors = neighbors
        )
        sendEnvelope(advert, emptyList())
    }

    private fun attachSession(
        transport: TransportType,
        remoteHint: String?,
        rawInputStream: InputStream,
        rawOutputStream: OutputStream,
        closeAction: () -> Unit
    ) {
        val inputStream = BufferedInputStream(rawInputStream, 64 * 1024)
        val outputStream = BufferedOutputStream(rawOutputStream, 64 * 1024)
        val sessionId = UUID.randomUUID().toString()
        val tempPeerId = remoteHint ?: sessionId.take(8)
        val session = ActiveSession(
            sessionId = sessionId,
            peerId = tempPeerId,
            transport = transport,
            remoteHint = remoteHint,
            outputStream = outputStream,
            closeAction = closeAction
        )
        sessions[sessionId] = session

        // Dedicated single writer coroutine per session: prioritizes control/chat packets over realtime frames
        session.writerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !session.closed.get()) {
                    val nextEnvelope = session.controlQueue.tryReceive().getOrNull()
                        ?: select {
                            session.controlQueue.onReceiveCatching { it.getOrNull() }
                            session.realtimeQueue.onReceiveCatching { it.getOrNull() }
                        } ?: break
                    val byteCount = SocketFrameCodec.writeFrame(session.outputStream, nextEnvelope)
                    _networkStats.update {
                        it.copy(
                            bytesSent = it.bytesSent + byteCount,
                            packetsSent = it.packetsSent + 1
                        )
                    }
                }
            } catch (_: Exception) {
                closeSession(session.sessionId)
            }
        }

        // Send initial handshake via session's writer queue
        session.enqueue(
            WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.HANDSHAKE.name,
                senderId = currentProfile.nodeId,
                senderName = currentProfile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                senderAvatarIndex = currentProfile.avatarIndex,
                senderStatus = currentProfile.statusMessage,
                transportName = transport.name
            )
        )

        // Reader loop
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !session.closed.get()) {
                    val frame = SocketFrameCodec.readFrame(inputStream) ?: break
                    val (envelope, byteCount) = frame
                    _networkStats.update {
                        it.copy(
                            bytesReceived = it.bytesReceived + byteCount,
                            packetsReceived = it.packetsReceived + 1
                        )
                    }
                    handleIncomingEnvelope(session, envelope)
                }
            } catch (_: Exception) {
                // Socket disconnected
            } finally {
                closeSession(sessionId)
            }
        }
    }

    private suspend fun handleIncomingEnvelope(session: ActiveSession, envelope: WireEnvelope) {
        val isLinkControl = envelope.type == PacketType.HANDSHAKE.name ||
            envelope.type == PacketType.PING.name ||
            envelope.type == PacketType.PONG.name

        if (!isLinkControl && !router.markPacketSeenIfNew(envelope.packetId)) {
            return
        }

        if (!isLinkControl && envelope.senderId == currentProfile.nodeId) {
            return
        }

        when (envelope.type) {
            PacketType.HANDSHAKE.name -> {
                if (envelope.senderId == currentProfile.nodeId) {
                    closeSession(session.sessionId)
                    return
                }
                // Deduplicate any stale session from the same peer on the same transport
                sessions.values
                    .filter {
                        it.sessionId != session.sessionId &&
                            it.peerId == envelope.senderId &&
                            it.transport == session.transport
                    }
                    .forEach { old -> closeSession(old.sessionId) }

                session.peerId = envelope.senderId
                val node = PeerNode(
                    peerId = envelope.senderId,
                    displayName = envelope.senderName,
                    deviceName = envelope.senderDevice,
                    statusText = envelope.senderStatus,
                    avatarIndex = envelope.senderAvatarIndex,
                    transport = session.transport,
                    isConnected = true,
                    hopCount = 1,
                    signalStrength = if (session.transport == TransportType.BLUETOOTH) 78 else 94,
                    estimatedDistanceMeters = if (session.transport == TransportType.BLUETOOTH) 3.2f else 5.0f,
                    bandwidthMbps = session.transport.typicalSpeedMbps,
                    ipAddress = if (session.transport != TransportType.BLUETOOTH) session.remoteHint else null,
                    bluetoothAddress = if (session.transport == TransportType.BLUETOOTH) session.remoteHint else null
                )
                upsertConnectedPeer(node)
                recomputeOptimalTransport()
                router.onDirectNeighborsChanged(currentProfile.nodeId, _connectedPeers.value)
                topologyDirty = true
                if (currentProfile.meshMode != MeshMode.DIRECT_ONLY) {
                    lastRouteAdvertAtMs = System.currentTimeMillis()
                    broadcastRouteAdvertisement()
                }
                _routeFlushTrigger.emit(setOf(envelope.senderId) + router.routes.value.keys)
            }
            PacketType.ROUTE_ADVERT.name -> {
                val nbrs = envelope.neighbors.orEmpty()
                router.onRouteAdvertReceived(
                    localNodeId = currentProfile.nodeId,
                    fromNeighborId = session.peerId,
                    advertisedBy = envelope.senderId,
                    neighbors = nbrs,
                    directPeers = _connectedPeers.value
                )
                _networkStats.update {
                    it.copy(reachableMultiHopCount = router.multiHopPeers.value.size)
                }
                _routeFlushTrigger.emit(router.routes.value.keys)
                forwardIfApplicable(session, envelope)
            }
            PacketType.PING.name -> {
                val pong = envelope.copy(
                    packetId = UUID.randomUUID().toString(),
                    type = PacketType.PONG.name,
                    senderId = currentProfile.nodeId,
                    senderName = currentProfile.nickname
                )
                session.enqueue(pong)
            }
            PacketType.PONG.name -> {
                val sentAt = pendingPings.remove(envelope.packetId)
                if (sentAt != null) {
                    val rtt = (System.currentTimeMillis() - sentAt).toInt().coerceAtLeast(1)
                    _networkStats.update { it.copy(avgLatencyMs = rtt) }
                }
            }
            PacketType.TYPING_INDICATOR.name -> {
                _typingPeers.update { map ->
                    val mutable = map.toMutableMap()
                    if (envelope.isTyping) mutable[envelope.conversationId] = envelope.senderName
                    else mutable.remove(envelope.conversationId)
                    mutable
                }
                if (envelope.isTyping) {
                    scope.launch {
                        delay(3500L)
                        _typingPeers.update { it - envelope.conversationId }
                    }
                }
            }
            PacketType.AUDIO_FRAME.name,
            PacketType.VIDEO_FRAME.name,
            PacketType.PTT_AUDIO_FRAME.name -> {
                val isForMe = envelope.targetId.isBlank() || envelope.targetId == currentProfile.nodeId
                if (isForMe) {
                    _realtimeMediaPackets.emit(envelope to session.transport)
                }
                val allowVideoRelay = envelope.type != PacketType.VIDEO_FRAME.name ||
                    (envelope.hopCount < 1 && session.transport != TransportType.BLUETOOTH)
                val allowPttRelay = envelope.type != PacketType.PTT_AUDIO_FRAME.name || currentProfile.pttMeshForwarding
                if (allowVideoRelay && allowPttRelay && (envelope.targetId.isBlank() || envelope.targetId != currentProfile.nodeId)) {
                    forwardIfApplicable(session, envelope)
                }
            }
            else -> {
                val isForMe = envelope.targetId.isBlank() || envelope.targetId == currentProfile.nodeId
                if (isForMe) {
                    val traceList = (envelope.routeTraceCsv.split(",").filter { it.isNotBlank() } + currentProfile.nodeId)
                    router.recordActivePath(traceList, session.transport)

                    val isAckable = envelope.type == PacketType.TEXT_MESSAGE.name ||
                        envelope.type == PacketType.VOICE_MESSAGE.name ||
                        envelope.type == PacketType.IMAGE_TRANSFER.name ||
                        envelope.type == PacketType.FILE_TRANSFER.name

                    if (isAckable) {
                        val ack = WireEnvelope(
                            packetId = UUID.randomUUID().toString(),
                            type = PacketType.DELIVERY_ACK.name,
                            senderId = currentProfile.nodeId,
                            senderName = currentProfile.nickname,
                            senderDevice = Build.MODEL ?: "Android",
                            targetId = envelope.senderId,
                            conversationId = envelope.conversationId,
                            messageId = envelope.messageId,
                            routeTraceCsv = traceList.joinToString(" → ")
                        )
                        sendUnicastToPeer(envelope.senderId, ack)
                    }
                    _incomingPackets.emit(envelope to session.transport)
                }
                if (envelope.targetId.isBlank() || envelope.targetId != currentProfile.nodeId) {
                    forwardIfApplicable(session, envelope)
                }
            }
        }
    }

    private fun forwardIfApplicable(incomingSession: ActiveSession, envelope: WireEnvelope) {
        if (currentProfile.meshMode == MeshMode.DIRECT_ONLY) return
        val nextHopCount = envelope.hopCount + 1
        if (nextHopCount > envelope.maxHops) return

        // Guard against forwarding loops if localNodeId already appears in routeTraceCsv
        val priorHops = envelope.routeTraceCsv.split(",").filter { it.isNotBlank() }
        if (currentProfile.nodeId in priorHops) return

        val updatedTrace = (priorHops + currentProfile.nodeId).joinToString(",")
        val forwarded = envelope.copy(
            hopCount = nextHopCount,
            routeTraceCsv = updatedTrace
        )

        val targetSessions: List<ActiveSession> = if (envelope.targetId.isNotBlank()) {
            val route = router.resolveNextHop(envelope.targetId, currentProfile.meshMode) ?: return
            if (route.nextHopPeerId == incomingSession.peerId) return
            sessions.values.filter { it.peerId == route.nextHopPeerId && !it.closed.get() }.take(1)
        } else {
            sessions.values
                .filter { !it.closed.get() && it.peerId != incomingSession.peerId && it.peerId != envelope.senderId }
                .groupBy { it.peerId }
                .values
                .mapNotNull { pickPreferredSession(it) }
        }

        if (targetSessions.isEmpty()) return

        targetSessions.forEach { outSession ->
            if (outSession.enqueue(forwarded)) {
                _networkStats.update { it.copy(packetsRelayed = it.packetsRelayed + 1) }
                router.recordActivePath(
                    listOf(incomingSession.peerId, currentProfile.nodeId, outSession.peerId),
                    outSession.transport
                )
            }
        }
    }

    private fun sendPingToAll() {
        sessions.values.filter { !it.closed.get() }.forEach { session ->
            val pingId = UUID.randomUUID().toString()
            pendingPings[pingId] = System.currentTimeMillis()
            val ping = WireEnvelope(
                packetId = pingId,
                type = PacketType.PING.name,
                senderId = currentProfile.nodeId,
                senderName = currentProfile.nickname,
                senderDevice = Build.MODEL ?: "Android"
            )
            session.enqueue(ping)
        }
    }

    private fun closeSession(sessionId: String) {
        val removed = sessions.remove(sessionId) ?: return
        removed.shutdown()
        val stillConnected = sessions.values.any { it.peerId == removed.peerId && !it.closed.get() }
        if (!stillConnected) {
            _connectedPeers.update { list -> list.filterNot { it.peerId == removed.peerId } }
            _discoveredPeers.update { list ->
                list.map { if (it.peerId == removed.peerId) it.copy(isConnected = false) else it }
            }
            router.onPeerDisconnected(currentProfile.nodeId, removed.peerId, _connectedPeers.value)
            topologyDirty = true
            recomputeOptimalTransport()
        }
    }

    private fun pickPreferredSession(candidates: List<ActiveSession>): ActiveSession? {
        if (candidates.isEmpty()) return null
        val pref = currentProfile.preferredTransport
        if (!currentProfile.autoSelectTransport && pref != TransportType.AUTO) {
            candidates.firstOrNull { it.transport == pref }?.let { return it }
        }
        return candidates.minByOrNull { transportPriority(it.transport) }
    }

    private fun transportPriority(t: TransportType): Int = when (t) {
        TransportType.WIFI_DIRECT -> 0
        TransportType.HOTSPOT -> 1
        TransportType.BLUETOOTH -> 2
        TransportType.AUTO -> 3
    }

    private fun determineBestAvailableTransport(): TransportType {
        val connected = _connectedPeers.value
        if (connected.isNotEmpty()) {
            return connected.minByOrNull { transportPriority(it.transport) }?.transport
                ?: TransportType.WIFI_DIRECT
        }
        val radio = _radioState.value
        return when {
            radio.wifiDirectSupported && radio.wifiEnabled -> TransportType.WIFI_DIRECT
            radio.wifiEnabled -> TransportType.HOTSPOT
            radio.bluetoothSupported && radio.bluetoothEnabled -> TransportType.BLUETOOTH
            else -> TransportType.WIFI_DIRECT
        }
    }

    private fun recomputeOptimalTransport() {
        val best = if (currentProfile.autoSelectTransport || currentProfile.preferredTransport == TransportType.AUTO) {
            determineBestAvailableTransport()
        } else {
            currentProfile.preferredTransport
        }
        _networkStats.update {
            it.copy(
                activeTransport = best,
                connectedPeerCount = _connectedPeers.value.size,
                reachableMultiHopCount = router.multiHopPeers.value.size
            )
        }
    }

    private fun upsertDiscoveredPeer(peer: PeerNode) {
        val isLinked = _connectedPeers.value.any { it.peerId == peer.peerId }
        val updated = peer.copy(isConnected = isLinked)
        _discoveredPeers.update { list ->
            val filtered = list.filterNot {
                it.peerId == peer.peerId ||
                    (peer.ipAddress != null && it.ipAddress == peer.ipAddress) ||
                    (peer.bluetoothAddress != null && it.bluetoothAddress == peer.bluetoothAddress)
            }
            (listOf(updated) + filtered).sortedByDescending { it.isConnected || it.signalStrength > 80 }
        }
    }

    private fun upsertConnectedPeer(peer: PeerNode) {
        _connectedPeers.update { list ->
            list.filterNot { it.peerId == peer.peerId } + peer
        }
        upsertDiscoveredPeer(peer)
    }

    private fun updatePeerConnectingState(peerId: String, connecting: Boolean) {
        _discoveredPeers.update { list ->
            list.map { if (it.peerId == peerId) it.copy(isConnecting = connecting) else it }
        }
    }

    private fun registerNetworkCallback() {
        val cm = connectivityManager ?: return
        if (networkCallbackRegistered) return
        runCatching {
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(req, networkCallback)
            networkCallbackRegistered = true
        }
    }

    fun release() {
        scanTimeoutJob?.cancel()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        disconnectAllPeers()
        allTransports.forEach { it.release() }
        scope.cancel()
    }
}
