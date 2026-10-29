package io.whaletodo.app.sync

import io.whaletodo.app.data.SyncOutboxRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPushBatchBuilderTest {
    @Test
    fun sortsByCreatedAtAndOpIdAndCapsAtTwenty() {
        val rows = (20 downTo 0).map { index ->
            row(
                opId = "op-%02d".format(index),
                createdAt = index.toLong(),
                objectId = "obj-%02d".format(index),
            )
        }
        val built = SyncPushBatchBuilder.build(
            ownerId = "owner-a",
            deviceId = "device-1",
            batchId = "11111111-1111-4111-8111-111111111111",
            rows = rows,
        )
        assertEquals(20, built.rows.size)
        assertEquals("op-00", built.rows.first().opId)
        assertEquals("op-19", built.rows.last().opId)
        assertEquals(20, Regex("\"object_type\"").findAll(built.bodyJson).count())
        assertEquals(SyncPushBatchBuilder.sha256(built.bodyJson), built.bodySha256)
        assertEquals(
            built.bodyJson,
            SyncPushBatchBuilder.build(
                "owner-a",
                "device-1",
                "11111111-1111-4111-8111-111111111111",
                rows,
            ).bodyJson,
        )
    }

    @Test
    fun keepsPayloadTextAndOmitsPayloadForDelete() {
        val built = SyncPushBatchBuilder.build(
            "owner-a",
            "device-1",
            "11111111-1111-4111-8111-111111111111",
            listOf(
                row("op-a", 1, "obj-a", op = "update", payload = """{"a":1,"b":"x"}"""),
                row("op-b", 2, "obj-b", op = "delete", payload = null),
            ),
        )
        assertTrue(built.bodyJson.contains("\"payload\":{\"a\":1,\"b\":\"x\"}"))
        assertTrue(built.bodyJson.contains("\"op\":\"delete\""))
        assertTrue(built.bodyJson.contains("\"expected_version\":2"))
        assertEquals(64, built.bodySha256.length)
    }

    private fun row(
        opId: String,
        createdAt: Long,
        objectId: String,
        op: String = "create",
        payload: String? = "{}",
    ) = SyncOutboxRow(
        opId = opId,
        ownerId = "owner-a",
        objectType = "task",
        objectId = objectId,
        op = op,
        payloadJson = payload,
        baseVersion = if (op == "create") null else 2,
        idempotencyKey = opId,
        parentOpId = null,
        isHead = true,
        batchId = null,
        state = "pending",
        attempts = 0,
        nextRetryAt = 0,
        createdAt = createdAt,
        lastErrorCode = null,
    )
}
