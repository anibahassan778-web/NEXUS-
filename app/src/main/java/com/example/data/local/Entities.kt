package com.example.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.domain.model.CallDirection
import com.example.domain.model.CallType
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransferState
import com.example.domain.model.TransportType

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["isArchived", "lastMessageTimestamp"]),
        Index(value = ["lastMessageTimestamp"])
    ]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean = false,
    val peerIdsCsv: String = "",
    val lastMessagePreview: String = "",
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val unreadCount: Int = 0,
    val isArchived: Boolean = false,
    val isMuted: Boolean = false,
    val preferredTransport: String = TransportType.WIFI_DIRECT.name,
    val avatarIndex: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["conversationId", "timestamp"]),
        Index(value = ["deliveryStatus"])
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderId: String,
    val senderName: String,
    val isOutgoing: Boolean,
    val type: String = MessageType.TEXT.name,
    val textContent: String = "",
    val mediaPath: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val voiceDurationMs: Long = 0L,
    val waveformCsv: String = "",
    val reactionsCsv: String = "",
    val replyToMessageId: String? = null,
    val replyToSender: String? = null,
    val replyToPreview: String? = null,
    val isForwarded: Boolean = false,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val transferProgress: Int = 100,
    val transferState: String = TransferState.COMPLETED.name,
    val transferSpeedBps: Long = 0L,
    val hopTrace: String = "",
    val deliveryStatus: String = DeliveryStatus.SENT.name,
    val transportUsed: String = TransportType.WIFI_DIRECT.name,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "peer_contacts",
    indices = [Index(value = ["lastConnectedTimestamp"])]
)
data class PeerContactEntity(
    @PrimaryKey val peerId: String,
    val nickname: String,
    val deviceName: String,
    val statusMessage: String,
    val avatarIndex: Int,
    val lastTransport: String,
    val ipAddress: String?,
    val bluetoothAddress: String?,
    val lastConnectedTimestamp: Long
)

@Entity(
    tableName = "received_files",
    indices = [
        Index(value = ["category", "receivedTimestamp"]),
        Index(value = ["receivedTimestamp"])
    ]
)
data class ReceivedFileEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val senderName: String,
    val receivedTimestamp: Long,
    val isImage: Boolean,
    val category: String = "FILE" // IMAGE, VIDEO, AUDIO, DOC, APK, ARCHIVE, FILE
)

@Entity(
    tableName = "call_records",
    indices = [Index(value = ["timestamp"])]
)
data class CallRecordEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val title: String,
    val callType: String = CallType.VOICE.name,
    val direction: String = CallDirection.OUTGOING.name,
    val isGroup: Boolean = false,
    val participantsCsv: String = "",
    val durationSeconds: Long = 0L,
    val transportUsed: String = TransportType.WIFI_DIRECT.name,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "pending_relays",
    indices = [
        Index(value = ["targetPeerId", "createdAtMs"]),
        Index(value = ["createdAtMs"])
    ]
)
data class PendingRelayEntity(
    @PrimaryKey val packetId: String,
    val targetPeerId: String,
    val conversationId: String,
    val messageId: String,
    val envelopeJson: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
