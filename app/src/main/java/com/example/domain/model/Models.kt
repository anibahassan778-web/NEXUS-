package com.example.domain.model

enum class TransportType(
    val displayName: String,
    val shortLabel: String,
    val typicalSpeedMbps: Float
) {
    AUTO("Auto Optimal", "AUTO", 0f),
    WIFI_DIRECT("Wi-Fi Direct", "P2P", 140f),
    HOTSPOT("Local Hotspot", "HOTSPOT", 85f),
    BLUETOOTH("Bluetooth RFCOMM", "BT", 2.1f)
}

enum class MeshMode(val label: String, val description: String) {
    DIRECT_ONLY("Direct Connection", "Single-hop peer-to-peer links only"),
    RELAY_MODE("Relay Mode", "Forwards packets & files for nearby nodes"),
    MESH_MODE("Mesh Mode", "Full multi-hop route discovery & dynamic recovery"),
    STORE_AND_FORWARD("Store-and-Forward", "Buffers offline packets until destination is reachable")
}

enum class MessageType {
    TEXT,
    VOICE,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    SYSTEM
}

enum class DeliveryStatus {
    QUEUED,
    SENDING,
    SENT,
    RELAYED,
    DELIVERED,
    FAILED
}

enum class TransferState {
    COMPLETED,
    IN_PROGRESS,
    QUEUED,
    CANCELLED,
    FAILED
}

enum class NexusThemeMode(val label: String) {
    CYBER_CHARCOAL("Cyber Charcoal"),
    ABYSSAL_OLED("Abyssal OLED"),
    ARCTIC_LIGHT("Arctic Light")
}

enum class CallType {
    VOICE,
    VIDEO
}

enum class CallDirection {
    INCOMING,
    OUTGOING,
    MISSED
}

enum class CallStatus {
    IDLE,
    RINGING_OUTGOING,
    RINGING_INCOMING,
    ACTIVE
}

enum class VideoQualityProfile(
    val label: String,
    val width: Int,
    val height: Int,
    val jpegQuality: Int,
    val maxFps: Int
) {
    HD_LOCAL("Adaptive High", 480, 360, 65, 14),
    BALANCED("Balanced Mesh", 320, 240, 48, 10),
    LOW_BANDWIDTH("Low Bandwidth", 192, 144, 32, 6)
}

data class PeerNode(
    val peerId: String,
    val displayName: String,
    val deviceName: String,
    val statusText: String = "Available on NEXUS",
    val avatarIndex: Int = 0,
    val transport: TransportType = TransportType.WIFI_DIRECT,
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isHost: Boolean = false,
    val isRelayCapable: Boolean = true,
    val hopCount: Int = 1,
    val viaPeerId: String? = null,
    val signalStrength: Int = 80,
    val estimatedDistanceMeters: Float? = null,
    val bandwidthMbps: Float = 85f,
    val ipAddress: String? = null,
    val port: Int = 8989,
    val bluetoothAddress: String? = null,
    val wifiP2pAddress: String? = null,
    val lastSeenMs: Long = System.currentTimeMillis()
)

data class MeshRoute(
    val destinationId: String,
    val destinationName: String,
    val nextHopPeerId: String,
    val hopCount: Int,
    val path: List<String>,
    val transport: TransportType,
    val bandwidthMbps: Float,
    val expiresAtMs: Long
)

data class ActiveMeshPath(
    val id: String,
    val hops: List<String>,
    val transport: TransportType,
    val timestamp: Long = System.currentTimeMillis()
)

data class RadioHardwareState(
    val bluetoothSupported: Boolean = false,
    val bluetoothEnabled: Boolean = false,
    val wifiEnabled: Boolean = false,
    val wifiDirectSupported: Boolean = false,
    val isBluetoothScanning: Boolean = false,
    val isWifiDirectScanning: Boolean = false,
    val isLanScanning: Boolean = false,
    val hasBluetoothPermissions: Boolean = false,
    val hasWifiNearbyPermissions: Boolean = false
) {
    val isAnyScanning: Boolean
        get() = isBluetoothScanning || isWifiDirectScanning || isLanScanning
}

data class NetworkStats(
    val activeTransport: TransportType = TransportType.WIFI_DIRECT,
    val meshMode: MeshMode = MeshMode.MESH_MODE,
    val localIpAddress: String = "127.0.0.1",
    val localPort: Int = 8989,
    val isHostingHotspot: Boolean = false,
    val hotspotSsid: String? = null,
    val hotspotPassphrase: String? = null,
    val isHostingWifiDirectGroup: Boolean = false,
    val wifiDirectGroupName: String? = null,
    val hostDisplayName: String? = null,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val packetsSent: Int = 0,
    val packetsReceived: Int = 0,
    val packetsRelayed: Int = 0,
    val queuedRelayCount: Int = 0,
    val avgLatencyMs: Int = 0,
    val connectedPeerCount: Int = 0,
    val reachableMultiHopCount: Int = 0
)

data class UserProfile(
    val nodeId: String = "NX-0000",
    val nickname: String = "Node Operator",
    val statusMessage: String = "Offline & Ready",
    val avatarIndex: Int = 0,
    val preferredTransport: TransportType = TransportType.AUTO,
    val autoSelectTransport: Boolean = true,
    val meshMode: MeshMode = MeshMode.MESH_MODE,
    val lowBandwidthMode: Boolean = false,
    val pttMeshForwarding: Boolean = true,
    val themeMode: NexusThemeMode = NexusThemeMode.CYBER_CHARCOAL,
    val voiceCodecPreset: String = "PCM 16kHz Adaptive (Offline)"
)

data class CallParticipant(
    val peerId: String,
    val displayName: String,
    val avatarIndex: Int = 0,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = false,
    val isSpeaking: Boolean = false,
    val transport: TransportType = TransportType.WIFI_DIRECT,
    val qualityPercent: Int = 92
)

data class ActiveCallState(
    val callId: String = "",
    val conversationId: String = "",
    val callTitle: String = "",
    val callType: CallType = CallType.VOICE,
    val status: CallStatus = CallStatus.IDLE,
    val isGroupCall: Boolean = false,
    val isMicMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val useFrontCamera: Boolean = true,
    val lowBandwidthMode: Boolean = false,
    val videoQuality: VideoQualityProfile = VideoQualityProfile.BALANCED,
    val currentFps: Int = 0,
    val bitrateKbps: Int = 0,
    val startedAtMs: Long = 0L,
    val durationSeconds: Long = 0L,
    val latencyMs: Int = 0,
    val transport: TransportType = TransportType.WIFI_DIRECT,
    val meshNotice: String? = null,
    val participants: List<CallParticipant> = emptyList()
)

data class WalkieTalkieState(
    val activeChannel: String = "CH-01 MESH",
    val availableChannels: List<String> = listOf("CH-01 MESH", "CH-02 FIELD", "CH-03 OPS", "CH-04 RESCUE"),
    val isTransmitting: Boolean = false,
    val isLatched: Boolean = false,
    val activeSpeakerName: String? = null,
    val rxLevel: Float = 0f,
    val txLevel: Float = 0f,
    val lowBandwidthCodec: Boolean = false,
    val meshRelayEnabled: Boolean = true
)

data class TransferItemState(
    val transferId: String,
    val conversationId: String,
    val messageId: String,
    val fileName: String,
    val mimeType: String,
    val totalBytes: Long,
    val transferredBytes: Long,
    val progressPercent: Int,
    val speedBytesPerSec: Long,
    val isOutgoing: Boolean,
    val state: TransferState
)
