package io.whaletodo.app.account

import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.core.GateReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 查询/提交错误映射的纯 JVM 回归：429 不得终结 pending。 */
class AgentErrorMappingTest {
    @Test
    fun `查询 429 保持原 ID pending`() {
        val e = AgentErrorMapping.query(429, "RATE_LIMITED", null, "req-1")
        assertEquals("req-1", (e as AgentPendingException).requestId)
    }

    @Test
    fun `查询 RATE_LIMITED 代码即使状态非 429 也 pending`() {
        assertTrue(
            AgentErrorMapping.query(400, "RATE_LIMITED", null, "req-2") is AgentPendingException,
        )
    }

    @Test
    fun `查询 503 与 5xx 保持 pending`() {
        listOf(500, 502, 503, 504).forEach { status ->
            val e = AgentErrorMapping.query(status, "UPSTREAM_UNAVAILABLE", null, "req-3")
            assertTrue("status=$status", e is AgentPendingException)
            assertEquals("req-3", (e as AgentPendingException).requestId)
        }
    }

    @Test
    fun `查询只有明确 failed 才 definitive`() {
        val failed = AgentErrorMapping.query(422, "UNPROCESSABLE", "failed", "req-4")
        assertTrue(failed is AgentServiceException)
        assertTrue((failed as AgentServiceException).definitelyRejected)

        val unknown = AgentErrorMapping.query(422, "UNPROCESSABLE", "unknown", "req-5")
        assertTrue(unknown is AgentServiceException)
        assertFalse((unknown as AgentServiceException).definitelyRejected)
    }

    @Test
    fun `查询 403 归属拒绝与 409 业务拒绝 definitive`() {
        val forbidden = AgentErrorMapping.query(403, "FORBIDDEN", null, "req-6")
        assertTrue((forbidden as AgentServiceException).definitelyRejected)
        val conflict = AgentErrorMapping.query(409, "CONFLICT", null, "req-7")
        assertTrue((conflict as AgentServiceException).definitelyRejected)
    }

    @Test
    fun `查询 401 与 402 仍走门禁`() {
        val login = AgentErrorMapping.query(401, "UNAUTHENTICATED", null, "req-8")
        assertEquals(GateReason.LOGIN, (login as AgentGateException).reason)
        val points = AgentErrorMapping.query(402, "INSUFFICIENT_POINTS", null, "req-9")
        assertEquals(GateReason.POINTS, (points as AgentGateException).reason)
    }

    @Test
    fun `提交 401 402 403 409 429 明确未发送`() {
        val login = AgentErrorMapping.submit(401, null, null, "req") as AgentGateException
        assertEquals(GateReason.LOGIN, login.reason)
        assertTrue(login.definitelyNotSent)

        val points = AgentErrorMapping.submit(402, null, null, "req") as AgentGateException
        assertEquals(GateReason.POINTS, points.reason)
        assertTrue(points.definitelyNotSent)

        val vip = AgentErrorMapping.submit(403, null, "VIP_REQUIRED", "req") as AgentGateException
        assertEquals(GateReason.VIP, vip.reason)
        assertTrue(vip.definitelyNotSent)

        val conflict = AgentErrorMapping.submit(409, null, null, "req") as AgentServiceException
        assertTrue(conflict.definitelyRejected)
        assertTrue(conflict.definitelyNotSent)

        val limited = AgentErrorMapping.submit(429, null, null, "req") as AgentServiceException
        assertTrue(limited.definitelyRejected)
        assertTrue(limited.definitelyNotSent)
    }

    @Test
    fun `提交 5xx 非 failed 为 pending failed 才明确未发送`() {
        val pending = AgentErrorMapping.submit(503, null, null, "req")
        assertTrue(pending is AgentPendingException)

        val failed = AgentErrorMapping.submit(503, "failed", null, "req") as AgentServiceException
        assertTrue(failed.definitelyRejected)
        assertTrue(failed.definitelyNotSent)
    }

    @Test
    fun `提交未知状态保留未知语义`() {
        val unknown = AgentErrorMapping.submit(418, null, null, "req") as AgentServiceException
        assertFalse(unknown.definitelyRejected)
        assertFalse(unknown.definitelyNotSent)
    }

    @Test
    fun `查询错误不带未发送标记`() {
        val forbidden = AgentErrorMapping.query(403, "FORBIDDEN", null, "req") as AgentServiceException
        assertTrue(forbidden.definitelyRejected)
        assertFalse(forbidden.definitelyNotSent)
        assertTrue(AgentErrorMapping.query(429, "RATE_LIMITED", null, "req") is AgentPendingException)
    }

    @Test
    fun `提交 429 语义不变`() {
        val e = AgentErrorMapping.submit(429, null, null, "req-10")
        assertTrue((e as AgentServiceException).definitelyRejected)
        assertTrue(e.definitelyNotSent)
    }

    @Test
    fun `提交 503 无 failed 仍 pending 有 failed 才明确失败`() {
        assertTrue(AgentErrorMapping.submit(503, null, null, "req-11") is AgentPendingException)
        val failed = AgentErrorMapping.submit(503, "failed", null, "req-12")
        assertTrue((failed as AgentServiceException).definitelyRejected)
    }

    @Test
    fun `提交 403 的三种 reason 仍映射门禁`() {
        val vip = AgentErrorMapping.submit(403, null, "VIP_REQUIRED", "req") as AgentGateException
        assertEquals(GateReason.VIP, vip.reason)
        assertTrue(vip.definitelyNotSent)
        val model = AgentErrorMapping.submit(403, null, "MODEL_NOT_ALLOWED", "req") as AgentGateException
        assertEquals(GateReason.MODEL, model.reason)
        assertTrue(model.definitelyNotSent)
        val consent = AgentErrorMapping.submit(403, null, "CONSENT_REQUIRED", "req") as AgentGateException
        assertEquals(GateReason.CONSENT, consent.reason)
        assertTrue(consent.definitelyNotSent)
    }
}
