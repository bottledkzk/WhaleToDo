package io.whaletodo.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "tasks",
    primaryKeys = ["owner_id", "id"],
    indices = [Index(name = "index_tasks_owner_done_created", value = ["owner_id", "done", "created"])],
)
data class TaskRow(
    val id: String,
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
    @ColumnInfo(name = "owner_id") val ownerId: String = "guest",
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "server_version", defaultValue = "0") val serverVersion: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = created,
)

@Entity(
    tableName = "blocks",
    primaryKeys = ["owner_id", "id"],
    indices = [Index(name = "index_blocks_owner_start", value = ["owner_id", "start"])],
)
data class BlockRow(
    val id: String,
    val taskId: String,
    val start: Long,
    val end: Long,
    val locked: Boolean = false,
    val done: Boolean = false,
    @ColumnInfo(name = "owner_id") val ownerId: String = "guest",
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "server_version", defaultValue = "0") val serverVersion: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = start,
)

@Entity(
    tableName = "checkins",
    primaryKeys = ["owner_id", "id"],
    indices = [Index(name = "index_checkins_owner_completed", value = ["owner_id", "completedAt"])],
)
data class CheckInRow(
    val id: String,
    val taskId: String,
    val completedAt: Long,
    val rating: Int,
    @ColumnInfo(name = "owner_id") val ownerId: String = "guest",
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "server_version", defaultValue = "0") val serverVersion: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = completedAt,
)

@Entity(tableName = "settings", primaryKeys = ["owner_id", "id"])
data class SettingRow(
    val id: Int = 1,
    val startHour: Int = 9,
    val endHour: Int = 21,
    @ColumnInfo(name = "owner_id") val ownerId: String = "guest",
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "server_version", defaultValue = "0") val serverVersion: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    @ColumnInfo(name = "time_zone") val timeZone: String? = null,
)

@Entity(tableName = "daily_work_times", primaryKeys = ["owner_id", "date"])
data class DailyWorkTimeRow(
    val date: String,
    val startMinute: Int,
    val endMinute: Int,
    @ColumnInfo(name = "owner_id") val ownerId: String = "guest",
    @ColumnInfo(name = "client_id") val clientId: String? = null,
    @ColumnInfo(name = "server_version", defaultValue = "0") val serverVersion: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = 0,
    @ColumnInfo(name = "time_zone") val timeZone: String? = null,
)

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

    @Query("SELECT * FROM tasks WHERE owner_id=:owner ORDER BY done, created DESC")
    fun observeTasks(owner: String = "guest"): Flow<List<TaskRow>>

    @Query("SELECT * FROM blocks WHERE owner_id=:owner ORDER BY start")
    fun observeBlocks(owner: String = "guest"): Flow<List<BlockRow>>

    @Query("SELECT * FROM checkins WHERE owner_id=:owner ORDER BY completedAt DESC")
    fun observeCheckIns(owner: String = "guest"): Flow<List<CheckInRow>>

    @Query("SELECT * FROM checkins WHERE owner_id=:owner ORDER BY completedAt DESC")
    suspend fun checkIns(owner: String = "guest"): List<CheckInRow>

    @Query("SELECT * FROM settings WHERE owner_id=:owner AND id=1")
    fun observeSettings(owner: String = "guest"): Flow<SettingRow?>

    @Query("SELECT * FROM tasks WHERE owner_id=:owner ORDER BY done, created DESC")
    suspend fun tasks(owner: String = "guest"): List<TaskRow>

    @Query("SELECT * FROM blocks WHERE owner_id=:owner ORDER BY start")
    suspend fun blocks(owner: String = "guest"): List<BlockRow>

    @Query("SELECT * FROM settings WHERE owner_id=:owner AND id=1")
    suspend fun settings(owner: String = "guest"): SettingRow?

    @Query("SELECT * FROM daily_work_times WHERE owner_id=:owner ORDER BY date")
    fun observeWorkTimes(owner: String = "guest"): Flow<List<DailyWorkTimeRow>>

    @Query("SELECT * FROM daily_work_times WHERE owner_id=:owner ORDER BY date")
    suspend fun workTimes(owner: String = "guest"): List<DailyWorkTimeRow>

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

    @Upsert suspend fun save(block: BlockRow)

    @Upsert suspend fun save(setting: SettingRow)

    @Insert suspend fun addCheckIn(row: CheckInRow)

    @Query("UPDATE blocks SET done=:done WHERE owner_id=:owner AND taskId=:id")
    suspend fun setBlocksDone(id: String, done: Boolean, owner: String = "guest")

    @Query("DELETE FROM blocks WHERE owner_id=:owner")
    suspend fun clearBlocks(owner: String = "guest")

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
    version = 6,
    exportSchema = true,
)
abstract class DeskDatabase : RoomDatabase() {
    abstract fun dao(): DeskDao

    abstract fun syncDao(): SyncDao

    companion object {
        internal const val SYNC_OUTBOX_HEAD_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_outbox_head` ON `sync_outbox`(`ownerId`, `objectType`, `objectId`) WHERE `isHead`=1"

        private const val UUID_ID_SQL =
            "CASE WHEN length(`id`)=36 " +
                "AND substr(`id`,9,1)='-' AND substr(`id`,14,1)='-' " +
                "AND substr(`id`,19,1)='-' AND substr(`id`,24,1)='-' " +
                "AND `id` NOT GLOB '*[^0-9a-fA-F-]*' THEN `id` ELSE NULL END"

        /** Shared by production open() and Room instrumentation tests. */
        internal val SYNC_OUTBOX_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // Partial unique index is not representable in Room's schema JSON;
                // create it after schema validation on every open.
                db.execSQL(SYNC_OUTBOX_HEAD_INDEX)
            }
        }

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

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `tasks_new` (" +
                            "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `minutes` INTEGER NOT NULL, " +
                            "`deadline` INTEGER NOT NULL, `importance` INTEGER NOT NULL, " +
                            "`done` INTEGER NOT NULL, `created` INTEGER NOT NULL, `note` TEXT NOT NULL, " +
                            "`chunkMinutes` INTEGER NOT NULL, `splittable` INTEGER NOT NULL, " +
                            "`category` TEXT NOT NULL DEFAULT 'none', `owner_id` TEXT NOT NULL, " +
                            "`client_id` TEXT, `server_version` INTEGER NOT NULL DEFAULT 0, " +
                            "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`owner_id`,`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `blocks_new` (" +
                            "`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `start` INTEGER NOT NULL, " +
                            "`end` INTEGER NOT NULL, `locked` INTEGER NOT NULL, `done` INTEGER NOT NULL, " +
                            "`owner_id` TEXT NOT NULL, `client_id` TEXT, " +
                            "`server_version` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`owner_id`,`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `checkins_new` (" +
                            "`id` TEXT NOT NULL, `taskId` TEXT NOT NULL, `completedAt` INTEGER NOT NULL, " +
                            "`rating` INTEGER NOT NULL, `owner_id` TEXT NOT NULL, `client_id` TEXT, " +
                            "`server_version` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`owner_id`,`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `settings_new` (" +
                            "`id` INTEGER NOT NULL, `startHour` INTEGER NOT NULL, `endHour` INTEGER NOT NULL, " +
                            "`owner_id` TEXT NOT NULL, `client_id` TEXT, " +
                            "`server_version` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL, " +
                            "`time_zone` TEXT, PRIMARY KEY(`owner_id`,`id`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_work_times_new` (" +
                            "`date` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, " +
                            "`owner_id` TEXT NOT NULL, `client_id` TEXT, " +
                            "`server_version` INTEGER NOT NULL DEFAULT 0, `updated_at` INTEGER NOT NULL, " +
                            "`time_zone` TEXT, PRIMARY KEY(`owner_id`,`date`))"
                    )

                    db.execSQL(
                        "INSERT INTO `tasks_new` (`id`,`title`,`minutes`,`deadline`,`importance`,`done`," +
                            "`created`,`note`,`chunkMinutes`,`splittable`,`category`,`owner_id`,`client_id`," +
                            "`server_version`,`updated_at`) " +
                            "SELECT `id`,`title`,`minutes`,`deadline`,`importance`,`done`,`created`,`note`," +
                            "`chunkMinutes`,`splittable`,`category`,'guest',$UUID_ID_SQL,0,`created` FROM `tasks`"
                    )
                    db.execSQL(
                        "INSERT INTO `blocks_new` (`id`,`taskId`,`start`,`end`,`locked`,`done`,`owner_id`," +
                            "`client_id`,`server_version`,`updated_at`) " +
                            "SELECT `id`,`taskId`,`start`,`end`,`locked`,`done`,'guest',$UUID_ID_SQL,0,`start` " +
                            "FROM `blocks`"
                    )
                    db.execSQL(
                        "INSERT INTO `checkins_new` (`id`,`taskId`,`completedAt`,`rating`,`owner_id`," +
                            "`client_id`,`server_version`,`updated_at`) " +
                            "SELECT `id`,`taskId`,`completedAt`,`rating`,'guest',$UUID_ID_SQL,0,`completedAt` " +
                            "FROM `checkins`"
                    )
                    db.execSQL(
                        "INSERT INTO `settings_new` (`id`,`startHour`,`endHour`,`owner_id`,`client_id`," +
                            "`server_version`,`updated_at`,`time_zone`) " +
                            "SELECT `id`,`startHour`,`endHour`,'guest',NULL,0,0,NULL FROM `settings`"
                    )
                    db.execSQL(
                        "INSERT INTO `daily_work_times_new` (`date`,`startMinute`,`endMinute`,`owner_id`," +
                            "`client_id`,`server_version`,`updated_at`,`time_zone`) " +
                            "SELECT `date`,`startMinute`,`endMinute`,'guest',NULL,0,0,NULL " +
                            "FROM `daily_work_times`"
                    )

                    fun count(table: String): Int =
                        db.query("SELECT COUNT(*) FROM `$table`").use { cursor ->
                            check(cursor.moveToFirst())
                            cursor.getInt(0)
                        }

                    check(count("tasks") == count("tasks_new")) { "T57 tasks row count mismatch" }
                    check(count("blocks") == count("blocks_new")) { "T57 blocks row count mismatch" }
                    check(count("checkins") == count("checkins_new")) { "T57 checkins row count mismatch" }
                    check(count("settings") == count("settings_new")) { "T57 settings row count mismatch" }
                    check(count("daily_work_times") == count("daily_work_times_new")) {
                        "T57 daily_work_times row count mismatch"
                    }
                    check(
                        db.query(
                            "SELECT COUNT(*) FROM `blocks_new` b LEFT JOIN `tasks_new` t " +
                                "ON t.`owner_id`=b.`owner_id` AND t.`id`=b.`taskId` WHERE t.`id` IS NULL"
                        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) } == 0
                    ) { "T57 block references a missing task" }
                    check(
                        db.query(
                            "SELECT COUNT(*) FROM `checkins_new` c LEFT JOIN `tasks_new` t " +
                                "ON t.`owner_id`=c.`owner_id` AND t.`id`=c.`taskId` WHERE t.`id` IS NULL"
                        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) } == 0
                    ) { "T57 checkin references a missing task" }

                    db.execSQL("DROP TABLE `tasks`")
                    db.execSQL("DROP TABLE `blocks`")
                    db.execSQL("DROP TABLE `checkins`")
                    db.execSQL("DROP TABLE `settings`")
                    db.execSQL("DROP TABLE `daily_work_times`")
                    db.execSQL("ALTER TABLE `tasks_new` RENAME TO `tasks`")
                    db.execSQL("ALTER TABLE `blocks_new` RENAME TO `blocks`")
                    db.execSQL("ALTER TABLE `checkins_new` RENAME TO `checkins`")
                    db.execSQL("ALTER TABLE `settings_new` RENAME TO `settings`")
                    db.execSQL("ALTER TABLE `daily_work_times_new` RENAME TO `daily_work_times`")

                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_tasks_owner_done_created` " +
                            "ON `tasks`(`owner_id`,`done`,`created`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_blocks_owner_start` " +
                            "ON `blocks`(`owner_id`,`start`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_checkins_owner_completed` " +
                            "ON `checkins`(`owner_id`,`completedAt`)"
                    )

                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        fun open(context: Context): DeskDatabase =
            Room.databaseBuilder(
                    context.applicationContext,
                    DeskDatabase::class.java,
                    "whaletodo.db",
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .addCallback(SYNC_OUTBOX_CALLBACK)
                .build()
    }
}
