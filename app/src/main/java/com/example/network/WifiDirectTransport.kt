package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class WifiDirectTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPeerDiscovered: TransportPeerCallback,
    private val onSocketConnected: TransportSocketCallback,
    private val onGroupStateChanged: (isHost: Boolean, groupName: String?) -> Unit,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onRadioStateChanged: () -> Unit = {}
) : NexusTransport {
    companion object {
        const val P2P_PORT = 8988
        private const val SOCKET_TIMEOUT_MS = 5000
    }

    override val transportType: TransportType = TransportType.WIFI_DIRECT

    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private val serverLock = Any()
    private val inFlightClientHosts = ConcurrentHashMap.newKeySet<String>()
    private var p2pServerSocket: ServerSocket? = null
    private var p2pServerJob: Job? = null
    @Volatile
    private var p2pEnabled = true

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    p2pEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                    if (!p2pEnabled) {
                        onScanningChanged(false)
                        onGroupStateChanged(false, null)
                    }
                    onRadioStateChanged()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (!hasPermissions()) return
                    manager?.requestPeers(channel) { peerList ->
                        peerList.deviceList.forEach { device ->
                            onPeerDiscovered.onPeerDiscovered(device.toPeerNode())
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (!hasPermissions()) return
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info != null && info.groupFormed) {
                            if (info.isGroupOwner) {
                                onGroupStateChanged(true, "NEXUS-P2P-Group")
                                startServer()
                            } else {
                                onGroupStateChanged(false, "Connected to P2P Host")
                                val hostAddr = info.groupOwnerAddress?.hostAddress
                                if (!hostAddr.isNullOrBlank()) {
                                    connectToGroupOwner(hostAddr)
                                }
                            }
                        } else {
                            onGroupStateChanged(false, null)
                        }
                    }
                }
            }
        }
    }

    override fun isSupported(): Boolean = manager != null

    override fun isEnabled(): Boolean = p2pEnabled && manager != null

    override fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun initialize() {
        if (manager != null && channel == null) {
            channel = manager.initialize(context, Looper.getMainLooper()) {
                channel = null
            }
        }
        registerReceiverIfNeeded()
    }

    override fun startServer() {
        initialize()
        synchronized(serverLock) {
            if (p2pServerJob?.isActive == true) return
            p2pServerJob = scope.launch(Dispatchers.IO) {
                try {
                    p2pServerSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(P2P_PORT))
                    }
                    while (isActive) {
                        val client = try {
                            p2pServerSocket?.accept()
                        } catch (_: IOException) {
                            break
                        } ?: break
                        runCatching { client.tcpNoDelay = true }
                        onSocketConnected.onSocketConnected(
                            TransportType.WIFI_DIRECT,
                            client.getInputStream(),
                            client.getOutputStream(),
                            client.inetAddress?.hostAddress
                        ) { runCatching { client.close() } }
                    }
                } catch (_: IOException) {
                    // Socket closed or already bound
                } finally {
                    runCatching { p2pServerSocket?.close() }
                    p2pServerSocket = null
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): Boolean {
        initialize()
        val ch = channel
        if (manager == null || ch == null || !hasPermissions()) {
            onScanningChanged(false)
            return false
        }
        onScanningChanged(true)
        return try {
            manager.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // Peers delivered via WIFI_P2P_PEERS_CHANGED_ACTION
                }

                override fun onFailure(reason: Int) {
                    onScanningChanged(false)
                }
            })
            true
        } catch (_: SecurityException) {
            onScanningChanged(false)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        val ch = channel ?: return
        runCatching { manager?.stopPeerDiscovery(ch, null) }
        onScanningChanged(false)
    }

    @SuppressLint("MissingPermission")
    fun createGroup(onResult: (Result<Unit>) -> Unit) {
        initialize()
        val ch = channel
        if (manager == null || ch == null || !hasPermissions()) {
            onResult(Result.failure(IllegalStateException("Wi-Fi Direct unavailable or missing permission")))
            return
        }
        try {
            manager.createGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onGroupStateChanged(true, "NEXUS-P2P-Host")
                    startServer()
                    onResult(Result.success(Unit))
                }

                override fun onFailure(reason: Int) {
                    onResult(Result.failure(IllegalStateException("Wi-Fi Direct group creation failed ($reason)")))
                }
            })
        } catch (e: SecurityException) {
            onResult(Result.failure(e))
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String, onResult: (Result<Unit>) -> Unit) {
        initialize()
        val ch = channel
        if (manager == null || ch == null || !hasPermissions()) {
            onResult(Result.failure(IllegalStateException("Wi-Fi Direct unavailable or missing permission")))
            return
        }
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
        }
        try {
            manager.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    onResult(Result.success(Unit))
                }

                override fun onFailure(reason: Int) {
                    onResult(Result.failure(IllegalStateException("P2P connect failed ($reason)")))
                }
            })
        } catch (e: SecurityException) {
            onResult(Result.failure(e))
        }
    }

    fun removeGroup() {
        val ch = channel ?: return
        synchronized(serverLock) {
            p2pServerJob?.cancel()
            p2pServerJob = null
            runCatching { p2pServerSocket?.close() }
            p2pServerSocket = null
        }
        runCatching { manager?.removeGroup(ch, null) }
        onGroupStateChanged(false, null)
    }

    private fun connectToGroupOwner(hostAddress: String) {
        if (!inFlightClientHosts.add(hostAddress)) return
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().apply { tcpNoDelay = true }
                socket.connect(InetSocketAddress(hostAddress, P2P_PORT), SOCKET_TIMEOUT_MS)
                onSocketConnected.onSocketConnected(
                    TransportType.WIFI_DIRECT,
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    hostAddress
                ) { runCatching { socket.close() } }
            } catch (_: IOException) {
                runCatching { socket?.close() }
            } finally {
                inFlightClientHosts.remove(hostAddress)
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            p2pReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    override fun release() {
        stopDiscovery()
        synchronized(serverLock) {
            p2pServerJob?.cancel()
            p2pServerJob = null
            runCatching { p2pServerSocket?.close() }
            p2pServerSocket = null
        }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
    }

    private fun WifiP2pDevice.toPeerNode(): PeerNode {
        val statusLabel = when (status) {
            WifiP2pDevice.CONNECTED -> "Wi-Fi Direct • Linked"
            WifiP2pDevice.INVITED -> "Wi-Fi Direct • Invited"
            WifiP2pDevice.AVAILABLE -> "Wi-Fi Direct • Available"
            else -> "Wi-Fi Direct • Standby"
        }
        return PeerNode(
            peerId = "p2p_${deviceAddress}",
            displayName = deviceName.ifBlank { "P2P Node ${deviceAddress.takeLast(5)}" },
            deviceName = deviceName.ifBlank { deviceAddress },
            statusText = statusLabel,
            transport = TransportType.WIFI_DIRECT,
            isConnected = status == WifiP2pDevice.CONNECTED,
            signalStrength = 92,
            estimatedDistanceMeters = 4.5f,
            bandwidthMbps = TransportType.WIFI_DIRECT.typicalSpeedMbps,
            wifiP2pAddress = deviceAddress
        )
    }
}
