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

@Dao
interface DeskDao {
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
        ],
    version = 2,
    exportSchema = true,
)
abstract class DeskDatabase : RoomDatabase() {
    abstract fun dao(): DeskDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `daily_work_times` (`date` TEXT NOT NULL, `startMinute` INTEGER NOT NULL, `endMinute` INTEGER NOT NULL, PRIMARY KEY(`date`))"
                    )
                }
            }

        fun open(context: Context): DeskDatabase =
            Room.databaseBuilder(
                    context.applicationContext,
                    DeskDatabase::class.java,
                    "whaletodo.db",
                )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
