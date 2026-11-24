package com.example.network

import com.example.domain.model.PeerNode
import com.example.domain.model.TransportType
import java.io.InputStream
import java.io.OutputStream

/**
 * Unified transport contract shared across Bluetooth RFCOMM, Wi-Fi Direct P2P,
 * and Local Hotspot / LAN socket engines.
 */
interface NexusTransport {
    val transportType: TransportType

    fun isSupported(): Boolean
    fun isEnabled(): Boolean
    fun hasPermissions(): Boolean

    fun startServer()
    fun startDiscovery(): Boolean
    fun stopDiscovery()
    fun release()
}

fun interface TransportSocketCallback {
    fun onSocketConnected(
        transport: TransportType,
        inputStream: InputStream,
        outputStream: OutputStream,
        remoteHint: String?,
        closeAction: () -> Unit
    )
}

fun interface TransportPeerCallback {
    fun onPeerDiscovered(peer: PeerNode)
}
