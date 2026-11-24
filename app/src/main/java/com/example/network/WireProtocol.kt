package com.example.network

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

enum class PacketType {
    HANDSHAKE,
    ROUTE_ADVERT,
    TEXT_MESSAGE,
    VOICE_MESSAGE,
    IMAGE_TRANSFER,
    FILE_TRANSFER,
    FILE_CHUNK,
    EMOJI_REACTION,
    MESSAGE_EDIT,
    MESSAGE_DELETE,
    DELIVERY_ACK,
    TYPING_INDICATOR,
    CALL_INVITE,
    CALL_ACCEPT,
    CALL_REJECT,
    CALL_END,
    CALL_MUTE_STATE,
    AUDIO_FRAME,
    VIDEO_FRAME,
    PTT_AUDIO_FRAME,
    PING,
    PONG
}

@JsonClass(generateAdapter = true)
data class RouteNeighborAdvert(
    val peerId: String,
    val displayName: String,
    val transportName: String,
    val bandwidthMbps: Float,
    val avatarIndex: Int = 0
)

@JsonClass(generateAdapter = true)
data class WireEnvelope(
    val packetId: String,
    val type: String,
    val senderId: String,
    val senderName: String,
    val senderDevice: String,
    val senderAvatarIndex: Int = 0,
    val senderStatus: String = "Offline & Ready",
    val targetId: String = "", // Empty = direct peer or group broadcast; non-empty = specific destination nodeId
    val hopCount: Int = 0,
    val maxHops: Int = 5,
    val routeTraceCsv: String = "",
    val conversationId: String = "",
    val conversationTitle: String = "",
    val isGroup: Boolean = false,
    val groupPeerIdsCsv: String = "",
    val messageId: String = "",
    val textContent: String = "",
    val replyToMessageId: String? = null,
    val replyToSender: String? = null,
    val replyToPreview: String? = null,
    val isForwarded: Boolean = false,
    val fileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val mediaWidth: Int = 0,
    val mediaHeight: Int = 0,
    val voiceDurationMs: Long = 0L,
    val waveformCsv: String = "",
    val transferId: String? = null,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val payloadBase64: String? = null,
    val reactionEmoji: String? = null,
    val isTyping: Boolean = false,
    val callId: String? = null,
    val callType: String? = null,
    val pttChannel: String? = null,
    val neighbors: List<RouteNeighborAdvert>? = null,
    val transportName: String = "",
    val timestamp: Long = System.currentTimeMillis()
)

object SocketFrameCodec {
    private const val MAX_FRAME_BYTES = 16 * 1024 * 1024 // 16 MB safety cap

    private val moshi: Moshi = Moshi.Builder().build()
    private val adapter = moshi.adapter(WireEnvelope::class.java)

    fun toJson(envelope: WireEnvelope): String = adapter.toJson(envelope)

    fun fromJson(json: String): WireEnvelope? = runCatching { adapter.fromJson(json) }.getOrNull()

    fun encode(envelope: WireEnvelope): ByteArray {
        return adapter.toJson(envelope).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): WireEnvelope? {
        return runCatching {
            adapter.fromJson(bytes.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    /**
     * Writes a length-prefixed JSON frame to [outputStream].
     * Synchronization is scoped per [outputStream] (and typically single-writer per session)
     * rather than globally across all peers, preventing slow Bluetooth links from stalling Wi-Fi Direct.
     */
    fun writeFrame(outputStream: OutputStream, envelope: WireEnvelope): Int {
        val payload = encode(envelope)
        if (payload.size > MAX_FRAME_BYTES) {
            throw IllegalArgumentException("Frame payload exceeds limit: ${payload.size}")
        }
        synchronized(outputStream) {
            val dos = DataOutputStream(outputStream)
            dos.writeInt(payload.size)
            dos.write(payload)
            dos.flush()
        }
        return payload.size + 4
    }

    fun readFrame(inputStream: InputStream): Pair<WireEnvelope, Int>? {
        val dis = DataInputStream(inputStream)
        val length = try {
            dis.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw IllegalStateException("Invalid frame length: $length")
        }
        val buffer = ByteArray(length)
        dis.readFully(buffer)
        val envelope = decode(buffer) ?: return null
        return envelope to (length + 4)
    }
}
