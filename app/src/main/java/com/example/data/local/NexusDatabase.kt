package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        PeerContactEntity::class,
        ReceivedFileEntity::class,
        CallRecordEntity::class,
        PendingRelayEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class NexusDatabase : RoomDatabase() {
    abstract fun nexusDao(): NexusDao

    companion object {
        @Volatile
        private var INSTANCE: NexusDatabase? = null

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_isArchived_lastMessageTimestamp ON conversations(isArchived, lastMessageTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_lastMessageTimestamp ON conversations(lastMessageTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId_timestamp ON messages(conversationId, timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_deliveryStatus ON messages(deliveryStatus)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_peer_contacts_lastConnectedTimestamp ON peer_contacts(lastConnectedTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_received_files_category_receivedTimestamp ON received_files(category, receivedTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_received_files_receivedTimestamp ON received_files(receivedTimestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_call_records_timestamp ON call_records(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_relays_targetPeerId_createdAtMs ON pending_relays(targetPeerId, createdAtMs)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_relays_createdAtMs ON pending_relays(createdAtMs)")
            }
        }

        fun getInstance(context: Context): NexusDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NexusDatabase::class.java,
                    "nexus_mesh.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
