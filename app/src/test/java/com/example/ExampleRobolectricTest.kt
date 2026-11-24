package com.example

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CallRecordEntity
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.NexusDatabase
import com.example.data.local.PendingRelayEntity
import com.example.data.local.ReceivedFileEntity
import com.example.domain.model.DeliveryStatus
import com.example.domain.model.MessageType
import com.example.domain.model.TransportType
import com.example.network.PacketType
import com.example.network.SocketFrameCodec
import com.example.network.WireEnvelope
import com.example.ui.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    private lateinit var database: NexusDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NexusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NEXUS", appName)
    }

    @Test
    fun `socket frame codec round trip`() {
        val original = WireEnvelope(
            packetId = "pkt-1",
            type = PacketType.TEXT_MESSAGE.name,
            senderId = "NX-1234",
            senderName = "Alpha",
            senderDevice = "Pixel",
            conversationId = "dm_NX-1234",
            messageId = "msg-1",
            textContent = "Offline mesh test payload",
            replyToMessageId = "msg-0",
            replyToSender = "Bravo",
            replyToPreview = "Previous message"
        )
        val out = ByteArrayOutputStream()
        SocketFrameCodec.writeFrame(out, original)
        val decoded = SocketFrameCodec.readFrame(ByteArrayInputStream(out.toByteArray()))
        assertNotNull(decoded)
        assertEquals(original.textContent, decoded?.first?.textContent)
        assertEquals(original.senderId, decoded?.first?.senderId)
        assertEquals("msg-0", decoded?.first?.replyToMessageId)
    }

    @Test
    fun `room database persists messages edits deletes call records and store-and-forward relays`() = runBlocking {
        val dao = database.nexusDao()
        dao.upsertConversation(
            ConversationEntity(
                id = "dm_peer1",
                title = "Peer One",
                isGroup = false,
                peerIdsCsv = "peer1"
            )
        )
        dao.insertMessage(
            MessageEntity(
                id = "m1",
                conversationId = "dm_peer1",
                senderId = "me",
                senderName = "Me",
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = "Initial message",
                deliveryStatus = DeliveryStatus.QUEUED.name,
                transportUsed = TransportType.WIFI_DIRECT.name
            )
        )
        dao.editMessageText("m1", "Edited message")
        val edited = dao.getMessageById("m1")
        assertNotNull(edited)
        assertEquals("Edited message", edited!!.textContent)
        assertTrue(edited.isEdited)

        dao.softDeleteMessage("m1")
        val deleted = dao.getMessageById("m1")
        assertTrue(deleted!!.isDeleted)

        dao.insertCallRecord(
            CallRecordEntity(
                id = "c1",
                conversationId = "dm_peer1",
                title = "Peer One",
                callType = "VIDEO",
                direction = "OUTGOING",
                isGroup = false,
                participantsCsv = "Peer One",
                durationSeconds = 42L,
                transportUsed = TransportType.WIFI_DIRECT.name
            )
        )
        val calls = dao.observeCallHistory().first()
        assertEquals(1, calls.size)
        assertEquals(42L, calls.first().durationSeconds)

        dao.insertPendingRelay(
            PendingRelayEntity(
                packetId = "pkt-q1",
                targetPeerId = "peer1",
                conversationId = "dm_peer1",
                messageId = "m1",
                envelopeJson = "{}"
            )
        )
        val relays = dao.getAllPendingRelays()
        assertEquals(1, relays.size)
        assertEquals("pkt-q1", relays.first().packetId)
    }

    @Test
    fun `persistIncomingMessageAtomic deduplicates duplicate message ids and preserves unread count`() = runBlocking {
        val dao = database.nexusDao()
        val conv = ConversationEntity(
            id = "dm_peer2",
            title = "Peer Two",
            isGroup = false,
            peerIdsCsv = "peer2",
            unreadCount = 1,
            lastMessagePreview = "Photo: img.jpg"
        )
        val msg = MessageEntity(
            id = "dup-msg-1",
            conversationId = "dm_peer2",
            senderId = "peer2",
            senderName = "Peer Two",
            isOutgoing = false,
            type = MessageType.IMAGE.name,
            textContent = "img.jpg",
            deliveryStatus = DeliveryStatus.DELIVERED.name,
            transportUsed = TransportType.HOTSPOT.name
        )
        val file = ReceivedFileEntity(
            id = "dup-msg-1",
            fileName = "img.jpg",
            filePath = "/tmp/img.jpg",
            mimeType = "image/jpeg",
            sizeBytes = 1024L,
            senderName = "Peer Two",
            receivedTimestamp = System.currentTimeMillis(),
            isImage = true
        )

        val firstInsert = dao.persistIncomingMessageAtomic(msg, conv, file)
        assertTrue(firstInsert)

        val duplicateConv = conv.copy(unreadCount = 2)
        val secondInsert = dao.persistIncomingMessageAtomic(msg, duplicateConv, file)
        assertFalse(secondInsert)

        val persistedConv = dao.getConversation("dm_peer2")
        assertNotNull(persistedConv)
        assertEquals(1, persistedConv!!.unreadCount)
    }

    @Test
    fun `pruneExpiredRelays removes exhausted or stale relays and deleteConversationAndMessages cleans up`() = runBlocking {
        val dao = database.nexusDao()
        val now = System.currentTimeMillis()

        dao.upsertConversation(ConversationEntity(id = "conv_del", title = "Group", isGroup = true))
        dao.insertMessage(
            MessageEntity(
                id = "m_del",
                conversationId = "conv_del",
                senderId = "me",
                senderName = "Me",
                isOutgoing = true,
                type = MessageType.TEXT.name,
                textContent = "hello",
                deliveryStatus = DeliveryStatus.SENT.name,
                transportUsed = TransportType.HOTSPOT.name
            )
        )
        dao.insertPendingRelay(
            PendingRelayEntity(
                packetId = "relay_fresh",
                targetPeerId = "p1",
                conversationId = "conv_other",
                messageId = "m_other",
                envelopeJson = "{}",
                createdAtMs = now,
                retryCount = 1
            )
        )
        dao.insertPendingRelay(
            PendingRelayEntity(
                packetId = "relay_exhausted",
                targetPeerId = "p1",
                conversationId = "conv_other",
                messageId = "m_other_2",
                envelopeJson = "{}",
                createdAtMs = now,
                retryCount = 20
            )
        )
        dao.insertPendingRelay(
            PendingRelayEntity(
                packetId = "relay_expired",
                targetPeerId = "p1",
                conversationId = "conv_del",
                messageId = "m_del",
                envelopeJson = "{}",
                createdAtMs = now - 10_000_000L,
                retryCount = 0
            )
        )

        dao.pruneExpiredRelays(maxRetries = 20, minCreatedAtMs = now - 5_000_000L)
        val remaining = dao.getAllPendingRelays()
        assertEquals(1, remaining.size)
        assertEquals("relay_fresh", remaining.first().packetId)

        dao.deleteConversationAndMessages("conv_del")
        assertNull(dao.getConversation("conv_del"))
        assertNull(dao.getMessageById("m_del"))
    }

    @Test
    fun `migration 2 to 3 creates all expected indices cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, isGroup INTEGER NOT NULL, peerIdsCsv TEXT NOT NULL, lastMessagePreview TEXT NOT NULL, lastMessageTimestamp INTEGER NOT NULL, unreadCount INTEGER NOT NULL, avatarIndex INTEGER NOT NULL, preferredTransport TEXT NOT NULL, isArchived INTEGER NOT NULL, isMuted INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, senderId TEXT NOT NULL, senderName TEXT NOT NULL, isOutgoing INTEGER NOT NULL, type TEXT NOT NULL, textContent TEXT NOT NULL, mediaPath TEXT, fileName TEXT, mimeType TEXT, fileSizeBytes INTEGER NOT NULL, mediaWidth INTEGER NOT NULL, mediaHeight INTEGER NOT NULL, voiceDurationMs INTEGER NOT NULL, waveformCsv TEXT NOT NULL, reactionsCsv TEXT NOT NULL, replyToMessageId TEXT, replyToSender TEXT, replyToPreview TEXT, isForwarded INTEGER NOT NULL, isEdited INTEGER NOT NULL, isDeleted INTEGER NOT NULL, transferProgress INTEGER NOT NULL, transferState TEXT NOT NULL, transferSpeedBps INTEGER NOT NULL, hopTrace TEXT NOT NULL, deliveryStatus TEXT NOT NULL, transportUsed TEXT NOT NULL, timestamp INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE peer_contacts (peerId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, deviceName TEXT NOT NULL, avatarIndex INTEGER NOT NULL, statusText TEXT NOT NULL, lastTransport TEXT NOT NULL, lastConnectedTimestamp INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE received_files (id TEXT NOT NULL PRIMARY KEY, fileName TEXT NOT NULL, filePath TEXT NOT NULL, mimeType TEXT NOT NULL, sizeBytes INTEGER NOT NULL, senderName TEXT NOT NULL, receivedTimestamp INTEGER NOT NULL, isImage INTEGER NOT NULL, category TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE call_records (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, title TEXT NOT NULL, callType TEXT NOT NULL, direction TEXT NOT NULL, isGroup INTEGER NOT NULL, participantsCsv TEXT NOT NULL, durationSeconds INTEGER NOT NULL, timestamp INTEGER NOT NULL, transportUsed TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE pending_relays (packetId TEXT NOT NULL PRIMARY KEY, targetPeerId TEXT NOT NULL, conversationId TEXT NOT NULL, messageId TEXT NOT NULL, envelopeJson TEXT NOT NULL, createdAtMs INTEGER NOT NULL, retryCount INTEGER NOT NULL)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        val db = helper.writableDatabase
        NexusDatabase.MIGRATION_2_3.migrate(db)

        val indices = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'index'").use { cursor ->
            while (cursor.moveToNext()) {
                indices.add(cursor.getString(0))
            }
        }
        helper.close()

        val expected = listOf(
            "index_conversations_isArchived_lastMessageTimestamp",
            "index_conversations_lastMessageTimestamp",
            "index_messages_conversationId_timestamp",
            "index_messages_deliveryStatus",
            "index_peer_contacts_lastConnectedTimestamp",
            "index_received_files_category_receivedTimestamp",
            "index_received_files_receivedTimestamp",
            "index_call_records_timestamp",
            "index_pending_relays_targetPeerId_createdAtMs",
            "index_pending_relays_createdAtMs"
        )
        expected.forEach { idx ->
            assertTrue("Missing index $idx", idx in indices)
        }
    }

    @Test
    fun `format utils thread-safe formatting across concurrent coroutines`() = runBlocking<Unit> {
        val jobs = (1..32).map { i ->
            async(Dispatchers.Default) {
                repeat(25) { step ->
                    val formatted = FormatUtils.formatTimestamp(1_700_000_000_000L + (i * 1000L + step))
                    assertTrue(formatted.isNotBlank())
                    assertTrue(FormatUtils.formatDuration(65_000L).contains(":"))
                    assertTrue(FormatUtils.formatBytes(1_500_000L).contains("MB"))
                }
            }
        }
        jobs.awaitAll()
    }
}

