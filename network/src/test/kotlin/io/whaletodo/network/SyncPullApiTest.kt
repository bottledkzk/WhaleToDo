package io.whaletodo.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPullApiTest {
    private val taskId = "22222222-2222-4222-8222-222222222222"

    @Test
    fun parsesChangesCursorAndHasMoreStrictly() {
        val response = parsePullResponse(
            ApiResponse(
                200,
                """{"data":{"changes":[
                    {"object_type":"task","object_id":"$taskId","version":3,"deleted":false,
                     "payload":{"payload_version":1,"title":"写周报","minutes":25,"deadline":0,
                                "importance":3,"done":false,"created":1,"note":"","chunk_minutes":60,
                                "splittable":true,"category":"work"}},
                    {"object_type":"block","object_id":"33333333-3333-4333-8333-333333333333","version":4,
                     "deleted":true,"payload":null}
                ],"next_cursor":7,"has_more":true}}""",
                "req-1",
            ),
        )
        assertEquals(2, response.changes.size)
        assertEquals("task", response.changes[0].objectType)
        assertEquals(3, response.changes[0].version)
        assertEquals(1, response.changes[0].payloadJson?.let { JsonPayload.version(it) })
        assertTrue(response.changes[1].deleted)
        assertNull(response.changes[1].payloadJson)
        assertEquals(7L, response.nextCursor)
        assertTrue(response.hasMore)
    }

    @Test
    fun emptyPageIsLegal() {
        val response = parsePullResponse(
            ApiResponse(200, """{"data":{"changes":[],"next_cursor":4,"has_more":false}}""", null),
        )
        assertEquals(0, response.changes.size)
        assertEquals(4L, response.nextCursor)
        assertTrue(!response.hasMore)
    }

    @Test
    fun unknownObjectTypeFailsWholePage() {
        assertMalformed(
            """{"data":{"changes":[{"object_type":"goal","object_id":"$taskId","version":1,
                "deleted":false,"payload":{"payload_version":1}}],"next_cursor":1,"has_more":false}}""",
        )
    }

    @Test
    fun invalidCursorOrHasMoreFails() {
        assertMalformed(
            """{"data":{"changes":[],"next_cursor":"1","has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[],"next_cursor":-1,"has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[],"next_cursor":1}}""",
        )
    }

    @Test
    fun nonDeletedPayloadMustBeObjectWithVersionOne() {
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"$taskId","version":1,
                "deleted":false,"payload":null}],"next_cursor":1,"has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"$taskId","version":1,
                "deleted":false,"payload":{"title":"x"}}],"next_cursor":1,"has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"$taskId","version":1,
                "deleted":false,"payload":{"payload_version":2}}],"next_cursor":1,"has_more":false}}""",
        )
    }

    @Test
    fun invalidObjectIdVersionOrDeletedFails() {
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"not-a-uuid","version":1,
                "deleted":false,"payload":{"payload_version":1}}],"next_cursor":1,"has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"$taskId","version":0,
                "deleted":false,"payload":{"payload_version":1}}],"next_cursor":1,"has_more":false}}""",
        )
        assertMalformed(
            """{"data":{"changes":[{"object_type":"task","object_id":"$taskId","version":1,
                "deleted":null,"payload":{"payload_version":1}}],"next_cursor":1,"has_more":false}}""",
        )
    }

    private fun assertMalformed(body: String) {
        assertThrows(ApiException::class.java) {
            parsePullResponse(ApiResponse(200, body, "req-bad"))
        }
    }
}

private object JsonPayload {
    fun version(json: String): Int =
        kotlinx.serialization.json.Json.parseToJsonElement(json)
            .let { it as kotlinx.serialization.json.JsonObject }
            .let { (it["payload_version"] as kotlinx.serialization.json.JsonPrimitive).content.toInt() }
}
