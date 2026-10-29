package io.whaletodo.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncApiTest {
    @Test
    fun bodyIsStableAndKeepsPayloadAndExpectedVersion() {
        val body = SyncPushBody(
            batchId = "11111111-1111-4111-8111-111111111111",
            deviceId = "device-1",
            mutations = listOf(
                SyncPushMutation(
                    objectType = "task",
                    objectId = "22222222-2222-4222-8222-222222222222",
                    op = "update",
                    expectedVersion = 3,
                    payloadJson = """{"b":2,"a":1}""",
                ),
                SyncPushMutation(
                    objectType = "block",
                    objectId = "33333333-3333-4333-8333-333333333333",
                    op = "delete",
                    expectedVersion = null,
                    payloadJson = null,
                ),
            ),
        )
        val first = body.toJson()
        val second = body.toJson()
        assertEquals(first, second)
        assertFalse(first.contains("owner_id"))
        assertFalse(first.contains("user_id"))

        val mutation = Json.parseToJsonElement(first).jsonObject["mutations"]!!.jsonArray
        val update = mutation[0].jsonObject
        assertEquals(3, update["expected_version"].toString().toInt())
        assertEquals("""{"b":2,"a":1}""", update["payload"].toString())
        assertTrue(mutation[1].jsonObject["expected_version"].toString() == "null")
        assertFalse(mutation[1].jsonObject.containsKey("payload"))
    }

    @Test
    fun parsesAppliedPartialAndReplayedResponses() {
        val response = parsePushResponse(
            ApiResponse(
                200,
                """{"data":{"batch_id":"b1","status":"partial","replayed":true,"items":[
                    {"object_type":"task","object_id":"o1","op":"create","result":"applied","server_version":1},
                    {"object_type":"block","object_id":"o2","op":"update","result":"conflict","server_version":7,"reason":"version_mismatch"},
                    {"object_type":"checkin","object_id":"o3","op":"create","result":"rejected","server_version":null,"reason":"payload_required"}
                ]}}""",
                "req-1",
            ),
        )
        assertEquals("b1", response.batchId)
        assertEquals("partial", response.status)
        assertTrue(response.replayed)
        assertEquals(3, response.items.size)
        assertEquals(1, response.items[0].serverVersion)
        assertEquals("version_mismatch", response.items[1].reason)
        assertEquals(7, response.items[1].serverVersion)
        assertEquals(null, response.items[2].serverVersion)
        assertEquals("payload_required", response.items[2].reason)
    }

    @Test
    fun parsesAppliedStatusWithoutReason() {
        val response = parsePushResponse(
            ApiResponse(
                200,
                """{"data":{"batch_id":"b2","status":"applied","replayed":false,"items":[
                    {"object_type":"task","object_id":"o1","op":"create","result":"applied","server_version":1}
                ]}}""",
                null,
            ),
        )
        assertEquals("applied", response.status)
        assertFalse(response.replayed)
        assertEquals(1, response.items.single().serverVersion)
    }

    @Test
    fun malformedResponseThrowsApiException() {
        val error = assertThrows(ApiException::class.java) {
            parsePushResponse(ApiResponse(200, "{}", "req-2"))
        }
        assertEquals(200, error.status)
        assertEquals("INTERNAL", error.code)
    }
}
