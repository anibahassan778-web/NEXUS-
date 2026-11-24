package com.example.data.repository

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Base64
import androidx.core.content.FileProvider
import com.example.data.local.CallRecordEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.NexusDao
import com.example.data.local.PeerContactEntity
import com.example.data.local.PendingRelayEntity
import com.example.data.local.ReceivedFileEntity
import com.example.data.local.UserPreferencesRepository
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.MeshMode
import com.example.domain.model.MessageType
import com.example.domain.model.NexusThemeMode
import com.example.domain.model.PeerNode
import com.example.domain.model.TransferItemState
import com.example.domain.model.TransferState
import com.example.domain.model.TransportType
import com.example.domain.model.UserProfile
import com.example.media.RecordedVoiceResult
import com.example.network.NexusMeshEngine
import com.example.network.PacketType
import com.example.network.SocketFrameCodec
import com.example.network.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.BitSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NexusRepository(
    private val context: Context,
    private val dao: NexusDao,
    private val prefs: UserPreferencesRepository,
    val meshEngine: NexusMeshEngine
) {
    companion object {
        private const val CHUNK_SIZE_BYTES = 48 * 1024 // 48 KB chunks
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val MAX_OFFLINE_FILE_BYTES = 32L * 1024 * 1024 // 32 MB offline cap
        private const val MAX_RELAY_RETRIES = 15
        private const val RELAY_TTL_MS = 24L * 60 * 60 * 1000 // 24 hours
        private const val ASSEMBLER_STALE_MS = 5L * 60 * 1000 // 5 minutes
        private const val TYPING_THROTTLE_MS = 2_500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mediaDir: File by lazy {
        File(context.filesDir, "nexus_media").apply { mkdirs() }
    }

    private val filesDir: File by lazy {
        File(context.filesDir, "nexus_files").apply { mkdirs() }
    }

    private val tempTransferDir: File by lazy {
        File(context.cacheDir, "transfer_parts").apply { mkdirs() }
    }

    private val cameraCacheDir: File by lazy {
        File(context.cacheDir, "camera_captures").apply { mkdirs() }
    }

    val userProfile: Flow<UserProfile> = prefs.userProfile
    val conversations: Flow<List<ConversationEntity>> = dao.observeConversations()
    val allConversations: Flow<List<ConversationEntity>> = dao.observeAllConversations()
    val peerContacts: Flow<List<PeerContactEntity>> = dao.observePeerContacts()
    val receivedFiles: Flow<List<ReceivedFileEntity>> = dao.observeReceivedFiles()
    val callHistory: Flow<List<CallRecordEntity>> = dao.observeCallHistory()
    val pendingRelays: Flow<List<PendingRelayEntity>> = dao.observePendingRelays()

    private val _transfers = MutableStateFlow<List<TransferItemState>>(emptyList())
    val transfers: StateFlow<List<TransferItemState>> = _transfers.asStateFlow()

    private val activeSendJobs = ConcurrentHashMap<String, Job>()
    private val relayFlushMutex = Mutex()
    private val lastTypingSentAt = ConcurrentHashMap<String, Long>()
    private val lastTypingState = ConcurrentHashMap<String, Boolean>()

    private class IncomingChunkAssembler(
        val transferId: String,
        val fileName: String,
        val mimeType: String,
        val totalChunks: Int,
        val totalBytes: Long,
        val senderId: String,
        val senderName: String,
        val senderAvatarIndex: Int,
        val conversationId: String,
        val conversationTitle: String,
        val isGroup: Boolean,
        val groupPeerIdsCsv: String,
        val messageId: String,
        val msgType: MessageType,
        val mediaWidth: Int,
        val mediaHeight: Int,
        val timestamp: Long,
        val partFile: File,
        val receivedChunks: BitSet = BitSet(totalChunks),
        var receivedBytesCount: Long = 0L,
        val startedAtMs: Long = System.currentTimeMillis(),
        @Volatile var lastChunkAtMs: Long = System.currentTimeMillis()
    ) {
        val writeLock = Any()
    }

    private val incomingAssemblers = ConcurrentHashMap<String, IncomingChunkAssembler>()

    init {
        // Clean up leftover .part files from prior killed runs
        scope.launch {
            tempTransferDir.listFiles()?.forEach { runCatching { it.delete() } }
            prefs.ensureNodeIdInitialized()
            prefs.userProfile.collect { profile ->
                meshEngine.updateLocalProfile(profile)
            }
        }
        scope.launch {
            dao.observePendingRelays().collect { relays ->
                meshEngine.updateQueuedRelayCount(relays.size)
            }
        }
        scope.launch {
            meshEngine.incomingPackets.collect { (envelope, transport) ->
                runCatching { processIncomingPacket(envelope, transport) }
            }
        }
        scope.launch {
            meshEngine.routeFlushTrigger.collect { reachableIds ->
                runCatching { flushPendingRelays(reachableIds) }
            }
        }
        scope.launch {
            meshEngine.connectedPeers.collect { peers ->
                peers.forEach { peer ->
                    dao.upsertPeerContact(
                        PeerContactEntity(
                            peerId = peer.peerId,
                            nickname = peer.displayName,
                            deviceName = peer.deviceName,
                            statusMessage = peer.statusText,
                            avatarIndex = peer.avatarIndex,
                            lastTransport = peer.transport.name,
                            ipAddress = peer.ipAddress,
                            bluetoothAddress = peer.bluetoothAddress,
                            lastConnectedTimestamp = System.currentTimeMillis()
                        )
                    )
                    ensureDirectConversation(peer)
                }
            }
        }
        // Periodic sweep of stale incoming .part assemblers & expired store-and-forward relays
        scope.launch {
            while (isActive) {
                delay(60_000L)
                val now = System.currentTimeMillis()
                incomingAssemblers.entries.removeIf { (_, assembler) ->
                    val expired = (now - assembler.lastChunkAtMs) > ASSEMBLER_STALE_MS
                    if (expired) {
                        runCatching { assembler.partFile.delete() }
                        upsertTransferState(
                            TransferItemState(
                                transferId = assembler.transferId,
                                conversationId = assembler.conversationId,
                                messageId = assembler.messageId,
                                fileName = assembler.fileName,
                                mimeType = assembler.mimeType,
                                totalBytes = assembler.totalBytes,
                                transferredBytes = assembler.receivedBytesCount,
                                progressPercent = 0,
                                speedBytesPerSec = 0L,
                                isOutgoing = false,
                                state = TransferState.FAILED
                            )
                        )
                    }
                    expired
                }
                runCatching {
                    dao.pruneExpiredRelays(
                        maxRetries = MAX_RELAY_RETRIES,
                        minCreatedAtMs = now - RELAY_TTL_MS
                    )
                }
            }
        }
    }

    fun observeConversation(conversationId: String): Flow<ConversationEntity?> =
        dao.observeConversation(conversationId)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        dao.observeMessages(conversationId)

    fun createCameraCaptureUri(): Pair<Uri, File> {
        val photoFile = File(cameraCacheDir, "cam_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
        return uri to photoFile
    }

    suspend fun openOrCreatePeerChat(peer: PeerNode): String {
        ensureDirectConversation(peer)
        return directConversationId(peer.peerId)
    }

    suspend fun createGroupChannel(title: String, selectedPeerIds: List<String>): String {
        val convId = "grp_" + UUID.randomUUID().toString().take(8)
        val cleanTitle = title.trim().ifEmpty { "Local Mesh Channel" }
        val activeTransport = meshEngine.networkStats.value.activeTransport.name
        val conversation = ConversationEntity(
            id = convId,
            title = cleanTitle,
            isGroup = true,
            peerIdsCsv = selectedPeerIds.joinToString(","),
            lastMessagePreview = "Group channel created offline",
            lastMessageTimestamp = System.currentTimeMillis(),
            unreadCount = 0,
            preferredTransport = activeTransport,
            avatarIndex = kotlin.math.abs(cleanTitle.hashCode()) % 6
        )
        val systemMsg = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            senderId = "SYSTEM",
            senderName = "NEXUS",
            isOutgoing = false,
            type = MessageType.SYSTEM.name,
            textContent = "Local channel \"$cleanTitle\" initialized. Messages route across direct & multi-hop Mesh peers.",
            deliveryStatus = DeliveryStatus.DELIVERED.name,
            transportUsed = activeTransport
        )
        dao.createConversationWithSystemMessage(conversation, systemMsg)
        return convId
    }

    suspend fun clearUnread(conversationId: String) = dao.clearUnreadCount(conversationId)

    suspend fun setConversationArchived(conversationId: String, archived: Boolean) =
        dao.setConversationArchived(conversationId, archived)

    suspend fun setConversationMuted(conversationId: String, muted: Boolean) =
        dao.setConversationMuted(conversationId, muted)

    suspend fun deleteConversation(conversationId: String) {
        dao.deleteConversationAndMessages(conversationId)
    }

    suspend fun switchConversationTransport(conversationId: String, transport: TransportType) {
        dao.updateConversationTransport(conversationId, transport.name)
        meshEngine.selectTransportMode(transport)
    }

    suspend fun sendTypingIndicator(conversationId: String, isTyping: Boolean) {
        val now = System.currentTimeMillis()
        val prevState = lastTypingState[conversationId] ?: false
        val lastAt = lastTypingSentAt[conversationId] ?: 0L
        if (isTyping && prevState && (now - lastAt) < TYPING_THROTTLE_MS) return
        if (!isTyping && !prevState) return

        lastTypingState[conversationId] = isTyping
        lastTypingSentAt[conversationId] = now

        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val targets = parsePeerIds(conv.peerIdsCsv)
        meshEngine.sendEnvelope(
            WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.TYPING_INDICATOR.name,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
                isTyping = isTyping
            ),
            targetPeerIds = targets
        )
    }

    suspend fun sendTextMessage(
        conversationId: String,
        text: String,
        replyTo: MessageEntity? = null
    ) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return
        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val transport = meshEngine.networkStats.value.activeTransport
        val targets = parsePeerIds(conv.peerIdsCsv)

        val envelope = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.TEXT_MESSAGE.name,
            senderId = profile.nodeId,
            senderName = profile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            senderAvatarIndex = profile.avatarIndex,
            targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
            conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
            conversationTitle = conv.title,
            isGroup = conv.isGroup,
            groupPeerIdsCsv = conv.peerIdsCsv,
            messageId = msgId,
            textContent = cleanText,
            replyToMessageId = replyTo?.id,
            replyToSender = replyTo?.senderName,
            replyToPreview = replyTo?.let { previewForMessage(it) }
        )

        val status = dispatchOrQueue(profile, conv, msgId, targets, envelope)

        dao.insertOutgoingMessageAndTouchConversation(
            message = MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = cleanText,
                replyToMessageId = replyTo?.id,
                replyToSender = replyTo?.senderName,
                replyToPreview = replyTo?.let { previewForMessage(it) },
                deliveryStatus = status.name,
                transportUsed = transport.name
            ),
            conversation = conv.copy(
                lastMessagePreview = cleanText,
                lastMessageTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun forwardMessage(source: MessageEntity, targetConversationId: String) {
        val profile = userProfile.first()
        val targetConv = dao.getConversation(targetConversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val transport = meshEngine.networkStats.value.activeTransport
        val targets = parsePeerIds(targetConv.peerIdsCsv)
        val msgType = runCatching { MessageType.valueOf(source.type) }.getOrDefault(MessageType.TEXT)

        if (msgType == MessageType.TEXT) {
            val envelope = WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.TEXT_MESSAGE.name,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                senderAvatarIndex = profile.avatarIndex,
                targetId = if (!targetConv.isGroup && targets.size == 1) targets.first() else "",
                conversationId = if (targetConv.isGroup) targetConv.id else directConversationId(profile.nodeId),
                conversationTitle = targetConv.title,
                isGroup = targetConv.isGroup,
                groupPeerIdsCsv = targetConv.peerIdsCsv,
                messageId = msgId,
                textContent = source.textContent,
                isForwarded = true
            )
            val status = dispatchOrQueue(profile, targetConv, msgId, targets, envelope)
            dao.insertOutgoingMessageAndTouchConversation(
                message = MessageEntity(
                    id = msgId,
                    conversationId = targetConversationId,
                    senderId = profile.nodeId,
                    senderName = profile.nickname,
                    isOutgoing = true,
                    type = MessageType.TEXT.name,
                    textContent = source.textContent,
                    isForwarded = true,
                    deliveryStatus = status.name,
                    transportUsed = transport.name
                ),
                conversation = targetConv.copy(
                    lastMessagePreview = "Forwarded: ${source.textContent}",
                    lastMessageTimestamp = System.currentTimeMillis()
                )
            )
        } else {
            val path = source.mediaPath ?: return
            val file = File(path)
            if (!file.exists()) return
            sendLocalFileStreaming(
                conversationId = targetConversationId,
                file = file,
                displayName = source.fileName ?: file.name,
                mimeType = source.mimeType ?: "application/octet-stream",
                msgType = msgType,
                isForwarded = true
            )
        }
    }

    suspend fun editMessage(conversationId: String, messageId: String, newText: String) {
        val clean = newText.trim()
        if (clean.isEmpty()) return
        dao.editMessageText(messageId, clean)
        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val targets = parsePeerIds(conv.peerIdsCsv)
        meshEngine.sendEnvelope(
            WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.MESSAGE_EDIT.name,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
                conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
                messageId = messageId,
                textContent = clean
            ),
            targetPeerIds = targets
        )
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        dao.softDeleteMessage(messageId)
        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val targets = parsePeerIds(conv.peerIdsCsv)
        meshEngine.sendEnvelope(
            WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.MESSAGE_DELETE.name,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
                conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
                messageId = messageId
            ),
            targetPeerIds = targets
        )
    }

    suspend fun sendVoiceMessage(conversationId: String, recorded: RecordedVoiceResult) {
        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val transport = meshEngine.networkStats.value.activeTransport
        val targets = parsePeerIds(conv.peerIdsCsv)
        val bytes = withContext(Dispatchers.IO) { recorded.file.readBytes() }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        val envelope = WireEnvelope(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.VOICE_MESSAGE.name,
            senderId = profile.nodeId,
            senderName = profile.nickname,
            senderDevice = Build.MODEL ?: "Android",
            senderAvatarIndex = profile.avatarIndex,
            targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
            conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
            conversationTitle = conv.title,
            isGroup = conv.isGroup,
            groupPeerIdsCsv = conv.peerIdsCsv,
            messageId = msgId,
            fileName = recorded.file.name,
            mimeType = "audio/mp4",
            fileSizeBytes = bytes.size.toLong(),
            voiceDurationMs = recorded.durationMs,
            waveformCsv = recorded.waveformCsv,
            payloadBase64 = b64
        )

        val status = dispatchOrQueue(profile, conv, msgId, targets, envelope)

        dao.insertOutgoingMessageAndTouchConversation(
            message = MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                isOutgoing = true,
                type = MessageType.VOICE.name,
                textContent = "Voice note",
                mediaPath = recorded.file.absolutePath,
                fileName = recorded.file.name,
                mimeType = "audio/mp4",
                fileSizeBytes = bytes.size.toLong(),
                voiceDurationMs = recorded.durationMs,
                waveformCsv = recorded.waveformCsv,
                deliveryStatus = status.name,
                transportUsed = transport.name
            ),
            conversation = conv.copy(
                lastMessagePreview = "Voice message (${(recorded.durationMs / 1000).coerceAtLeast(1)}s)",
                lastMessageTimestamp = System.currentTimeMillis()
            )
        )
    }

    suspend fun sendImageFromUri(conversationId: String, uri: Uri, caption: String = "") = withContext(Dispatchers.IO) {
        val copied = copyUriToFileStreaming(uri, mediaDir, "photo_${System.currentTimeMillis()}.jpg")
            ?: return@withContext
        val (fileName, mimeType, destFile) = copied
        sendLocalFileStreaming(
            conversationId = conversationId,
            file = destFile,
            displayName = fileName,
            mimeType = mimeType,
            msgType = MessageType.IMAGE,
            caption = caption
        )
    }

    suspend fun sendCameraFile(conversationId: String, cameraFile: File, caption: String = "") = withContext(Dispatchers.IO) {
        if (!cameraFile.exists() || cameraFile.length() == 0L) return@withContext
        val destFile = File(mediaDir, "${UUID.randomUUID()}_camera.jpg")
        cameraFile.copyTo(destFile, overwrite = true)
        runCatching { cameraFile.delete() }
        sendLocalFileStreaming(
            conversationId = conversationId,
            file = destFile,
            displayName = destFile.name,
            mimeType = "image/jpeg",
            msgType = MessageType.IMAGE,
            caption = caption
        )
    }

    suspend fun sendFileFromUri(conversationId: String, uri: Uri) = withContext(Dispatchers.IO) {
        val meta = resolveUriMetadata(uri, "file_${System.currentTimeMillis()}.bin")
        val msgType = classifyMessageType(meta.second, meta.first)
        val destFolder = if (msgType == MessageType.IMAGE) mediaDir else filesDir
        val copied = copyUriToFileStreaming(uri, destFolder, meta.first) ?: return@withContext
        val (fileName, mimeType, destFile) = copied
        sendLocalFileStreaming(
            conversationId = conversationId,
            file = destFile,
            displayName = fileName,
            mimeType = mimeType,
            msgType = msgType
        )
    }

    suspend fun retryTransfer(messageId: String) = withContext(Dispatchers.IO) {
        val msg = dao.getMessageById(messageId) ?: return@withContext
        val path = msg.mediaPath ?: return@withContext
        val file = File(path)
        if (!file.exists()) return@withContext
        val msgType = runCatching { MessageType.valueOf(msg.type) }.getOrDefault(MessageType.FILE)
        streamChunkedTransfer(
            conversationId = msg.conversationId,
            messageId = msg.id,
            file = file,
            displayName = msg.fileName ?: file.name,
            mimeType = msg.mimeType ?: "application/octet-stream",
            msgType = msgType,
            width = msg.mediaWidth,
            height = msg.mediaHeight,
            caption = msg.textContent
        )
    }

    fun cancelTransfer(transferId: String) {
        activeSendJobs.remove(transferId)?.cancel()
        incomingAssemblers.remove(transferId)?.let { assembler ->
            runCatching { assembler.partFile.delete() }
        }
        _transfers.update { list ->
            list.map {
                if (it.transferId == transferId) it.copy(state = TransferState.CANCELLED) else it
            }
        }
    }

    private suspend fun sendLocalFileStreaming(
        conversationId: String,
        file: File,
        displayName: String,
        mimeType: String,
        msgType: MessageType,
        caption: String = "",
        isForwarded: Boolean = false
    ) {
        val msgId = UUID.randomUUID().toString()
        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val transport = meshEngine.networkStats.value.activeTransport

        var width = 0
        var height = 0
        if (msgType == MessageType.IMAGE) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            width = opts.outWidth.coerceAtLeast(0)
            height = opts.outHeight.coerceAtLeast(0)
        }

        val sizeBytes = file.length()
        val preview = when (msgType) {
            MessageType.IMAGE -> "Photo: $displayName"
            MessageType.VIDEO -> "Video: $displayName"
            MessageType.AUDIO -> "Audio: $displayName"
            else -> "File: $displayName"
        }

        dao.insertOutgoingMessageAndTouchConversation(
            message = MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                isOutgoing = true,
                type = msgType.name,
                textContent = caption.ifBlank { displayName },
                mediaPath = file.absolutePath,
                fileName = displayName,
                mimeType = mimeType,
                fileSizeBytes = sizeBytes,
                mediaWidth = width,
                mediaHeight = height,
                isForwarded = isForwarded,
                transferProgress = 0,
                transferState = TransferState.IN_PROGRESS.name,
                deliveryStatus = DeliveryStatus.SENDING.name,
                transportUsed = transport.name
            ),
            conversation = conv.copy(
                lastMessagePreview = preview,
                lastMessageTimestamp = System.currentTimeMillis()
            )
        )

        streamChunkedTransfer(
            conversationId = conversationId,
            messageId = msgId,
            file = file,
            displayName = displayName,
            mimeType = mimeType,
            msgType = msgType,
            width = width,
            height = height,
            caption = caption.ifBlank { displayName }
        )
    }

    /**
     * Streams file chunks from disk with a fixed 48 KB buffer rather than loading the entire file into RAM.
     */
    private fun streamChunkedTransfer(
        conversationId: String,
        messageId: String,
        file: File,
        displayName: String,
        mimeType: String,
        msgType: MessageType,
        width: Int,
        height: Int,
        caption: String
    ) {
        // Prevent duplicate simultaneous transfer for the same message
        if (activeSendJobs.values.any { it.isActive && _transfers.value.any { tx -> tx.messageId == messageId && tx.state == TransferState.IN_PROGRESS } }) {
            return
        }

        val transferId = "tx_" + UUID.randomUUID().toString().take(8)
        val job = scope.launch(Dispatchers.IO) {
            val profile = userProfile.first()
            val conv = dao.getConversation(conversationId) ?: return@launch
            val targets = parsePeerIds(conv.peerIdsCsv)
            val totalSize = file.length()
            val totalChunks = ((totalSize + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt().coerceAtLeast(1)

            upsertTransferState(
                TransferItemState(
                    transferId = transferId,
                    conversationId = conversationId,
                    messageId = messageId,
                    fileName = displayName,
                    mimeType = mimeType,
                    totalBytes = totalSize,
                    transferredBytes = 0L,
                    progressPercent = 0,
                    speedBytesPerSec = 0L,
                    isOutgoing = true,
                    state = TransferState.IN_PROGRESS
                )
            )

            val startedMs = System.currentTimeMillis()
            var allChunksDispatched = true
            var sentBytes = 0L
            val chunkBuf = ByteArray(CHUNK_SIZE_BYTES)

            try {
                BufferedInputStream(FileInputStream(file), CHUNK_SIZE_BYTES).use { bis ->
                    for (idx in 0 until totalChunks) {
                        if (!isActive) {
                            dao.updateMessageTransferProgress(messageId, 0, TransferState.CANCELLED.name, 0L)
                            return@launch
                        }
                        var bytesRead = 0
                        while (bytesRead < CHUNK_SIZE_BYTES) {
                            val n = bis.read(chunkBuf, bytesRead, CHUNK_SIZE_BYTES - bytesRead)
                            if (n == -1) break
                            bytesRead += n
                        }
                        if (bytesRead <= 0 && totalSize > 0L) break

                        val b64 = Base64.encodeToString(chunkBuf, 0, bytesRead, Base64.NO_WRAP)
                        val envelope = WireEnvelope(
                            packetId = UUID.randomUUID().toString(),
                            type = PacketType.FILE_CHUNK.name,
                            senderId = profile.nodeId,
                            senderName = profile.nickname,
                            senderDevice = Build.MODEL ?: "Android",
                            senderAvatarIndex = profile.avatarIndex,
                            targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
                            conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
                            conversationTitle = conv.title,
                            isGroup = conv.isGroup,
                            groupPeerIdsCsv = conv.peerIdsCsv,
                            messageId = messageId,
                            textContent = caption,
                            fileName = displayName,
                            mimeType = mimeType,
                            fileSizeBytes = totalSize,
                            mediaWidth = width,
                            mediaHeight = height,
                            transferId = transferId,
                            chunkIndex = idx,
                            totalChunks = totalChunks,
                            payloadBase64 = b64
                        )

                        val sent = meshEngine.sendEnvelope(envelope, targets)
                        if (!sent) {
                            allChunksDispatched = false
                            break
                        }

                        sentBytes += bytesRead
                        val elapsedSec = ((System.currentTimeMillis() - startedMs).coerceAtLeast(100L)) / 1000.0
                        val speed = (sentBytes / elapsedSec).toLong()
                        val pct = ((idx + 1) * 100) / totalChunks

                        dao.updateMessageTransferProgress(messageId, pct, TransferState.IN_PROGRESS.name, speed)
                        upsertTransferState(
                            TransferItemState(
                                transferId = transferId,
                                conversationId = conversationId,
                                messageId = messageId,
                                fileName = displayName,
                                mimeType = mimeType,
                                totalBytes = totalSize,
                                transferredBytes = sentBytes,
                                progressPercent = pct,
                                speedBytesPerSec = speed,
                                isOutgoing = true,
                                state = if (pct >= 100) TransferState.COMPLETED else TransferState.IN_PROGRESS
                            )
                        )
                        if (totalChunks > 1) delay(12L)
                    }
                }
            } catch (_: Exception) {
                allChunksDispatched = false
            }

            if (allChunksDispatched) {
                dao.updateMessageTransferProgress(messageId, 100, TransferState.COMPLETED.name, 0L)
                dao.updateMessageStatus(messageId, DeliveryStatus.SENT.name)
            } else {
                dao.updateMessageTransferProgress(messageId, 0, TransferState.FAILED.name, 0L)
                dao.updateMessageStatus(messageId, DeliveryStatus.QUEUED.name)
                upsertTransferState(
                    TransferItemState(
                        transferId = transferId,
                        conversationId = conversationId,
                        messageId = messageId,
                        fileName = displayName,
                        mimeType = mimeType,
                        totalBytes = totalSize,
                        transferredBytes = sentBytes,
                        progressPercent = 0,
                        speedBytesPerSec = 0L,
                        isOutgoing = true,
                        state = TransferState.FAILED
                    )
                )
            }
            activeSendJobs.remove(transferId)
        }
        activeSendJobs[transferId] = job
    }

    suspend fun toggleReaction(conversationId: String, messageId: String, emoji: String) {
        val msg = dao.getMessageById(messageId) ?: return
        val existing = msg.reactionsCsv.split(",").filter { it.isNotBlank() }.toMutableList()
        if (emoji in existing) existing.remove(emoji) else existing.add(emoji)
        dao.updateMessageReactions(messageId, existing.joinToString(","))

        val profile = userProfile.first()
        val conv = dao.getConversation(conversationId) ?: return
        val targets = parsePeerIds(conv.peerIdsCsv)
        meshEngine.sendEnvelope(
            WireEnvelope(
                packetId = UUID.randomUUID().toString(),
                type = PacketType.EMOJI_REACTION.name,
                senderId = profile.nodeId,
                senderName = profile.nickname,
                senderDevice = Build.MODEL ?: "Android",
                targetId = if (!conv.isGroup && targets.size == 1) targets.first() else "",
                conversationId = if (conv.isGroup) conv.id else directConversationId(profile.nodeId),
                messageId = messageId,
                reactionEmoji = emoji
            ),
            targetPeerIds = targets
        )
    }

    suspend fun updateProfile(nickname: String, status: String, avatarIndex: Int) =
        prefs.updateProfile(nickname, status, avatarIndex)

    suspend fun updateTransportPreference(preferred: TransportType, autoSelect: Boolean) =
        prefs.updateTransportPreference(preferred, autoSelect)

    suspend fun updateMeshMode(meshMode: MeshMode) = prefs.updateMeshMode(meshMode)

    suspend fun updateLowBandwidthMode(enabled: Boolean) = prefs.updateLowBandwidthMode(enabled)

    suspend fun updatePttMeshForwarding(enabled: Boolean) = prefs.updatePttMeshForwarding(enabled)

    suspend fun updateThemeMode(themeMode: NexusThemeMode) = prefs.updateThemeMode(themeMode)

    suspend fun updateVoiceCodecPreset(preset: String) = prefs.updateVoiceCodecPreset(preset)

    suspend fun clearCallHistory() = dao.clearCallHistory()

    suspend fun deleteReceivedFile(file: ReceivedFileEntity) = withContext(Dispatchers.IO) {
        runCatching { File(file.filePath).delete() }
        dao.deleteReceivedFile(file.id)
    }

    suspend fun clearAllReceivedStorage() = withContext(Dispatchers.IO) {
        mediaDir.listFiles()?.forEach { it.delete() }
        filesDir.listFiles()?.forEach { it.delete() }
        tempTransferDir.listFiles()?.forEach { it.delete() }
        dao.clearReceivedFiles()
    }

    suspend fun calculateLocalStorageBytes(): Long = withContext(Dispatchers.IO) {
        val mediaBytes = mediaDir.listFiles()?.sumOf { it.length() } ?: 0L
        val docBytes = filesDir.listFiles()?.sumOf { it.length() } ?: 0L
        val voiceBytes = File(context.filesDir, "nexus_voice").listFiles()?.sumOf { it.length() } ?: 0L
        mediaBytes + docBytes + voiceBytes
    }

    private suspend fun dispatchOrQueue(
        profile: UserProfile,
        conv: ConversationEntity,
        messageId: String,
        targets: List<String>,
        envelope: WireEnvelope
    ): DeliveryStatus {
        val dispatched = meshEngine.sendEnvelope(envelope, targets)
        if (dispatched) return DeliveryStatus.SENT

        if (profile.meshMode == MeshMode.STORE_AND_FORWARD || profile.meshMode == MeshMode.MESH_MODE) {
            val targetCsv = if (targets.isEmpty()) "BROADCAST" else targets.joinToString(",")
            dao.insertPendingRelay(
                PendingRelayEntity(
                    packetId = envelope.packetId,
                    targetPeerId = targetCsv,
                    conversationId = conv.id,
                    messageId = messageId,
                    envelopeJson = SocketFrameCodec.toJson(envelope)
                )
            )
            return DeliveryStatus.QUEUED
        }
        return DeliveryStatus.FAILED
    }

    private suspend fun flushPendingRelays(reachablePeerIds: Set<String>) {
        relayFlushMutex.withLock {
            val pending = dao.getAllPendingRelays()
            if (pending.isEmpty()) return

            for (relay in pending) {
                val targetList = if (relay.targetPeerId == "BROADCAST") {
                    emptyList()
                } else {
                    relay.targetPeerId.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                }
                val canSend = targetList.isEmpty() ||
                    targetList.any { it in reachablePeerIds || meshEngine.isPeerReachable(it) }
                if (!canSend) continue

                val envelope = SocketFrameCodec.fromJson(relay.envelopeJson) ?: run {
                    dao.deletePendingRelay(relay.packetId)
                    continue
                }
                if (meshEngine.sendEnvelope(envelope, targetList)) {
                    dao.deletePendingRelay(relay.packetId)
                    dao.updateMessageStatus(relay.messageId, DeliveryStatus.SENT.name)
                } else {
                    if (relay.retryCount + 1 >= MAX_RELAY_RETRIES) {
                        dao.deletePendingRelay(relay.packetId)
                        dao.updateMessageStatus(relay.messageId, DeliveryStatus.FAILED.name)
                    } else {
                        dao.incrementRelayRetry(relay.packetId)
                    }
                }
            }
        }
    }

    private suspend fun processIncomingPacket(envelope: WireEnvelope, transport: TransportType) {
        when (envelope.type) {
            PacketType.DELIVERY_ACK.name -> {
                if (envelope.messageId.isNotBlank()) {
                    dao.updateMessageDeliveryAndTrace(
                        envelope.messageId,
                        DeliveryStatus.DELIVERED.name,
                        envelope.routeTraceCsv
                    )
                }
            }
            PacketType.MESSAGE_EDIT.name -> {
                if (envelope.messageId.isNotBlank()) {
                    dao.editMessageText(envelope.messageId, envelope.textContent)
                }
            }
            PacketType.MESSAGE_DELETE.name -> {
                if (envelope.messageId.isNotBlank()) {
                    dao.softDeleteMessage(envelope.messageId)
                }
            }
            PacketType.EMOJI_REACTION.name -> {
                val emoji = envelope.reactionEmoji ?: return
                val msg = dao.getMessageById(envelope.messageId) ?: return
                val existing = msg.reactionsCsv.split(",").filter { it.isNotBlank() }.toMutableList()
                if (emoji in existing) existing.remove(emoji) else existing.add(emoji)
                dao.updateMessageReactions(envelope.messageId, existing.joinToString(","))
            }
            PacketType.FILE_CHUNK.name -> {
                handleIncomingFileChunk(envelope, transport)
            }
            PacketType.TEXT_MESSAGE.name,
            PacketType.VOICE_MESSAGE.name,
            PacketType.IMAGE_TRANSFER.name,
            PacketType.FILE_TRANSFER.name -> {
                val msgId = envelope.messageId.ifBlank { UUID.randomUUID().toString() }
                if (dao.getMessageById(msgId) != null) {
                    return // Deduplicate re-delivered message
                }

                val convId = if (envelope.isGroup && envelope.conversationId.isNotBlank()) {
                    envelope.conversationId
                } else {
                    directConversationId(envelope.senderId)
                }
                val existingConv = dao.getConversation(convId)
                val convTitle = if (envelope.isGroup) {
                    envelope.conversationTitle.ifBlank { "Group Mesh" }
                } else {
                    envelope.senderName
                }

                var localMediaPath: String? = null
                var receivedFileEntity: ReceivedFileEntity? = null
                val rawBytes = envelope.payloadBase64?.let {
                    runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
                }
                val msgType = when (envelope.type) {
                    PacketType.VOICE_MESSAGE.name -> MessageType.VOICE
                    PacketType.IMAGE_TRANSFER.name -> MessageType.IMAGE
                    PacketType.FILE_TRANSFER.name -> MessageType.FILE
                    else -> MessageType.TEXT
                }

                if (rawBytes != null) {
                    val safeName = sanitizeFileName(envelope.fileName ?: "nexus_payload_${System.currentTimeMillis()}")
                    val targetFolder = when (msgType) {
                        MessageType.VOICE -> File(context.filesDir, "nexus_voice").apply { mkdirs() }
                        MessageType.IMAGE -> mediaDir
                        else -> filesDir
                    }
                    val dest = File(targetFolder, "${UUID.randomUUID()}_$safeName")
                    dest.writeBytes(rawBytes)
                    localMediaPath = dest.absolutePath

                    if (msgType != MessageType.VOICE) {
                        receivedFileEntity = ReceivedFileEntity(
                            id = msgId,
                            fileName = safeName,
                            filePath = dest.absolutePath,
                            mimeType = envelope.mimeType ?: "*/*",
                            sizeBytes = rawBytes.size.toLong(),
                            senderName = envelope.senderName,
                            receivedTimestamp = envelope.timestamp,
                            isImage = msgType == MessageType.IMAGE,
                            category = classifyFileCategory(envelope.mimeType ?: "", safeName)
                        )
                    }
                }

                val preview = when (msgType) {
                    MessageType.VOICE -> "Voice message"
                    MessageType.IMAGE -> "Photo: ${envelope.fileName ?: "Image"}"
                    MessageType.VIDEO -> "Video: ${envelope.fileName ?: "Video"}"
                    MessageType.FILE -> "File: ${envelope.fileName ?: "Document"}"
                    else -> envelope.textContent
                }

                val updatedConv = (existingConv ?: ConversationEntity(
                    id = convId,
                    title = convTitle,
                    isGroup = envelope.isGroup,
                    peerIdsCsv = envelope.groupPeerIdsCsv.ifBlank { envelope.senderId },
                    avatarIndex = envelope.senderAvatarIndex
                )).copy(
                    title = convTitle,
                    lastMessagePreview = preview,
                    lastMessageTimestamp = envelope.timestamp,
                    unreadCount = (existingConv?.unreadCount ?: 0) + 1,
                    preferredTransport = transport.name
                )

                val incomingMessage = MessageEntity(
                    id = msgId,
                    conversationId = convId,
                    senderId = envelope.senderId,
                    senderName = envelope.senderName,
                    isOutgoing = false,
                    type = msgType.name,
                    textContent = envelope.textContent,
                    mediaPath = localMediaPath,
                    fileName = envelope.fileName,
                    mimeType = envelope.mimeType,
                    fileSizeBytes = envelope.fileSizeBytes,
                    voiceDurationMs = envelope.voiceDurationMs,
                    waveformCsv = envelope.waveformCsv,
                    replyToMessageId = envelope.replyToMessageId,
                    replyToSender = envelope.replyToSender,
                    replyToPreview = envelope.replyToPreview,
                    isForwarded = envelope.isForwarded,
                    hopTrace = envelope.routeTraceCsv,
                    deliveryStatus = DeliveryStatus.DELIVERED.name,
                    transportUsed = transport.name,
                    timestamp = envelope.timestamp
                )

                dao.persistIncomingMessageAtomic(incomingMessage, updatedConv, receivedFileEntity)
            }
        }
    }

    /**
     * Streams incoming FILE_CHUNK frames straight to disk (.part temp file) using RandomAccessFile,
     * avoiding accumulating multi-MB byte arrays in heap memory.
     */
    private suspend fun handleIncomingFileChunk(envelope: WireEnvelope, transport: TransportType) {
        val txId = envelope.transferId ?: return
        val msgId = envelope.messageId.ifBlank { UUID.randomUUID().toString() }
        if (dao.getMessageById(msgId) != null) {
            return // Already completed this file transfer
        }
        val chunkBytes = envelope.payloadBase64?.let {
            runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull()
        } ?: return

        val mime = envelope.mimeType ?: "application/octet-stream"
        val fileName = sanitizeFileName(envelope.fileName ?: "nexus_file_${System.currentTimeMillis()}")
        val msgType = classifyMessageType(mime, fileName)

        val assembler = incomingAssemblers.getOrPut(txId) {
            IncomingChunkAssembler(
                transferId = txId,
                fileName = fileName,
                mimeType = mime,
                totalChunks = envelope.totalChunks.coerceAtLeast(1),
                totalBytes = envelope.fileSizeBytes,
                senderId = envelope.senderId,
                senderName = envelope.senderName,
                senderAvatarIndex = envelope.senderAvatarIndex,
                conversationId = envelope.conversationId,
                conversationTitle = envelope.conversationTitle,
                isGroup = envelope.isGroup,
                groupPeerIdsCsv = envelope.groupPeerIdsCsv,
                messageId = msgId,
                msgType = msgType,
                mediaWidth = envelope.mediaWidth,
                mediaHeight = envelope.mediaHeight,
                timestamp = envelope.timestamp,
                partFile = File(tempTransferDir, "${txId}.part")
            )
        }

        val isComplete: Boolean
        val receivedBytesNow: Long
        val pctNow: Int

        synchronized(assembler.writeLock) {
            assembler.lastChunkAtMs = System.currentTimeMillis()
            if (envelope.chunkIndex in 0 until assembler.totalChunks && !assembler.receivedChunks.get(envelope.chunkIndex)) {
                RandomAccessFile(assembler.partFile, "rw").use { raf ->
                    raf.seek(envelope.chunkIndex.toLong() * CHUNK_SIZE_BYTES)
                    raf.write(chunkBytes)
                }
                assembler.receivedChunks.set(envelope.chunkIndex)
                assembler.receivedBytesCount += chunkBytes.size.toLong()
            }
            receivedBytesNow = assembler.receivedBytesCount
            val cardinality = assembler.receivedChunks.cardinality()
            pctNow = ((cardinality * 100) / assembler.totalChunks).coerceIn(0, 100)
            isComplete = cardinality >= assembler.totalChunks
        }

        val elapsedSec = ((System.currentTimeMillis() - assembler.startedAtMs).coerceAtLeast(100L)) / 1000.0
        val speed = (receivedBytesNow / elapsedSec).toLong()

        upsertTransferState(
            TransferItemState(
                transferId = txId,
                conversationId = assembler.conversationId,
                messageId = assembler.messageId,
                fileName = fileName,
                mimeType = mime,
                totalBytes = assembler.totalBytes,
                transferredBytes = receivedBytesNow,
                progressPercent = pctNow,
                speedBytesPerSec = speed,
                isOutgoing = false,
                state = if (isComplete) TransferState.COMPLETED else TransferState.IN_PROGRESS
            )
        )

        if (isComplete) {
            if (incomingAssemblers.remove(txId) == null) return
            val destFolder = if (msgType == MessageType.IMAGE) mediaDir else filesDir
            val dest = File(destFolder, "${UUID.randomUUID()}_$fileName")
            if (!assembler.partFile.renameTo(dest)) {
                assembler.partFile.copyTo(dest, overwrite = true)
                runCatching { assembler.partFile.delete() }
            }

            val convId = if (assembler.isGroup && assembler.conversationId.isNotBlank()) {
                assembler.conversationId
            } else {
                directConversationId(assembler.senderId)
            }
            val existingConv = dao.getConversation(convId)
            val convTitle = if (assembler.isGroup) {
                assembler.conversationTitle.ifBlank { "Group Mesh" }
            } else {
                assembler.senderName
            }
            val preview = when (msgType) {
                MessageType.IMAGE -> "Photo: $fileName"
                MessageType.VIDEO -> "Video: $fileName"
                MessageType.AUDIO -> "Audio: $fileName"
                else -> "File: $fileName"
            }

            val updatedConv = (existingConv ?: ConversationEntity(
                id = convId,
                title = convTitle,
                isGroup = assembler.isGroup,
                peerIdsCsv = assembler.groupPeerIdsCsv.ifBlank { assembler.senderId },
                avatarIndex = assembler.senderAvatarIndex
            )).copy(
                title = convTitle,
                lastMessagePreview = preview,
                lastMessageTimestamp = assembler.timestamp,
                unreadCount = (existingConv?.unreadCount ?: 0) + 1,
                preferredTransport = transport.name
            )

            val messageEntity = MessageEntity(
                id = assembler.messageId,
                conversationId = convId,
                senderId = assembler.senderId,
                senderName = assembler.senderName,
                isOutgoing = false,
                type = msgType.name,
                textContent = envelope.textContent.ifBlank { fileName },
                mediaPath = dest.absolutePath,
                fileName = fileName,
                mimeType = mime,
                fileSizeBytes = dest.length(),
                mediaWidth = assembler.mediaWidth,
                mediaHeight = assembler.mediaHeight,
                transferProgress = 100,
                transferState = TransferState.COMPLETED.name,
                hopTrace = envelope.routeTraceCsv,
                deliveryStatus = DeliveryStatus.DELIVERED.name,
                transportUsed = transport.name,
                timestamp = assembler.timestamp
            )

            val receivedFile = ReceivedFileEntity(
                id = assembler.messageId,
                fileName = fileName,
                filePath = dest.absolutePath,
                mimeType = mime,
                sizeBytes = dest.length(),
                senderName = assembler.senderName,
                receivedTimestamp = assembler.timestamp,
                isImage = msgType == MessageType.IMAGE,
                category = classifyFileCategory(mime, fileName)
            )

            val persisted = dao.persistIncomingMessageAtomic(messageEntity, updatedConv, receivedFile)
            if (persisted) {
                val profile = userProfile.first()
                meshEngine.sendUnicastToPeer(
                    assembler.senderId,
                    WireEnvelope(
                        packetId = UUID.randomUUID().toString(),
                        type = PacketType.DELIVERY_ACK.name,
                        senderId = profile.nodeId,
                        senderName = profile.nickname,
                        senderDevice = Build.MODEL ?: "Android",
                        targetId = assembler.senderId,
                        conversationId = convId,
                        messageId = assembler.messageId,
                        routeTraceCsv = envelope.routeTraceCsv
                    )
                )
            }
        }
    }

    private fun upsertTransferState(item: TransferItemState) {
        _transfers.update { list ->
            val others = list.filterNot { it.transferId == item.transferId }
            (listOf(item) + others).take(30)
        }
    }

    private suspend fun ensureDirectConversation(peer: PeerNode) {
        val convId = directConversationId(peer.peerId)
        dao.insertConversationIfAbsent(
            ConversationEntity(
                id = convId,
                title = peer.displayName,
                isGroup = false,
                peerIdsCsv = peer.peerId,
                lastMessagePreview = "Linked via ${peer.transport.displayName}",
                lastMessageTimestamp = System.currentTimeMillis(),
                unreadCount = 0,
                preferredTransport = peer.transport.name,
                avatarIndex = peer.avatarIndex
            )
        )
    }

    private fun resolveUriMetadata(uri: Uri, fallbackName: String): Pair<String, String> {
        val resolver = context.contentResolver
        var displayName = fallbackName
        runCatching {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    displayName = cursor.getString(idx) ?: fallbackName
                }
            }
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return sanitizeFileName(displayName) to mime
    }

    /**
     * Streams a ContentResolver URI straight to a local File with a 16KB buffer, enforcing MAX_OFFLINE_FILE_BYTES.
     */
    private fun copyUriToFileStreaming(
        uri: Uri,
        destFolder: File,
        fallbackName: String
    ): Triple<String, String, File>? {
        return runCatching {
            val (displayName, mime) = resolveUriMetadata(uri, fallbackName)
            val destFile = File(destFolder, "${UUID.randomUUID()}_$displayName")
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            var totalCopied = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        totalCopied += read
                        if (totalCopied > MAX_OFFLINE_FILE_BYTES) {
                            destFile.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            } ?: return null
            Triple(displayName, mime, destFile)
        }.getOrNull()
    }

    private fun sanitizeFileName(raw: String): String {
        return raw.replace("/", "_").replace("\\", "_").replace("..", "_").take(120)
    }

    private fun classifyMessageType(mimeType: String, fileName: String): MessageType {
        val lower = fileName.lowercase()
        return when {
            mimeType.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") -> MessageType.IMAGE
            mimeType.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") -> MessageType.VIDEO
            mimeType.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".wav") -> MessageType.AUDIO
            else -> MessageType.FILE
        }
    }

    private fun classifyFileCategory(mimeType: String, fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            mimeType.startsWith("image/") || lower.endsWith(".jpg") || lower.endsWith(".png") || lower.endsWith(".webp") -> "IMAGE"
            mimeType.startsWith("video/") || lower.endsWith(".mp4") || lower.endsWith(".mkv") -> "VIDEO"
            mimeType.startsWith("audio/") || lower.endsWith(".mp3") || lower.endsWith(".m4a") -> "AUDIO"
            lower.endsWith(".apk") -> "APK"
            lower.endsWith(".zip") || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".7z") -> "ARCHIVE"
            lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") -> "DOC"
            else -> "FILE"
        }
    }

    private fun previewForMessage(msg: MessageEntity): String = when (msg.type) {
        MessageType.IMAGE.name -> "Photo"
        MessageType.VIDEO.name -> "Video"
        MessageType.VOICE.name -> "Voice message"
        MessageType.FILE.name -> msg.fileName ?: "File"
        else -> msg.textContent
    }

    private fun directConversationId(peerId: String): String = "dm_$peerId"

    private fun parsePeerIds(csv: String): List<String> =
        csv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
