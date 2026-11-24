package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

@JsonClass(generateAdapter = true)
data class LanBeacon(
    val nodeId: String,
    val nickname: String,
    val deviceName: String,
    val statusText: String,
    val avatarIndex: Int,
    val tcpPort: Int,
    val isHotspotHost: Boolean
)

class HotspotLanTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val localBeaconProvider: () -> LanBeacon,
    private val onPeerDiscovered: TransportPeerCallback,
    private val onSocketConnected: TransportSocketCallback,
    private val onHotspotStateChanged: (isHosting: Boolean, ssid: String?, passphrase: String?) -> Unit,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onRadioStateChanged: () -> Unit = {}
) : NexusTransport {
    companion object {
        const val TCP_PORT = 8989
        const val UDP_BEACON_PORT = 8990
        private const val ACTIVE_BEACON_INTERVAL_MS = 2_500L
        private const val BACKGROUND_BEACON_INTERVAL_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 4_500
    }

    override val transportType: TransportType = TransportType.HOTSPOT

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val moshi = Moshi.Builder().build()
    private val beaconAdapter = moshi.adapter(LanBeacon::class.java)

    private val serverLock = Any()
    private val inFlightConnections = ConcurrentHashMap.newKeySet<String>()
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var beaconListenerJob: Job? = null
    private var beaconBroadcasterJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var localOnlyHotspotReservation: Any? = null
    private var receiverRegistered = false
    @Volatile
    private var inForegroundMode = true

    private val wifiStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                val state = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                if (state == WifiManager.WIFI_STATE_ENABLED) {
                    startServer()
                }
                onRadioStateChanged()
            }
        }
    }

    override fun isSupported(): Boolean = true

    override fun isEnabled(): Boolean = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

    override fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun setForegroundMode(foreground: Boolean) {
        inForegroundMode = foreground
    }

    fun detectLocalIpv4Address(): String {
        val candidates = mutableListOf<Pair<String, String>>()
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                val addrs = intf.inetAddresses.toList()
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(intf.name to host)
                    }
                }
            }
        }
        val preferred = candidates.firstOrNull { (name, ip) ->
            name.startsWith("ap") || name.startsWith("wlan") || ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
        } ?: candidates.firstOrNull()
        return preferred?.second ?: "127.0.0.1"
    }

    override fun startServer() {
        registerReceiverIfNeeded()
        synchronized(serverLock) {
            if (serverJob?.isActive == true) return
            serverJob = scope.launch(Dispatchers.IO) {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(TCP_PORT))
                    }
                    while (isActive) {
                        val client = try {
                            serverSocket?.accept()
                        } catch (_: IOException) {
                            break
                        } ?: break
                        runCatching { client.tcpNoDelay = true }
                        onSocketConnected.onSocketConnected(
                            TransportType.HOTSPOT,
                            client.getInputStream(),
                            client.getOutputStream(),
                            client.inetAddress?.hostAddress
                        ) { runCatching { client.close() } }
                    }
                } catch (_: IOException) {
                    // Port already bound or closed
                } finally {
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                }
            }
        }
    }

    override fun startDiscovery(): Boolean {
        startServer()
        if (beaconListenerJob?.isActive == true) return true
        onScanningChanged(true)

        beaconListenerJob = scope.launch(Dispatchers.IO) {
            acquireMulticastLock()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_BEACON_PORT))
                }
                val buffer = ByteArray(2048)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val senderIp = packet.address?.hostAddress ?: continue
                    val localIp = detectLocalIpv4Address()
                    if (senderIp == localIp || senderIp == "127.0.0.1") continue

                    val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val beacon = runCatching { beaconAdapter.fromJson(json) }.getOrNull() ?: continue
                    val localNodeId = localBeaconProvider().nodeId
                    if (beacon.nodeId == localNodeId) continue

                    onPeerDiscovered.onPeerDiscovered(
                        PeerNode(
                            peerId = beacon.nodeId,
                            displayName = beacon.nickname,
                            deviceName = beacon.deviceName,
                            statusText = beacon.statusText,
                            avatarIndex = beacon.avatarIndex,
                            transport = TransportType.HOTSPOT,
                            isHost = beacon.isHotspotHost,
                            signalStrength = 88,
                            estimatedDistanceMeters = 6.0f,
                            bandwidthMbps = TransportType.HOTSPOT.typicalSpeedMbps,
                            ipAddress = senderIp,
                            port = beacon.tcpPort
                        )
                    )
                }
            } catch (_: Exception) {
                // Socket closed
            } finally {
                runCatching { socket?.close() }
                releaseMulticastLock()
            }
        }

        beaconBroadcasterJob = scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply { broadcast = true }
                while (isActive) {
                    val beacon = localBeaconProvider()
                    val payload = beaconAdapter.toJson(beacon).toByteArray(Charsets.UTF_8)
                    val targets = buildBroadcastTargets()
                    for (target in targets) {
                        runCatching {
                            socket.send(DatagramPacket(payload, payload.size, target, UDP_BEACON_PORT))
                        }
                    }
                    delay(if (inForegroundMode) ACTIVE_BEACON_INTERVAL_MS else BACKGROUND_BEACON_INTERVAL_MS)
                }
            } catch (_: Exception) {
                // Stopped
            } finally {
                runCatching { socket?.close() }
            }
        }
        return true
    }

    override fun stopDiscovery() {
        beaconListenerJob?.cancel()
        beaconBroadcasterJob?.cancel()
        beaconListenerJob = null
        beaconBroadcasterJob = null
        onScanningChanged(false)
    }

    fun connectByIp(ipAddress: String, port: Int = TCP_PORT, onResult: (Result<Unit>) -> Unit) {
        val cleanIp = ipAddress.trim()
        val key = "$cleanIp:$port"
        if (!inFlightConnections.add(key)) {
            onResult(Result.failure(IllegalStateException("Connection to $key already in progress")))
            return
        }
        scope.launch(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket().apply { tcpNoDelay = true }
                socket.connect(InetSocketAddress(cleanIp, port), CONNECT_TIMEOUT_MS)
                onSocketConnected.onSocketConnected(
                    TransportType.HOTSPOT,
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    cleanIp
                ) { runCatching { socket.close() } }
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                runCatching { socket?.close() }
                onResult(Result.failure(e))
            } finally {
                inFlightConnections.remove(key)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocalHotspot(onResult: (Result<Pair<String, String>>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || wifiManager == null) {
            onResult(Result.failure(UnsupportedOperationException("Local-only hotspot requires Android 8.0+")))
            return
        }
        if (!hasPermissions()) {
            onResult(Result.failure(SecurityException("Nearby Wi-Fi / Location permission required to host hotspot")))
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                    localOnlyHotspotReservation = reservation
                    val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.ssid ?: "NEXUS-Hotspot"
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.SSID ?: "NEXUS-Hotspot"
                    }
                    val pass = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reservation?.softApConfiguration?.passphrase ?: "Check Device Settings"
                    } else {
                        @Suppress("DEPRECATION")
                        reservation?.wifiConfiguration?.preSharedKey ?: "Check Device Settings"
                    }
                    onHotspotStateChanged(true, ssid, pass)
                    startDiscovery()
                    onResult(Result.success(ssid to pass))
                }

                override fun onStopped() {
                    localOnlyHotspotReservation = null
                    onHotspotStateChanged(false, null, null)
                }

                override fun onFailed(reason: Int) {
                    onHotspotStateChanged(false, null, null)
                    onResult(Result.failure(IllegalStateException("Hotspot failed to start (code $reason). You can enable Mobile Hotspot in Android Settings.")))
                }
            }, null)
        } catch (e: Exception) {
            onResult(Result.failure(e))
        }
    }

    fun stopLocalHotspot() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                (localOnlyHotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close()
            }
        }
        localOnlyHotspotReservation = null
        onHotspotStateChanged(false, null, null)
    }

    private fun buildBroadcastTargets(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        runCatching { list.add(InetAddress.getByName("255.255.255.255")) }
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ifaceAddr in intf.interfaceAddresses) {
                    val bcast = ifaceAddr.broadcast
                    if (bcast != null && bcast !in list) {
                        list.add(bcast)
                    }
                }
            }
        }
        return list
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("NEXUS_MESH_LOCK")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        ContextCompat.registerReceiver(
            context,
            wifiStateReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    override fun release() {
        stopDiscovery()
        stopLocalHotspot()
        synchronized(serverLock) {
            serverJob?.cancel()
            serverJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(wifiStateReceiver) }
            receiverRegistered = false
        }
    }
}
