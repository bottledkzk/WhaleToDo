package io.whaletodo.app.account

import io.whaletodo.network.AiRequestView
import org.junit.Assert.*
import org.junit.Test

class AgentFailureFeedbackTest {
    private fun view(status:String="failed",code:String="MODEL_OUTPUT_INVALID",text:String="请明确一个事项、日期和开始时间")=
        AiRequestView("r",status,"edit",text,1200,null,null,null,1,0,false,false,null,code,null)
    @Test fun terminalDomainFeedbackIsActionableWhilePendingAndProviderFailuresStayGeneric() {
        assertEquals("请明确一个事项、日期和开始时间",modelFailureMessage(view()))
        assertNull(modelFailureMessage(view(status="unknown")))
        assertNull(modelFailureMessage(view(code="UPSTREAM_UNAVAILABLE")))
        assertNull(modelFailureMessage(view(text="provider internal diagnostic")))
        assertNull(modelFailureMessage(view(text="请明确"+"x".repeat(201))))
        assertNull(modelFailureMessage(view(text="请明确\ninternal metadata")))
    }
}
