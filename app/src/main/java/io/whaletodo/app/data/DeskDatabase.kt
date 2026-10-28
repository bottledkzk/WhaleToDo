package io.whaletodo.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey val id: String,
    val title: String,
    val minutes: Int = 0,
    val deadline: Long = 0,
    val importance: Int = 3,
    val done: Boolean = false,
    val created: Long = System.currentTimeMillis(),
    val note: String = "",
    val chunkMinutes: Int = 60,
    val splittable: Boolean = true,
    @ColumnInfo(defaultValue = "'none'") val category: String = "none",
)

@Entity(tableName = "blocks")
data class BlockRow(
    @PrimaryKey val id: String,
    val taskId: String,
    val start: Long,
    val end: Long,
    val locked: Boolean = false,
    val done: Boolean = false,
)

@Entity(tableName = "checkins")
data class CheckInRow(
    @PrimaryKey val id: String,
    val taskId: String,
    val completedAt: Long,
    val rating: Int,
)

@Entity(tableName = "settings")
data class SettingRow(@PrimaryKey val id: Int = 1, val startHour: Int = 9, val endHour: Int = 21)

@Entity(tableName = "daily_work_times")
data class DailyWorkTimeRow(@PrimaryKey val date: String, val startMinute: Int, val endMinute: Int)

@Entity(tableName = "agent_sessions")
data class AgentSessionRow(@PrimaryKey val id: String, val ownerId: String, val title: String,
    val created: Long, val updated: Long, val archived: Boolean = false)
@Entity(tableName = "agent_messages", indices = [Index("sessionId")])
data class AgentMessageRow(@PrimaryKey val id: String, val sessionId: String, val ownerId: String,
    val role: String, val mode: String, val text: String, val created: Long,
    val totalTokens: Int = 0, val promptTokens: Int? = null, val completionTokens: Int? = null, val chargedPoints: Int? = null, val status: String = "complete",
    val snapshotHash: String? = null, val modelCode: String? = null, val editGranted: Boolean = false)
@Entity(tableName = "agent_receipts", primaryKeys = ["id", "ownerId"])
data class AgentReceiptRow(val id: String, val ownerId: String, val appliedAt: Long)

@Entity(tableName="ui_preferences",primaryKeys=["ownerId","key"])
data class UiPreferenceRow(val ownerId:String,val key:String,val value:String)
@Entity(tableName="task_reminders",primaryKeys=["ownerId","taskId"])
data class TaskReminderRow(val ownerId:String,val taskId:String,val enabled:Boolean=false,val method:String="message",val timing:String="start")
@Entity(tableName="diaries",primaryKeys=["ownerId","date"])
data class DiaryRow(val ownerId:String,val date:String,val content:String,val updatedAt:Long,val sourceMessageId:String?=null)

@Dao
interface DeskDao {
    @Query("SELECT * FROM ui_preferences WHERE ownerId=:owner ORDER BY `key`") fun observeOptions(owner:String):Flow<List<UiPreferenceRow>>
    @Query("SELECT * FROM ui_preferences WHERE ownerId='device' ORDER BY `key`") suspend fun deviceOptions():List<UiPreferenceRow>
    @Query("SELECT * FROM ui_preferences WHERE ownerId=:owner ORDER BY `key`") suspend fun options(owner:String):List<UiPreferenceRow>
    @Upsert suspend fun saveOptions(options:List<UiPreferenceRow>)
    @Upsert suspend fun save(option:UiPreferenceRow)
    @Query("DELETE FROM ui_preferences WHERE ownerId=:owner AND `key`=:key") suspend fun removeOption(owner:String,key:String)
    @Query("SELECT * FROM task_reminders WHERE ownerId=:owner AND taskId=:task") suspend fun reminder(owner:String,task:String):TaskReminderRow?
    @Query("SELECT * FROM task_reminders WHERE enabled=1") suspend fun enabledReminders(): List<TaskReminderRow>
    @Upsert suspend fun save(reminder:TaskReminderRow)
    @Query("SELECT * FROM diaries WHERE ownerId=:owner AND date=:date") suspend fun diary(owner:String,date:String):DiaryRow?
    @Upsert suspend fun save(diary:DiaryRow)
    @Query("DELETE FROM diaries WHERE ownerId=:owner AND date=:date AND updatedAt=:stamp") suspend fun deleteDiary(owner:String,date:String,stamp:Long):Int

    @Query("SELECT * FROM tasks ORDER BY done, created DESC")
    fun observeTasks(): Flow<List<TaskRow>>

    @Query("SELECT * FROM blocks ORDER BY start") fun observeBlocks(): Flow<List<BlockRow>>

    @Query("SELECT * FROM checkins ORDER BY completedAt DESC")
    fun observeCheckIns(): Flow<List<CheckInRow>>

    @Query("SELECT * FROM settings WHERE id=1") fun observeSettings(): Flow<SettingRow?>

    @Query("SELECT * FROM tasks ORDER BY done, created DESC") suspend fun tasks(): List<TaskRow>

    @Query("SELECT * FROM blocks ORDER BY start") suspend fun blocks(): List<BlockRow>

    @Query("SELECT * FROM settings WHERE id=1") suspend fun settings(): SettingRow?

    @Query("SELECT * FROM daily_work_times ORDER BY date")
    fun observeWorkTimes(): Flow<List<DailyWorkTimeRow>>

    @Query("SELECT * FROM daily_work_times ORDER BY date")
    suspend fun workTimes(): List<DailyWorkTimeRow>

    @Query("SELECT * FROM agent_sessions WHERE ownerId=:owner AND archived=0 ORDER BY updated DESC")
    suspend fun sessions(owner: String): List<AgentSessionRow>
    @Query("SELECT * FROM agent_messages WHERE sessionId=:session AND ownerId=:owner ORDER BY created,id")
    suspend fun messages(session: String, owner: String): List<AgentMessageRow>
    @Upsert suspend fun save(session: AgentSessionRow)
    @Insert suspend fun addMessage(message: AgentMessageRow)
    @Query("UPDATE agent_messages SET status=:status WHERE id=:id AND ownerId=:owner") suspend fun requestStatus(id: String, owner: String, status: String)
    @Query("UPDATE agent_sessions SET archived=1 WHERE id=:id AND ownerId=:owner") suspend fun archiveSession(id: String, owner: String)
    @Query("SELECT COUNT(*) FROM agent_receipts WHERE id=:id AND ownerId=:owner") suspend fun receiptCount(id: String, owner: String): Int
    @Insert suspend fun addReceipt(receipt: AgentReceiptRow)

    @Upsert suspend fun save(workTime: DailyWorkTimeRow)

    @Upsert suspend fun save(task: TaskRow)

    @Upsert suspend fun save(setting: SettingRow)

    @Insert suspend fun addCheckIn(row: CheckInRow)

    @Query("UPDATE blocks SET done=:done WHERE taskId=:id")
    suspend fun setBlocksDone(id: String, done: Boolean)

    @Query("DELETE FROM blocks") suspend fun clearBlocks()

    @Insert suspend fun insertBlocks(blocks: List<BlockRow>)
}

@Database(
    entities =
        [
            TaskRow::class,
            BlockRow::class,
            CheckInRow::class,
            SettingRow::class,
            DailyWorkTimeRow::class,
            AgentSessionRow::class, AgentMessageRow::class, AgentReceiptRow::class,
            UiPreferenceRow::class, TaskReminderRow::class, DiaryRow::class,
            SyncBatchRow::class, SyncOutboxRow::class, SyncStateRow::class,
            SyncConflictRow::class, SyncIdMappingRow::class, SyncTombstoneRow::class,
        ],
    version = 5,
    exportSchema = true,
)
abstract class DeskDatabase : RoomDatabase() {
    abstract fun dao(): DeskDao

    abstract fun syncDao(): SyncDao

    companion object {
        private const val SYNC_OUTBOX_HEAD_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_head` ON `sync_outbox`(`ownerId`, `objectType`, `objectId`) WHERE `isHead`=1"

        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_work_times` (`date` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                    )
                }
            }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN category TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_sessions (id TEXT NOT NULL PRIMARY KEY, ownerId TEXT NOT NULL, title TEXT NOT NULL, created INTEGER NOT NULL, updated INTEGER NOT NULL, archived INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_messages (id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, ownerId TEXT NOT NULL, role TEXT NOT NULL, mode TEXT NOT NULL, text TEXT NOT NULL, created INTEGER NOT NULL, totalTokens INTEGER NOT NULL, promptTokens INTEGER, completionTokens INTEGER, chargedPoints INTEGER, status TEXT NOT NULL, snapshotHash TEXT, modelCode TEXT, editGranted INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_agent_messages_sessionId ON agent_messages(sessionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS agent_receipts (id TEXT NOT NULL, ownerId TEXT NOT NULL, appliedAt INTEGER NOT NULL, PRIMARY KEY(id,ownerId))")
            }
        }

        val MIGRATION_3_4 = object : Migration(3,4) {
            override fun migrate(db:SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS ui_preferences (ownerId TEXT NOT NULL, `key` TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(ownerId,`key`))")
                db.execSQL("CREATE TABLE IF NOT EXISTS task_reminders (ownerId TEXT NOT NULL, taskId TEXT NOT NULL, enabled INTEGER NOT NULL, method TEXT NOT NULL, timing TEXT NOT NULL, PRIMARY KEY(ownerId,taskId))")
                db.execSQL("CREATE TABLE IF NOT EXISTS diaries (ownerId TEXT NOT NULL, date TEXT NOT NULL, content TEXT NOT NULL, updatedAt INTEGER NOT NULL, sourceMessageId TEXT, PRIMARY KEY(ownerId,date))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_batches` (" +
                        "`batchId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, " +
                        "`createdSessionId` TEXT NOT NULL, `attemptSessionId` TEXT, " +
                        "`deviceId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
                        "`bodySha256` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, `attempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastAttemptAt` INTEGER, `lastErrorCode` TEXT, " +
                        "PRIMARY KEY(`batchId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_batches_owner_state` " +
                        "ON `sync_batches`(`ownerId`, `state`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_outbox` (" +
                        "`opId` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `objectType` TEXT NOT NULL, " +
                        "`objectId` TEXT NOT NULL, `op` TEXT NOT NULL, `payloadJson` TEXT, " +
                        "`baseVersion` INTEGER, `idempotencyKey` TEXT NOT NULL, `parentOpId` TEXT, " +
                        "`isHead` INTEGER NOT NULL DEFAULT 1, `batchId` TEXT, `state` TEXT NOT NULL, " +
                        "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                        "`nextRetryAt` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL, " +
                        "`lastErrorCode` TEXT, PRIMARY KEY(`opId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_owner_state_retry` " +
                        "ON `sync_outbox`(`ownerId`, `state`, `nextRetryAt`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_owner_batch` " +
                        "ON `sync_outbox`(`ownerId`, `batchId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_outbox_parent` " +
                        "ON `sync_outbox`(`parentOpId`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_state` (" +
                        "`ownerId` TEXT NOT NULL, `cursor` INTEGER NOT NULL DEFAULT 0, " +
                        "`lastPushAt` INTEGER, `lastPullAt` INTEGER, `deviceId` TEXT NOT NULL, " +
                        "PRIMARY KEY(`ownerId`))"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_conflicts` (" +
                        "`ownerId` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, " +
                        "`localPayloadJson` TEXT, `localBaseVersion` INTEGER, " +
                        "`remoteKnownVersion` INTEGER, " +
                        "`remoteDetailFetched` INTEGER NOT NULL DEFAULT 0, " +
                        "`remotePayloadJson` TEXT, `remoteDeleted` INTEGER, " +
                        "`reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`state` TEXT NOT NULL, PRIMARY KEY(`ownerId`, `objectType`, `objectId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_conflicts_owner_state` " +
                        "ON `sync_conflicts`(`ownerId`, `state`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_id_mappings` (" +
                        "`ownerId` TEXT NOT NULL, `objectType` TEXT NOT NULL, " +
                        "`guestObjectId` TEXT NOT NULL, `accountObjectId` TEXT NOT NULL, " +
                        "`naturalKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`, `objectType`, `guestObjectId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_id_mappings_owner_natural` " +
                        "ON `sync_id_mappings`(`ownerId`, `naturalKey`)"
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (" +
                        "`ownerId` TEXT NOT NULL, `objectType` TEXT NOT NULL, `objectId` TEXT NOT NULL, " +
                        "`deletedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ownerId`, `objectType`, `objectId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sync_tombstones_owner_deleted` " +
                        "ON `sync_tombstones`(`ownerId`, `deletedAt`)"
                )
            }
        }

        fun open(context: Context): DeskDatabase =
            Room.databaseBuilder(
                    context.applicationContext,
                    DeskDatabase::class.java,
                    "whaletodo.db",
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        // Partial unique index is not representable in Room's schema JSON;
                        // create it after schema validation on every open.
                        db.execSQL(SYNC_OUTBOX_HEAD_INDEX)
                    }
                })
                .build()
    }
}
