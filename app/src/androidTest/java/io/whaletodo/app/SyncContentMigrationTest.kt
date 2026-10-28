package io.whaletodo.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.DeskDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncContentMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeskDatabase::class.java)

    private val uuidId = "11111111-1111-4111-8111-111111111111"
    private val blockId = "22222222-2222-4222-8222-222222222222"
    private val checkinId = "33333333-3333-4333-8333-333333333333"

    @Test
    fun v5ContentMigratesToV6WithOwnerAndClientIdRules() {
        val name = "sync-content-${UUID.randomUUID()}.db"
        helper.createDatabase(name, 5).apply {
            execSQL(
                "INSERT INTO tasks VALUES " +
                    "('$uuidId','UUID任务',60,0,3,0,1000,'',60,1,'none')",
            )
            execSQL(
                "INSERT INTO tasks VALUES " +
                    "('legacy-task','旧任务',30,0,2,0,2000,'旧备注',30,1,'study')",
            )
            execSQL("INSERT INTO blocks VALUES ('$blockId','$uuidId',1000,2000,1,0)")
            execSQL("INSERT INTO checkins VALUES ('$checkinId','$uuidId',3000,4)")
            execSQL("INSERT INTO settings VALUES (1,8,22)")
            execSQL("INSERT INTO daily_work_times VALUES ('2026-10-28',480,1200)")
            close()
        }

        helper.runMigrationsAndValidate(name, 6, true, DeskDatabase.MIGRATION_5_6).apply {
            assertEquals(2, count("tasks"))
            assertEquals(1, count("blocks"))
            assertEquals(1, count("checkins"))
            assertEquals(1, count("settings"))
            assertEquals(1, count("daily_work_times"))

            query(
                "SELECT owner_id,client_id,server_version,updated_at FROM tasks " +
                    "WHERE id='$uuidId'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertEquals(uuidId, it.getString(1))
                assertEquals(0, it.getInt(2))
                assertEquals(1000L, it.getLong(3))
            }
            query(
                "SELECT owner_id,client_id,server_version,updated_at FROM tasks " +
                    "WHERE id='legacy-task'",
            ).use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertNull(it.getString(1))
                assertEquals(0, it.getInt(2))
                assertEquals(2000L, it.getLong(3))
            }
            query("SELECT owner_id,client_id,updated_at FROM blocks WHERE id='$blockId'").use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertEquals(blockId, it.getString(1))
                assertEquals(1000L, it.getLong(2))
            }
            query("SELECT owner_id,client_id,updated_at FROM checkins WHERE id='$checkinId'").use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertEquals(checkinId, it.getString(1))
                assertEquals(3000L, it.getLong(2))
            }
            query("SELECT owner_id,client_id,server_version,updated_at,time_zone FROM settings").use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertNull(it.getString(1))
                assertEquals(0, it.getInt(2))
                assertEquals(0L, it.getLong(3))
                assertNull(it.getString(4))
            }
            query("SELECT owner_id,client_id,updated_at,time_zone FROM daily_work_times").use {
                assertTrue(it.moveToFirst())
                assertEquals("guest", it.getString(0))
                assertNull(it.getString(1))
                assertEquals(0L, it.getLong(2))
                assertNull(it.getString(3))
            }

            assertEquals(1, scalar("SELECT COUNT(*) FROM tasks WHERE owner_id='guest' AND client_id IS NULL"))
            assertEquals(1, scalar("SELECT COUNT(*) FROM tasks WHERE client_id='$uuidId'"))
            assertEquals(
                listOf("owner_id", "id"),
                pkColumns("tasks"),
            )
            assertEquals(listOf("owner_id", "id"), pkColumns("settings"))
            assertEquals(listOf("owner_id", "date"), pkColumns("daily_work_times"))

            val indexes = mutableSetOf<String>()
            query(
                "SELECT name FROM sqlite_master WHERE type='index' AND name IN " +
                    "('index_tasks_owner_done_created','index_blocks_owner_start'," +
                    "'index_checkins_owner_completed')",
            ).use { while (it.moveToNext()) indexes += it.getString(0) }
            assertEquals(3, indexes.size)

            // The same object id can coexist under different owners after the composite PK.
            execSQL(
                "INSERT INTO tasks (id,title,minutes,deadline,importance,done,created,note," +
                    "chunkMinutes,splittable,category,owner_id,client_id,server_version,updated_at) " +
                    "VALUES ('$uuidId','B任务',10,0,1,0,0,'',10,1,'none','owner-b',NULL,0,0)",
            )
            assertEquals(2, scalar("SELECT COUNT(*) FROM tasks WHERE id='$uuidId'"))
            assertEquals(
                1,
                scalar("SELECT COUNT(*) FROM tasks WHERE id='$uuidId' AND owner_id='owner-b'"),
            )
            close()
        }
    }

    @Test
    fun emptyV5DatabaseMigratesToEmptyV6() {
        val name = "sync-content-empty-${UUID.randomUUID()}.db"
        helper.createDatabase(name, 5).close()
        helper.runMigrationsAndValidate(name, 6, true, DeskDatabase.MIGRATION_5_6).apply {
            assertEquals(0, count("tasks"))
            assertEquals(0, count("blocks"))
            assertEquals(0, count("checkins"))
            assertEquals(0, count("settings"))
            assertEquals(0, count("daily_work_times"))
            close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.count(table: String): Int =
        scalar("SELECT COUNT(*) FROM `$table`")

    private fun androidx.sqlite.db.SupportSQLiteDatabase.scalar(sql: String): Int =
        query(sql).use {
            check(it.moveToFirst())
            it.getInt(0)
        }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.pkColumns(table: String): List<String> {
        val result = mutableListOf<Pair<Int, String>>()
        query("PRAGMA table_info(`$table`)").use {
            while (it.moveToNext()) {
                val pk = it.getInt(5)
                if (pk > 0) result += pk to it.getString(1)
            }
        }
        return result.sortedBy { it.first }.map { it.second }
    }
}
