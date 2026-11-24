package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface NexusDao {
    // Conversations
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY lastMessageTimestamp DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConversation(conversation: ConversationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversationIfAbsent(conversation: ConversationEntity)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :conversationId")
    suspend fun clearUnreadCount(conversationId: String)

    @Query("UPDATE conversations SET preferredTransport = :transport WHERE id = :conversationId")
    suspend fun updateConversationTransport(conversationId: String, transport: String)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :conversationId")
    suspend fun setConversationArchived(conversationId: String, archived: Boolean)

    @Query("UPDATE conversations SET isMuted = :muted WHERE id = :conversationId")
    suspend fun setConversationMuted(conversationId: String, muted: Boolean)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)

    @Transaction
    suspend fun createConversationWithSystemMessage(
        conversation: ConversationEntity,
        systemMessage: MessageEntity
    ) {
        upsertConversation(conversation)
        insertMessage(systemMessage)
    }

    @Transaction
    suspend fun deleteConversationAndMessages(conversationId: String) {
        deleteMessagesForConversation(conversationId)
        deletePendingRelaysForConversation(conversationId)
        deleteConversation(conversationId)
    }

    // Messages
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessageIfNew(message: MessageEntity): Long

    @Transaction
    suspend fun insertOutgoingMessageAndTouchConversation(
        message: MessageEntity,
        conversation: ConversationEntity
    ) {
        insertMessage(message)
        upsertConversation(conversation)
    }

    @Transaction
    suspend fun persistIncomingMessageAtomic(
        message: MessageEntity,
        conversation: ConversationEntity,
        receivedFile: ReceivedFileEntity?
    ): Boolean {
        val insertedRow = insertMessageIfNew(message)
        if (insertedRow == -1L) {
            return false // Duplicate message already persisted
        }
        upsertConversation(conversation)
        if (receivedFile != null) {
            insertReceivedFile(receivedFile)
        }
        return true
    }

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET deliveryStatus = :status, hopTrace = :hopTrace WHERE id = :messageId")
    suspend fun updateMessageDeliveryAndTrace(messageId: String, status: String, hopTrace: String)

    @Query("UPDATE messages SET reactionsCsv = :reactionsCsv WHERE id = :messageId")
    suspend fun updateMessageReactions(messageId: String, reactionsCsv: String)

    @Query("UPDATE messages SET textContent = :newText, isEdited = 1 WHERE id = :messageId")
    suspend fun editMessageText(messageId: String, newText: String)

    @Query("UPDATE messages SET textContent = 'Message deleted', isDeleted = 1, mediaPath = NULL WHERE id = :messageId")
    suspend fun softDeleteMessage(messageId: String)

    @Query("UPDATE messages SET transferProgress = :progress, transferState = :state, transferSpeedBps = :speedBps WHERE id = :messageId")
    suspend fun updateMessageTransferProgress(
        messageId: String,
        progress: Int,
        state: String,
        speedBps: Long
    )

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    // Peer Contacts
    @Query("SELECT * FROM peer_contacts ORDER BY lastConnectedTimestamp DESC")
    fun observePeerContacts(): Flow<List<PeerContactEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeerContact(contact: PeerContactEntity)

    // Received Files & Media Gallery
    @Query("SELECT * FROM received_files ORDER BY receivedTimestamp DESC")
    fun observeReceivedFiles(): Flow<List<ReceivedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReceivedFile(file: ReceivedFileEntity)

    @Query("DELETE FROM received_files WHERE id = :fileId")
    suspend fun deleteReceivedFile(fileId: String)

    @Query("DELETE FROM received_files")
    suspend fun clearReceivedFiles()

    // Call History
    @Query("SELECT * FROM call_records ORDER BY timestamp DESC")
    fun observeCallHistory(): Flow<List<CallRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCallRecord(record: CallRecordEntity)

    @Query("DELETE FROM call_records")
    suspend fun clearCallHistory()

    // Store-and-Forward Pending Relay Queue
    @Query("SELECT * FROM pending_relays ORDER BY createdAtMs ASC")
    fun observePendingRelays(): Flow<List<PendingRelayEntity>>

    @Query("SELECT * FROM pending_relays ORDER BY createdAtMs ASC")
    suspend fun getAllPendingRelays(): List<PendingRelayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPendingRelay(relay: PendingRelayEntity)

    @Query("DELETE FROM pending_relays WHERE packetId = :packetId")
    suspend fun deletePendingRelay(packetId: String)

    @Query("DELETE FROM pending_relays WHERE conversationId = :conversationId")
    suspend fun deletePendingRelaysForConversation(conversationId: String)

    @Query("DELETE FROM pending_relays WHERE retryCount >= :maxRetries OR createdAtMs < :minCreatedAtMs")
    suspend fun pruneExpiredRelays(maxRetries: Int, minCreatedAtMs: Long)

    @Query("UPDATE pending_relays SET retryCount = retryCount + 1 WHERE packetId = :packetId")
    suspend fun incrementRelayRetry(packetId: String)
}
