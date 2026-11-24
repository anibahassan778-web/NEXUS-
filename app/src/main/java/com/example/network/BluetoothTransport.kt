package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class BluetoothTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onPeerDiscovered: TransportPeerCallback,
    private val onSocketConnected: TransportSocketCallback,
    private val onScanningChanged: (Boolean) -> Unit,
    private val onRadioStateChanged: () -> Unit = {}
) : NexusTransport {
    companion object {
        val NEXUS_RFCOMM_UUID: UUID = UUID.fromString("8a93f1c2-4d3b-4c28-9f15-7e2a0c19d411")
        const val SERVICE_NAME = "NEXUS_MESH"
        private const val CONNECT_TIMEOUT_MS = 10_000L
    }

    override val transportType: TransportType = TransportType.BLUETOOTH

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val serverLock = Any()
    private val inFlightConnections = ConcurrentHashMap.newKeySet<String>()
    private var serverSocket: BluetoothServerSocket? = null
    private var serverJob: Job? = null
    private var receiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        startServer()
                    } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        stopDiscovery()
                        stopServer()
                    }
                    onRadioStateChanged()
                }
                BluetoothDevice.ACTION_FOUND -> {
                    if (!hasPermissions()) return
                    val device = IntentCompat.getParcelableExtra(
                        intent,
                        BluetoothDevice.EXTRA_DEVICE,
                        BluetoothDevice::class.java
                    ) ?: return
                    val rssi = intent.getShortExtra(
                        BluetoothDevice.EXTRA_RSSI,
                        (-68).toShort()
                    ).toInt()
                    val name = runCatching { device.name }.getOrNull()
                        ?.takeIf { it.isNotBlank() } ?: "BT Node ${device.address.takeLast(5)}"
                    val signalPct = rssiToPercent(rssi)
                    val distanceM = estimateDistanceMeters(rssi)
                    onPeerDiscovered.onPeerDiscovered(
                        PeerNode(
                            peerId = "bt_${device.address}",
                            displayName = name,
                            deviceName = name,
                            statusText = "Bluetooth Classic • RFCOMM",
                            transport = TransportType.BLUETOOTH,
                            signalStrength = signalPct,
                            estimatedDistanceMeters = distanceM,
                            bandwidthMbps = TransportType.BLUETOOTH.typicalSpeedMbps,
                            bluetoothAddress = device.address
                        )
                    )
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    onScanningChanged(false)
                }
            }
        }
    }

    override fun isSupported(): Boolean = adapter != null

    override fun isEnabled(): Boolean = runCatching { adapter?.isEnabled == true }.getOrDefault(false)

    override fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    override fun startServer() {
        registerReceiverIfNeeded()
        if (adapter == null || !isEnabled() || !hasPermissions()) return
        synchronized(serverLock) {
            if (serverJob?.isActive == true) return
            serverJob = scope.launch(Dispatchers.IO) {
                try {
                    serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, NEXUS_RFCOMM_UUID)
                    while (isActive) {
                        val socket = try {
                            serverSocket?.accept()
                        } catch (_: IOException) {
                            break
                        } ?: break

                        val remoteAddress = runCatching { socket.remoteDevice?.address }.getOrNull()
                        onSocketConnected.onSocketConnected(
                            TransportType.BLUETOOTH,
                            socket.inputStream,
                            socket.outputStream,
                            remoteAddress
                        ) { runCatching { socket.close() } }
                    }
                } catch (_: SecurityException) {
                    // Permission revoked dynamically
                } catch (_: IOException) {
                    // Adapter disabled or closed
                } finally {
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                }
            }
        }
    }

    private fun stopServer() {
        synchronized(serverLock) {
            serverJob?.cancel()
            serverJob = null
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
    }

    @SuppressLint("MissingPermission")
    override fun startDiscovery(): Boolean {
        val btAdapter = adapter
        if (btAdapter == null || !isEnabled() || !hasPermissions()) {
            onScanningChanged(false)
            return false
        }
        registerReceiverIfNeeded()
        startServer()
        runCatching {
            btAdapter.bondedDevices?.forEach { device ->
                val name = device.name?.takeIf { it.isNotBlank() } ?: device.address
                onPeerDiscovered.onPeerDiscovered(
                    PeerNode(
                        peerId = "bt_${device.address}",
                        displayName = name,
                        deviceName = "$name (Paired)",
                        statusText = "Paired Bluetooth Device",
                        transport = TransportType.BLUETOOTH,
                        signalStrength = 72,
                        estimatedDistanceMeters = 3.5f,
                        bandwidthMbps = TransportType.BLUETOOTH.typicalSpeedMbps,
                        bluetoothAddress = device.address
                    )
                )
            }
        }
        return try {
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            val started = btAdapter.startDiscovery()
            onScanningChanged(started)
            started
        } catch (_: SecurityException) {
            onScanningChanged(false)
            false
        }
    }

    @SuppressLint("MissingPermission")
    override fun stopDiscovery() {
        if (hasPermissions()) {
            runCatching {
                if (adapter?.isDiscovering == true) {
                    adapter.cancelDiscovery()
                }
            }
        }
        onScanningChanged(false)
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(address: String, onResult: (Result<Unit>) -> Unit) {
        if (adapter == null || !isEnabled() || !hasPermissions()) {
            onResult(Result.failure(IllegalStateException("Bluetooth unavailable or missing permissions")))
            return
        }
        if (!inFlightConnections.add(address)) {
            onResult(Result.failure(IllegalStateException("Connection already in progress")))
            return
        }
        scope.launch(Dispatchers.IO) {
            var socket: BluetoothSocket? = null
            try {
                runCatching {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                }
                val device = adapter.getRemoteDevice(address)
                socket = device.createRfcommSocketToServiceRecord(NEXUS_RFCOMM_UUID)
                val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    socket.connect()
                    true
                } ?: false
                if (!connected) {
                    throw IOException("Bluetooth RFCOMM connection timed out")
                }
                onSocketConnected.onSocketConnected(
                    TransportType.BLUETOOTH,
                    socket.inputStream,
                    socket.outputStream,
                    address
                ) { runCatching { socket.close() } }
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                runCatching { socket?.close() }
                onResult(Result.failure(e))
            } finally {
                inFlightConnections.remove(address)
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(
            context,
            discoveryReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
    }

    override fun release() {
        stopDiscovery()
        stopServer()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(discoveryReceiver) }
            receiverRegistered = false
        }
    }

    private fun rssiToPercent(rssi: Int): Int {
        return ((rssi + 100).coerceIn(0, 60) * 100 / 60).coerceIn(10, 100)
    }

    private fun estimateDistanceMeters(rssi: Int, txPower: Int = -59): Float {
        val ratio = (txPower - rssi) / 22.0
        val meters = 10.0.pow(ratio).toFloat()
        return ((meters * 10).toInt() / 10f).coerceIn(0.3f, 35.0f)
    }
}
