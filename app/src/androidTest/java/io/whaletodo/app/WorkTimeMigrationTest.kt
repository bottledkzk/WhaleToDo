package io.whaletodo.app

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.*
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkTimeMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeskDatabase::class.java)

    @Test
    fun migrationPreservesVersionOneRecordsAndDefaults() {
        val name = "migration-${UUID.randomUUID()}.db"
        helper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO tasks VALUES ('old','旧事项',90,1800000000000,4,0,1000,'保留备注',60,1)")
            execSQL("INSERT INTO settings VALUES (1,8,24)")
            execSQL("INSERT INTO blocks VALUES ('block','old',10000,20000,1,0)")
            execSQL("INSERT INTO checkins VALUES ('check','old',30000,4)")
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, DeskDatabase.MIGRATION_1_2).apply {
            query("SELECT title,note FROM tasks WHERE id='old'").use {
                assertTrue(it.moveToFirst())
                assertEquals("旧事项", it.getString(0))
                assertEquals("保留备注", it.getString(1))
            }
            query("SELECT endHour FROM settings").use {
                assertTrue(it.moveToFirst())
                assertEquals(24, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM daily_work_times").use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM blocks").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM checkins").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            close()
        }
    }

    @Test
    fun todayMinutesPersistAndInvalidateOldPreviewWithoutChangingDefaults() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "worktime-${UUID.randomUUID()}.db"
        var db =
            Room.databaseBuilder(context, DeskDatabase::class.java, name)
                .addMigrations(DeskDatabase.MIGRATION_1_2)
                .build()
        try {
            val repo = DeskRepository(db)
            repo.capture("分钟级时段验证")
            val preview = repo.preview()
            repo.todayWorkTime(9 * 60 + 15, 18 * 60 + 27, LocalDate.now())
            assertNull(db.dao().settings())
            var rejected = false
            try {
                repo.apply(preview)
            } catch (e: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(db.dao().blocks().isEmpty())
            db.close()
            db = Room.databaseBuilder(context, DeskDatabase::class.java, name).build()
            assertEquals(
                DailyWorkTimeRow(LocalDate.now().toString(), 555, 1107),
                db.dao().workTimes().single(),
            )
            var invalid = false
            try {
                DeskRepository(db).todayWorkTime(1200, 600, LocalDate.now())
            } catch (e: IllegalArgumentException) {
                invalid = true
            }
            assertTrue(invalid)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
