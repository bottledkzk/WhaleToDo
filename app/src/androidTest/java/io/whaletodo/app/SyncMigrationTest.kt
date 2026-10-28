package io.whaletodo.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.data.DeskDatabase
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), DeskDatabase::class.java)

    private val syncTables = setOf(
        "sync_batches",
        "sync_outbox",
        "sync_state",
        "sync_conflicts",
        "sync_id_mappings",
        "sync_tombstones",
    )

    @Test
    fun migration4To5PreservesOldRowsAndCreatesSyncSchema() {
        val name = "sync-migration-${UUID.randomUUID()}.db"
        helper.createDatabase(name, 4).apply {
            execSQL(
                "INSERT INTO tasks VALUES ('old-task','旧事项',90,1800000000000,4,0,1000,'旧备注',60,1,'study')",
            )
            execSQL("INSERT INTO settings VALUES (1,8,24)")
            execSQL("INSERT INTO blocks VALUES ('old-block','old-task',10000,20000,1,0)")
            execSQL("INSERT INTO checkins VALUES ('old-check','old-task',30000,5)")
            execSQL("INSERT INTO daily_work_times VALUES ('2026-10-28',480,1200)")
            close()
        }

        helper.runMigrationsAndValidate(name, 5, true, DeskDatabase.MIGRATION_4_5).apply {
            query("SELECT title,note,category FROM tasks WHERE id='old-task'").use {
                assertTrue(it.moveToFirst())
                assertEquals("旧事项", it.getString(0))
                assertEquals("旧备注", it.getString(1))
                assertEquals("study", it.getString(2))
            }
            query("SELECT endHour FROM settings WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals(24, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM blocks").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM checkins").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }
            query("SELECT COUNT(*) FROM daily_work_times").use {
                assertTrue(it.moveToFirst())
                assertEquals(1, it.getInt(0))
            }

            assertTablesExist()
            assertBatchColumns()
            assertConflictNullableDeleted()
            assertIndexesExist()
            syncTables.forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
            close()
        }
    }

    @Test
    fun emptyVersion4DatabaseUpgradesToEmptyVersion5() {
        val name = "sync-empty-${UUID.randomUUID()}.db"
        helper.createDatabase(name, 4).close()

        helper.runMigrationsAndValidate(name, 5, true, DeskDatabase.MIGRATION_4_5).apply {
            assertTablesExist()
            syncTables.forEach { table ->
                query("SELECT COUNT(*) FROM `$table`").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                }
            }
            assertIndexesExist()
            close()
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertTablesExist() {
        query(
            "SELECT name FROM sqlite_master WHERE type='table' AND name IN " +
                "('sync_batches','sync_outbox','sync_state','sync_conflicts'," +
                "'sync_id_mappings','sync_tombstones')",
        ).use {
            val names = mutableSetOf<String>()
            while (it.moveToNext()) names += it.getString(0)
            assertEquals(syncTables, names)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertBatchColumns() {
        query("PRAGMA table_info(`sync_batches`)").use {
            val columns = mutableSetOf<String>()
            while (it.moveToNext()) columns += it.getString(1)
            assertTrue(
                columns.containsAll(
                    setOf(
                        "createdSessionId",
                        "attemptSessionId",
                        "bodyJson",
                        "bodySha256",
                        "state",
                    ),
                ),
            )
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertConflictNullableDeleted() {
        query("PRAGMA table_info(`sync_conflicts`)").use {
            var found = false
            var nullable = false
            while (it.moveToNext()) {
                if (it.getString(1) == "remoteDeleted") {
                    found = true
                    nullable = it.getInt(3) == 0
                }
            }
            assertTrue("remoteDeleted missing", found)
            assertTrue("remoteDeleted must be nullable", nullable)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.assertIndexesExist() {
        query(
            "SELECT name FROM sqlite_master WHERE type='index' AND name IN " +
                "('index_sync_batches_owner_state'," +
                "'index_sync_outbox_owner_state_retry'," +
                "'index_sync_outbox_owner_batch'," +
                "'index_sync_outbox_parent'," +
                "'index_sync_conflicts_owner_state'," +
                "'index_sync_id_mappings_owner_natural'," +
                "'index_sync_tombstones_owner_deleted')",
        ).use {
            val names = mutableSetOf<String>()
            while (it.moveToNext()) names += it.getString(0)
            assertEquals(7, names.size)
        }
    }
}
