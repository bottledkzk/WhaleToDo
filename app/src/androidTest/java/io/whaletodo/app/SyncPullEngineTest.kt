package io.whaletodo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.whaletodo.app.account.AccountSession
import io.whaletodo.app.data.DailyWorkTimeRow
import io.whaletodo.app.data.DeskDatabase
import io.whaletodo.app.data.SettingRow
import io.whaletodo.app.data.SyncOutboxRow
import io.whaletodo.app.data.TaskRow
import io.whaletodo.app.sync.SyncPullEngine
import io.whaletodo.network.AccountProfile
import io.whaletodo.network.ApiException
import io.whaletodo.network.Entitlements
import io.whaletodo.network.SyncPullApi
import io.whaletodo.network.SyncPullChange
import io.whaletodo.network.SyncPullResponse
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
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

@RunWith(AndroidJUnit4::class)
class SyncPullEngineTest {
    private lateinit var db: DeskDatabase
    private lateinit var session: AccountSession
    private var credentialSession = "s1"
    private val owner = "owner-a"
    private val taskId = "22222222-2222-4222-8222-222222222222"

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, DeskDatabase::class.java).build()
        session = AccountSession { credentialSession }
        check(session.begin("s1"))
        check(
            session.update(
                "s1",
                "a@example.test",
                AccountProfile(owner, "user-a", null),
                Entitlements(false, null),
                0,
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun appliesTaskAndAdvancesCursorAndLastPullAt() = runBlocking {
        val api = FakeApi { _, _ ->
            page(
                listOf(taskChange(taskId, 1, title = "远端任务")),
                nextCursor = 7,
                hasMore = false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("ok", outcome.status)
        assertEquals(7L, outcome.cursor)
        assertEquals(1, outcome.applied)
        assertEquals("远端任务", db.dao().task(owner, taskId)?.title)
        assertEquals(1, db.dao().task(owner, taskId)?.serverVersion)
        assertEquals(7L, db.syncDao().state(owner)?.cursor)
        assertNotNull(db.syncDao().state(owner)?.lastPullAt)
        assertEquals(0L, api.cursors.single())
    }

    @Test
    fun olderVersionIsSkippedAndNewerVersionWins() = runBlocking {
        db.dao().save(TaskRow(taskId, "本地 v3", ownerId = owner, serverVersion = 3, updatedAt = 1))
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    taskChange(taskId, 2, title = "旧 v2"),
                    taskChange(taskId, 4, title = "新 v4"),
                ),
                nextCursor = 4,
                hasMore = false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("ok", outcome.status)
        assertEquals(1, outcome.applied)
        assertEquals("新 v4", db.dao().task(owner, taskId)?.title)
        assertEquals(4, db.dao().task(owner, taskId)?.serverVersion)
    }

    @Test
    fun deleteRemovesContentAndWritesTombstone() = runBlocking {
        db.dao().save(TaskRow(taskId, "要删除", ownerId = owner, serverVersion = 1, updatedAt = 1))
        val api = FakeApi { _, _ ->
            page(listOf(SyncPullChange("task", taskId, 2, true, null)), 2, false)
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals(1, outcome.deleted)
        assertNull(db.dao().task(owner, taskId))
        assertEquals(2, db.syncDao().tombstone(owner, "task", taskId)?.version)
        assertEquals(2L, db.syncDao().state(owner)?.cursor)
    }

    @Test
    fun dirtyOutboxWritesConflictAndDoesNotOverwriteLocal() = runBlocking {
        db.dao().save(TaskRow(taskId, "本地待推", ownerId = owner, serverVersion = 1, updatedAt = 1))
        db.syncDao().insertOutbox(outbox("pending", baseVersion = 1))
        val api = FakeApi { _, _ ->
            page(listOf(taskChange(taskId, 2, title = "远端 v2")), 2, false)
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals(1, outcome.conflicts)
        assertEquals("本地待推", db.dao().task(owner, taskId)?.title)
        assertEquals(1, db.dao().task(owner, taskId)?.serverVersion)
        val conflict = db.syncDao().conflict(owner, "task", taskId)!!
        assertEquals(2, conflict.remoteKnownVersion)
        assertEquals(0, conflict.remoteDeleted)
        assertTrue(conflict.remoteDetailFetched)
        assertNotNull(conflict.remotePayloadJson)
        assertEquals(2L, db.syncDao().state(owner)?.cursor)
    }

    @Test
    fun remoteDeleteWithDirtyOutboxWritesDeletedConflictAndKeepsLocal() = runBlocking {
        db.dao().save(TaskRow(taskId, "本地保留", ownerId = owner, serverVersion = 1, updatedAt = 1))
        db.syncDao().insertOutbox(outbox("pending", baseVersion = 1))
        val api = FakeApi { _, _ ->
            page(listOf(SyncPullChange("task", taskId, 2, true, null)), 2, false)
        }
        SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("本地保留", db.dao().task(owner, taskId)?.title)
        val conflict = db.syncDao().conflict(owner, "task", taskId)!!
        assertEquals(1, conflict.remoteDeleted)
        assertTrue(conflict.remoteDetailFetched)
        assertNull(db.syncDao().tombstone(owner, "task", taskId))
    }

    @Test
    fun malformedSecondChangeRollsBackWholePageAndCursor() = runBlocking {
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    taskChange(taskId, 1, title = "应先落但不提交"),
                    SyncPullChange("task", "33333333-3333-4333-8333-333333333333", 1, false, "{}"),
                ),
                1,
                false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("invalid", outcome.status)
        assertNull(db.dao().task(owner, taskId))
        assertNull(db.syncDao().state(owner))
    }

    @Test
    fun unknownTypeRollsBackWholePage() = runBlocking {
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    taskChange(taskId, 1, title = "合法"),
                    SyncPullChange("goal", "33333333-3333-4333-8333-333333333333", 1, false, null),
                ),
                1,
                false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("invalid", outcome.status)
        assertNull(db.dao().task(owner, taskId))
        assertNull(db.syncDao().state(owner))
    }

    @Test
    fun cursorRegressionOrNonAdvancingPageIsRejected() = runBlocking {
        db.syncDao().upsertState(
            io.whaletodo.app.data.SyncStateRow(owner, cursor = 10, deviceId = "device-1"),
        )
        val regression = FakeApi { _, _ -> page(emptyList(), 9, false) }
        assertEquals(
            "invalid",
            SyncPullEngine(db, session, regression).pullOnce(owner, "device-1").status,
        )
        assertEquals(10L, db.syncDao().state(owner)?.cursor)

        val stuck = FakeApi { _, _ -> page(listOf(taskChange(taskId, 11)), 10, true) }
        assertEquals(
            "invalid",
            SyncPullEngine(db, session, stuck).pullOnce(owner, "device-1").status,
        )
        assertNull(db.dao().task(owner, taskId))
    }

    @Test
    fun ownerOrSessionMismatchDoesNotCallApiOrApply() = runBlocking {
        check(
            session.update(
                "s1",
                "b@example.test",
                AccountProfile("owner-b", "user-b", null),
                Entitlements(false, null),
                0,
            ),
        )
        val api = FakeApi { _, _ -> page(listOf(taskChange(taskId, 1)), 1, false) }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("session_wait", outcome.status)
        assertEquals(0, api.calls)
        assertNull(db.dao().task(owner, taskId))

        check(session.clear("s1"))
        credentialSession = "s2"
        check(session.begin("s2"))
        check(
            session.update(
                "s2",
                "a@example.test",
                AccountProfile(owner, "user-a", null),
                Entitlements(false, null),
                0,
            ),
        )
        val switched = FakeApi { _, _ ->
            session.clear("s2")
            page(listOf(taskChange(taskId, 1)), 1, false)
        }
        val second = SyncPullEngine(db, session, switched).pullOnce(owner, "device-1")
        assertEquals("session_wait", second.status)
        assertNull(db.dao().task(owner, taskId))
    }

    @Test
    fun unauthorizedAndNetworkFailureDoNotAdvanceCursor() = runBlocking {
        val unauthorized = FakeApi { _, _ ->
            throw ApiException("AUTH_REQUIRED", "401", 401, null)
        }
        assertEquals(
            "session_wait",
            SyncPullEngine(db, session, unauthorized).pullOnce(owner, "device-1").status,
        )
        val network = FakeApi { _, _ -> throw IOException("offline") }
        assertEquals(
            "error",
            SyncPullEngine(db, session, network).pullOnce(owner, "device-1").status,
        )
        assertNull(db.syncDao().state(owner))
    }

    @Test
    fun emptyPageStillAdvancesCursorAndLastPullAt() = runBlocking {
        val api = FakeApi { _, _ -> page(emptyList(), 5, false) }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals("ok", outcome.status)
        assertEquals(5L, db.syncDao().state(owner)?.cursor)
        assertNotNull(db.syncDao().state(owner)?.lastPullAt)
    }

    @Test
    fun settingsAndAvailabilityUseCanonicalIdsAndMapPayloads() = runBlocking {
        val settingsId = "44444444-4444-4444-8444-444444444444"
        val availabilityId = "55555555-5555-4555-8555-555555555555"
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    SyncPullChange(
                        "settings",
                        settingsId,
                        1,
                        false,
                        """{"payload_version":1,"start_hour":8,"end_hour":20,"time_zone":"Asia/Shanghai"}""",
                    ),
                    SyncPullChange(
                        "availability",
                        availabilityId,
                        1,
                        false,
                        """{"payload_version":1,"date":"2026-10-31","start_minute":540,"end_minute":1080,"time_zone":"Asia/Shanghai"}""",
                    ),
                ),
                1,
                false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals(2, outcome.applied)
        assertEquals(8, db.dao().settings(owner)?.startHour)
        assertEquals(20, db.dao().settings(owner)?.endHour)
        assertEquals("Asia/Shanghai", db.dao().settings(owner)?.timeZone)
        assertEquals(540, db.dao().workTime(owner, "2026-10-31")?.startMinute)
        assertEquals(1080, db.dao().workTime(owner, "2026-10-31")?.endMinute)
    }

    @Test
    fun hasMoreRequiresCallerToRequestNextPage() = runBlocking {
        val secondId = "33333333-3333-4333-8333-333333333333"
        var call = 0
        val api = FakeApi { cursor, _ ->
            call++
            if (cursor == 0L) {
                page(listOf(taskChange(taskId, 1, title = "第一页")), 1, true)
            } else {
                page(listOf(taskChange(secondId, 2, title = "第二页")), 2, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)
        val first = engine.pullOnce(owner, "device-1")
        assertEquals("ok", first.status)
        assertTrue(first.hasMore)
        assertEquals(1L, first.cursor)
        assertEquals(1, api.calls)

        val second = engine.pullOnce(owner, "device-1")
        assertEquals("ok", second.status)
        assertFalse(second.hasMore)
        assertEquals(2L, second.cursor)
        assertEquals(listOf(0L, 1L), api.cursors)
        assertNotNull(db.dao().task(owner, taskId))
        assertNotNull(db.dao().task(owner, secondId))
    }

    @Test
    fun oldCanonicalAvailabilityRowWithoutMappingIsStillDeletable() = runBlocking {
        val remoteId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        val date = "2026-11-01"
        var call = 0
        var canonicalId: String? = null
        val api = FakeApi { _, _ ->
            call++
            if (call == 1) {
                page(
                    listOf(
                        SyncPullChange(
                            "availability",
                            remoteId,
                            1,
                            false,
                            """{"payload_version":1,"date":"$date","start_minute":540,"end_minute":1080,"time_zone":"Asia/Shanghai"}""",
                        ),
                    ),
                    1,
                    false,
                )
            } else {
                page(listOf(SyncPullChange("availability", canonicalId!!, 2, true, null)), 2, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)
        engine.pullOnce(owner, "device-1")
        val mapping = db.syncDao().mapping(owner, "availability", remoteId)!!
        canonicalId = mapping.accountObjectId
        // Simulate a pre-T86 local row: canonical client_id was not backfilled, no mapping.
        db.dao().save(
            DailyWorkTimeRow(
                date = date,
                startMinute = 540,
                endMinute = 1080,
                ownerId = owner,
                clientId = null,
                serverVersion = 1,
                updatedAt = 1,
                timeZone = "Asia/Shanghai",
            ),
        )
        db.syncDao().deleteMapping(mapping)

        val removed = engine.pullOnce(owner, "device-1")
        assertEquals(1, removed.deleted)
        assertNull(db.dao().workTime(owner, date))
    }

    @Test
    fun nonCanonicalV1ThenCanonicalOutboxKeepsV2UpdateAndDeleteConflicted() = runBlocking {
        val remoteId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        val date = "2026-11-02"
        val localId = canonicalAvailabilityId(owner, date)
        db.dao().save(
            DailyWorkTimeRow(
                date = date,
                startMinute = 540,
                endMinute = 1080,
                ownerId = owner,
                clientId = localId,
                serverVersion = 1,
                updatedAt = 1,
                timeZone = "Asia/Shanghai",
            ),
        )
        db.syncDao().insertOutbox(
            SyncOutboxRow(
                opId = "op-availability",
                ownerId = owner,
                objectType = "availability",
                objectId = localId,
                op = "update",
                payloadJson = "{}",
                baseVersion = 1,
                idempotencyKey = "op-availability",
                parentOpId = null,
                isHead = true,
                batchId = null,
                state = "pending",
                attempts = 0,
                nextRetryAt = 0,
                createdAt = 1,
                lastErrorCode = null,
            ),
        )
        var call = 0
        val api = FakeApi { _, _ ->
            call++
            when (call) {
                1 -> page(listOf(availabilityChange(remoteId, 1, date)), 1, false)
                2 -> page(listOf(availabilityChange(remoteId, 2, date)), 2, false)
                else -> page(listOf(SyncPullChange("availability", remoteId, 3, true, null)), 3, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)
        assertEquals("ok", engine.pullOnce(owner, "device-1").status)
        assertEquals(localId, db.syncDao().mapping(owner, "availability", remoteId)?.accountObjectId)

        val updated = engine.pullOnce(owner, "device-1")
        assertEquals(1, updated.conflicts)
        assertNotNull(db.dao().workTime(owner, date))
        assertEquals("pending", db.syncDao().outbox("op-availability")?.state)
        assertEquals(2, db.syncDao().conflict(owner, "availability", localId)?.remoteKnownVersion)

        val deleted = engine.pullOnce(owner, "device-1")
        assertEquals(1, deleted.conflicts)
        assertNotNull(db.dao().workTime(owner, date))
        val conflict = db.syncDao().conflict(owner, "availability", localId)!!
        assertEquals(3, conflict.remoteKnownVersion)
        assertEquals(1, conflict.remoteDeleted)
        assertNull(db.syncDao().tombstone(owner, "availability", localId))
    }

    @Test
    fun nonCanonicalAvailabilityUpsertThenPayloadlessDeleteThenReplay() = runBlocking {
        val remoteId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val date = "2026-10-31"
        var call = 0
        val api = FakeApi { _, _ ->
            call++
            when (call) {
                1 -> page(
                    listOf(
                        SyncPullChange(
                            "availability",
                            remoteId,
                            1,
                            false,
                            """{"payload_version":1,"date":"$date","start_minute":540,"end_minute":1080,"time_zone":"Asia/Shanghai"}""",
                        ),
                    ),
                    1,
                    false,
                )
                else -> page(listOf(SyncPullChange("availability", remoteId, 2, true, null)), 2, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)
        assertEquals("ok", engine.pullOnce(owner, "device-1").status)
        assertNotNull(db.dao().workTime(owner, date))
        val localId = db.syncDao().mapping(owner, "availability", remoteId)!!.accountObjectId
        assertEquals(date, db.syncDao().mapping(owner, "availability", remoteId)?.naturalKey)

        val removed = engine.pullOnce(owner, "device-1")
        assertEquals(1, removed.deleted)
        assertNull(db.dao().workTime(owner, date))
        assertEquals(2, db.syncDao().tombstone(owner, "availability", localId)?.version)

        db.syncDao().upsertState(io.whaletodo.app.data.SyncStateRow(owner, 0, deviceId = "device-1"))
        val replay = engine.pullOnce(owner, "device-1")
        assertEquals("ok", replay.status)
        assertEquals(0, replay.deleted)
        assertNull(db.dao().workTime(owner, date))
        assertEquals(2, db.syncDao().tombstone(owner, "availability", localId)?.version)
    }

    @Test
    fun invalidAvailabilityDateRollsBackWholePage() = runBlocking {
        db.syncDao().upsertState(
            io.whaletodo.app.data.SyncStateRow(
                owner,
                cursor = 5,
                lastPullAt = 123,
                deviceId = "device-1",
            ),
        )
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    taskChange(taskId, 1, title = "不应提交"),
                    SyncPullChange(
                        "availability",
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        1,
                        false,
                        """{"payload_version":1,"date":"2026-02-30","start_minute":540,"end_minute":1080,"time_zone":"Asia/Shanghai"}""",
                    ),
                ),
                6,
                false,
            )
        }
        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")
        assertEquals("invalid", outcome.status)
        assertNull(db.dao().task(owner, taskId))
        assertEquals(5L, db.syncDao().state(owner)?.cursor)
        assertEquals(123L, db.syncDao().state(owner)?.lastPullAt)
    }

    @Test
    fun invalidTimezoneInAvailabilityOrSettingsRollsBackWholePage() = runBlocking {
        db.syncDao().upsertState(
            io.whaletodo.app.data.SyncStateRow(
                owner,
                cursor = 5,
                lastPullAt = 123,
                deviceId = "device-1",
            ),
        )
        val api = FakeApi { _, _ ->
            page(
                listOf(
                    SyncPullChange(
                        "availability",
                        "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                        1,
                        false,
                        """{"payload_version":1,"date":"2026-10-31","start_minute":540,"end_minute":1080,"time_zone":"Not/AZone"}""",
                    ),
                ),
                6,
                false,
            )
        }
        val availability = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")
        assertEquals("invalid", availability.status)
        assertNull(db.dao().workTime(owner, "2026-10-31"))
        assertEquals(5L, db.syncDao().state(owner)?.cursor)
        assertEquals(123L, db.syncDao().state(owner)?.lastPullAt)

        val settingsApi = FakeApi { _, _ ->
            page(
                listOf(
                    SyncPullChange(
                        "settings",
                        "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                        1,
                        false,
                        """{"payload_version":1,"start_hour":8,"end_hour":20,"time_zone":"Not/AZone"}""",
                    ),
                ),
                6,
                false,
            )
        }
        val settings = SyncPullEngine(db, session, settingsApi).pullOnce(owner, "device-1")
        assertEquals("invalid", settings.status)
        assertNull(db.dao().settings(owner))
        assertEquals(5L, db.syncDao().state(owner)?.cursor)
        assertEquals(123L, db.syncDao().state(owner)?.lastPullAt)
    }

    @Test
    fun samePageReplayIsIdempotent() = runBlocking {
        var call = 0
        val api = FakeApi { _, _ ->
            call++
            page(listOf(taskChange(taskId, 1, title = "只应用一次")), call.toLong(), false)
        }
        val engine = SyncPullEngine(db, session, api)
        engine.pullOnce(owner, "device-1")
        db.syncDao().upsertState(io.whaletodo.app.data.SyncStateRow(owner, 0, deviceId = "device-1"))
        val replay = engine.pullOnce(owner, "device-1")

        assertEquals("ok", replay.status)
        assertEquals(0, replay.applied)
        assertEquals(1, db.dao().task(owner, taskId)?.serverVersion)
        assertEquals("只应用一次", db.dao().task(owner, taskId)?.title)
    }

    @Test
    fun settingsDirtyCanonicalOutboxSurvivesRemoteUpdateAndDelete() = runBlocking {
        val canonical = canonicalSettingsId(owner)
        val remote = "44444444-4444-4444-8444-444444444444"
        db.dao().save(SettingRow(ownerId = owner, clientId = canonical, startHour = 9, endHour = 18, serverVersion = 1))
        db.syncDao().insertOrUpdateOutbox(settingsOutbox(canonical, "pending", 1))
        val api = FakeApi { cursor, _ ->
            when (cursor) {
                0L -> page(listOf(settingsChange(remote, 2, 10, 20)), 1, false)
                1L -> page(listOf(settingsChange(remote, 3, 10, 20, deleted = true)), 2, false)
                else -> page(listOf(settingsChange(remote, 2, 10, 20)), 3, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)

        val update = engine.pullOnce(owner, "device-1")
        assertEquals(1, update.conflicts)
        assertEquals(9, db.dao().settings(owner)?.startHour)
        assertEquals("pending", db.syncDao().outbox("op-settings")?.state)
        assertEquals(2, db.syncDao().conflict(owner, "settings", canonical)?.remoteKnownVersion)
        assertEquals(canonical, db.syncDao().mapping(owner, "settings", remote)?.accountObjectId)
        assertNull(db.syncDao().tombstone(owner, "settings", canonical))

        val delete = engine.pullOnce(owner, "device-1")
        assertEquals(1, delete.conflicts)
        assertEquals(9, db.dao().settings(owner)?.startHour)
        assertEquals(1, db.syncDao().conflict(owner, "settings", canonical)?.remoteDeleted)
        assertEquals("pending", db.syncDao().outbox("op-settings")?.state)
        assertNull(db.syncDao().tombstone(owner, "settings", canonical))

        val replay = engine.pullOnce(owner, "device-1")
        assertEquals(0, replay.applied)
        assertEquals(0, replay.conflicts)
        assertEquals(9, db.dao().settings(owner)?.startHour)
        assertEquals(3, db.syncDao().conflict(owner, "settings", canonical)?.remoteKnownVersion)
    }

    @Test
    fun settingsCleanPullAppliesVersionsAndReplayIsIdempotent() = runBlocking {
        val canonical = canonicalSettingsId(owner)
        val remote = "66666666-6666-4666-8666-666666666666"
        val api = FakeApi { cursor, _ ->
            when (cursor) {
                0L -> page(listOf(settingsChange(remote, 1, 8, 20)), 1, false)
                1L -> page(listOf(settingsChange(remote, 2, 10, 21)), 2, false)
                else -> page(listOf(settingsChange(remote, 2, 10, 21)), 3, false)
            }
        }
        val engine = SyncPullEngine(db, session, api)

        assertEquals(1, engine.pullOnce(owner, "device-1").applied)
        assertEquals(8, db.dao().settings(owner)?.startHour)
        assertEquals(canonical, db.dao().settings(owner)?.clientId)
        assertEquals(1, db.dao().settings(owner)?.serverVersion)
        assertEquals(canonical, db.syncDao().mapping(owner, "settings", remote)?.accountObjectId)
        assertEquals("settings", db.syncDao().mapping(owner, "settings", remote)?.naturalKey)

        assertEquals(1, engine.pullOnce(owner, "device-1").applied)
        assertEquals(10, db.dao().settings(owner)?.startHour)
        assertEquals(21, db.dao().settings(owner)?.endHour)
        assertEquals(2, db.dao().settings(owner)?.serverVersion)

        val replay = engine.pullOnce(owner, "device-1")
        assertEquals(0, replay.applied)
        assertEquals(10, db.dao().settings(owner)?.startHour)
        assertEquals(2, db.dao().settings(owner)?.serverVersion)
    }

    @Test
    fun settingsOldCanonicalRemoteIdAppliesWithoutMapping() = runBlocking {
        val canonical = canonicalSettingsId(owner)
        val api = FakeApi { _, _ -> page(listOf(settingsChange(canonical, 1, 7, 19)), 1, false) }

        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals(1, outcome.applied)
        assertEquals(7, db.dao().settings(owner)?.startHour)
        assertEquals(19, db.dao().settings(owner)?.endHour)
        assertEquals(canonical, db.dao().settings(owner)?.clientId)
        assertNull(db.syncDao().mapping(owner, "settings", canonical))
    }

    @Test
    fun settingsBlockedAndSendingOutboxAlsoConflictInsteadOfOverwriting() = runBlocking {
        val canonical = canonicalSettingsId(owner)
        val remote = "77777777-7777-4777-8777-777777777777"
        for ((state, version) in listOf("blocked" to 2, "sending" to 3)) {
            db.dao().save(SettingRow(ownerId = owner, clientId = canonical, startHour = 9, endHour = 18, serverVersion = 1))
            db.syncDao().insertOrUpdateOutbox(settingsOutbox(canonical, state, 1))
            db.syncDao().upsertState(io.whaletodo.app.data.SyncStateRow(owner, 0, deviceId = "device-1"))
            val api = FakeApi { _, _ -> page(listOf(settingsChange(remote, version, 11, 22)), 1, false) }

            val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

            assertEquals(1, outcome.conflicts)
            assertEquals(9, db.dao().settings(owner)?.startHour)
            assertEquals(state, db.syncDao().outbox("op-settings")?.state)
            assertEquals(version, db.syncDao().conflict(owner, "settings", canonical)?.remoteKnownVersion)
        }
    }

    @Test
    fun settingsLegacyRemoteKeyedDirtyOutboxConflictsWithoutCreatingLocalRow() = runBlocking {
        val remote = "88888888-8888-4888-8888-888888888888"
        db.syncDao().insertOrUpdateOutbox(settingsOutbox(remote, "sending", 1))
        val api = FakeApi { _, _ -> page(listOf(settingsChange(remote, 2, 10, 20)), 1, false) }

        val outcome = SyncPullEngine(db, session, api).pullOnce(owner, "device-1")

        assertEquals(1, outcome.conflicts)
        assertNull(db.dao().settings(owner))
        assertEquals("sending", db.syncDao().outbox("op-settings")?.state)
        assertEquals(2, db.syncDao().conflict(owner, "settings", remote)?.remoteKnownVersion)
        assertNull(db.syncDao().mapping(owner, "settings", remote))
    }

    private fun canonicalSettingsId(owner: String): String {
        val namespace = ByteBuffer.allocate(16)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(namespace + "settings:$owner".toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest.copyOf(16))
        return UUID(bytes.long, bytes.long).toString()
    }

    private fun settingsOutbox(objectId: String, state: String, baseVersion: Int?) = SyncOutboxRow(
        opId = "op-settings",
        ownerId = owner,
        objectType = "settings",
        objectId = objectId,
        op = "update",
        payloadJson = "{}",
        baseVersion = baseVersion,
        idempotencyKey = "op-settings",
        parentOpId = null,
        isHead = true,
        batchId = null,
        state = state,
        attempts = 0,
        nextRetryAt = 0,
        createdAt = 1,
        lastErrorCode = null,
    )

    private fun settingsChange(
        id: String,
        version: Int,
        startHour: Int,
        endHour: Int,
        deleted: Boolean = false,
    ) = SyncPullChange(
        "settings",
        id,
        version,
        deleted,
        if (deleted) null else
            """{"payload_version":1,"start_hour":$startHour,"end_hour":$endHour,"time_zone":"Asia/Shanghai"}""",
    )

    private fun availabilityChange(
        id: String,
        version: Int,
        date: String,
    ) = SyncPullChange(
        "availability",
        id,
        version,
        false,
        """{"payload_version":1,"date":"$date","start_minute":540,"end_minute":1080,"time_zone":"Asia/Shanghai"}""",
    )

    private fun canonicalAvailabilityId(owner: String, date: String): String {
        val namespace = ByteBuffer.allocate(16)
            .putLong(NAMESPACE.mostSignificantBits)
            .putLong(NAMESPACE.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
            .digest(namespace + "availability:$owner:$date".toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest.copyOf(16))
        return UUID(bytes.long, bytes.long).toString()
    }

    private fun outbox(state: String, baseVersion: Int?) = SyncOutboxRow(
        opId = "op-$taskId",
        ownerId = owner,
        objectType = "task",
        objectId = taskId,
        op = "update",
        payloadJson = "{}",
        baseVersion = baseVersion,
        idempotencyKey = "op-$taskId",
        parentOpId = null,
        isHead = true,
        batchId = null,
        state = state,
        attempts = 0,
        nextRetryAt = 0,
        createdAt = 1,
        lastErrorCode = null,
    )

    private fun taskChange(
        id: String,
        version: Int,
        title: String = "任务",
    ) = SyncPullChange(
        "task",
        id,
        version,
        false,
        """{"payload_version":1,"title":"$title","minutes":25,"deadline":0,"importance":3,
           "done":false,"created":1,"note":"","chunk_minutes":60,"splittable":true,"category":"work"}""",
    )

    private fun page(
        changes: List<SyncPullChange>,
        nextCursor: Long,
        hasMore: Boolean,
    ) = SyncPullResponse(changes, nextCursor, hasMore)

    private class FakeApi(
        private val handler: suspend (Long, Int) -> SyncPullResponse,
    ) : SyncPullApi {
        var calls = 0
        val cursors = mutableListOf<Long>()

        override suspend fun pull(sessionId: String, cursor: Long, limit: Int): SyncPullResponse {
            calls++
            cursors += cursor
            return handler(cursor, limit)
        }
    }

    private companion object {
        val NAMESPACE: UUID = UUID.fromString("b420822a-ec70-4d54-976d-586dde231c6d")
    }
}
