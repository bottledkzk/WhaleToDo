package io.whaletodo.app.account

import io.whaletodo.app.agent.AgentGateException
import io.whaletodo.app.agent.AgentPendingException
import io.whaletodo.app.agent.AgentServiceException
import io.whaletodo.core.GateReason

/**
 * AI 网关错误到桥接异常的纯映射，便于 JVM 单测。
 * 提交与查询语义不同，刻意保留两个独立函数：
 * 查询的 429 只是“本次状态查询被限流”，不能证明已提交请求失败或积分已释放。
 */
internal object AgentErrorMapping {
    fun submit(
        status: Int,
        bodyStatus: String?,
        reason: String?,
        requestId: String,
    ): Exception = when {
        status == 401 -> AgentGateException(GateReason.LOGIN)
        status == 402 -> AgentGateException(GateReason.POINTS)
        status == 403 -> when (reason) {
            "VIP_REQUIRED" -> AgentGateException(GateReason.VIP)
            "MODEL_NOT_ALLOWED" -> AgentGateException(GateReason.MODEL)
            "CONSENT_REQUIRED" -> AgentGateException(GateReason.CONSENT)
            else -> AgentServiceException(true)
        }
        status == 409 || status == 429 -> AgentServiceException(true)
        status in 500..599 ->
            if (bodyStatus == "failed") AgentServiceException(true)
            else AgentPendingException(requestId)
        else -> AgentServiceException(false)
    }

    fun query(
        status: Int,
        code: String,
        bodyStatus: String?,
        requestId: String,
    ): Exception = when {
        status == 401 -> AgentGateException(GateReason.LOGIN)
        status == 402 -> AgentGateException(GateReason.POINTS)
        // 403 归属拒绝 / 409 明确业务拒绝：原请求不会再被当前账号查询到。
        status == 403 || status == 409 -> AgentServiceException(true)
        // 429 只限制本次查询；必须保留原 ID，不能把 pending 清成 failed。
        status == 429 || code == "RATE_LIMITED" -> AgentPendingException(requestId)
        // 503/5xx 是暂时查不到，仍可能稍后成功。
        status in 500..599 -> AgentPendingException(requestId)
        // 只有响应体明确 failed 才终结 pending。
        bodyStatus == "failed" -> AgentServiceException(true)
        else -> AgentServiceException(false)
    }
}
