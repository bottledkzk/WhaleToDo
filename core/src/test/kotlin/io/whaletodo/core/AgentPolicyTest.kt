package io.whaletodo.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AgentPolicyTest {
    private val model=AgentModel("planner","安排",true)
    private val access=AgentAccess("owner",true,20,true,setOf("planner"))
    @Test fun permissionMatrixNeverAllowsGuestOrWrongModelToEdit() {
        assertEquals(GateReason.LOGIN,AgentPolicy.gate(AgentAccess(),AgentMode.CHAT,model,false))
        assertEquals(GateReason.VIP,AgentPolicy.gate(access.copy(vip=false),AgentMode.EDIT,model,true))
        assertEquals(GateReason.MODEL,AgentPolicy.gate(access,AgentMode.EDIT,AgentModel("read","只读"),true))
        assertEquals(GateReason.CONSENT,AgentPolicy.gate(access,AgentMode.EDIT,model,false))
        assertEquals(GateReason.POINTS,AgentPolicy.gate(access.copy(availablePoints=0),AgentMode.EDIT,model,true))
        assertNull(AgentPolicy.gate(access,AgentMode.EDIT,model,true))
    }
    @Test fun shareNeedsIdentityAndPremiumNeedsEntitlement() {
        assertEquals(GateReason.LOGIN,AgentPolicy.shareGate(AgentAccess(),false))
        assertNull(AgentPolicy.shareGate(access.copy(vip=false),false))
        assertEquals(GateReason.VIP,AgentPolicy.shareGate(access.copy(vip=false),true))
        assertNull(AgentPolicy.shareGate(access.copy(vip=false),true,true))
    }
    @Test fun tokenDisplayAndClockAreStableAtLargeValues(){assertEquals("1.25k",TokenDisplay.k(1250));assertEquals("Working… (02m03s)",TokenDisplay.elapsed(123));assertEquals("none",TaskCategory.from("untrusted").code)}
    @Test fun routingSelectsOnlyTrustedModeCapabilityAndNeverUpgradesReadOnly() {
        val read=AgentModel("read","鲸鱼AI")
        val denied=AgentModel("other","不可用",true)
        assertEquals(read,AgentPolicy.route(listOf(denied,model,read),access,AgentMode.CHAT))
        assertEquals(model,AgentPolicy.route(listOf(read,denied,model),access,AgentMode.EDIT))
        val missing=AgentPolicy.route(listOf(read,denied),access,AgentMode.EDIT)
        assertEquals(GateReason.MODEL,AgentPolicy.gate(access,AgentMode.EDIT,missing,true))
        assertEquals(GateReason.MODEL,AgentPolicy.gate(access.copy(editModels=emptySet()),AgentMode.EDIT,model,true))
    }
    @Test fun historicalProviderIdentityIsNeverRenderedButUserContentCanRemainUntouched() {
        for(text in listOf("我是DeepSeek", "I am GPT-5", "我是 deep-seek 模型", "我是Claude")) {
            assertEquals(AssistantIdentity.introduction,AssistantIdentity.display(text))
        }
        assertEquals("今天先完成阅读。",AssistantIdentity.display("今天先完成阅读。"))
    }
    @Test fun paidProposalCanBeConfirmedWithZeroBalanceButNeverWithRevokedEntitlement() {
        assertNull(AgentPolicy.proposalGate(access.copy(availablePoints=0),model))
        assertEquals(GateReason.POINTS,AgentPolicy.gate(access.copy(availablePoints=0),AgentMode.EDIT,model,true))
        assertEquals(GateReason.LOGIN,AgentPolicy.proposalGate(access.copy(userId=null),model))
        assertEquals(GateReason.VIP,AgentPolicy.proposalGate(access.copy(vip=false),model))
        assertEquals(GateReason.MODEL,AgentPolicy.proposalGate(access.copy(editModels=emptySet()),model))
    }
    private val now=Instant.parse("2030-01-01T08:00:00Z").toEpochMilli()
    private val tasks=listOf(AgentTaskFact("t",false,now+10*3600000))
    private val block=AgentBlockFact("b","t",now+3600000,now+7200000,false,false)
    private val windows=listOf(Window(Instant.ofEpochMilli(now),Instant.ofEpochMilli(now+12*3600000)))
    @Test fun validMovePreservesDuration(){AgentChanges.validate(listOf(AgentChange.MoveBlock("b",now+3*3600000,now+4*3600000)),tasks,listOf(block),now,windows)}
    @Test fun lockedStartedCompletedOrCollidingMovesAreRejected(){
        val action=listOf(AgentChange.MoveBlock("b",now+3*3600000,now+4*3600000))
        for(b in listOf(block.copy(locked=true),block.copy(done=true),block.copy(start=now-1)))assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(action,tasks,listOf(b),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(action,tasks,listOf(block,block.copy(id="other",start=now+3*3600000,end=now+4*3600000)),now,windows)}
    }
    @Test fun maliciousChangesCannotStretchBudgetOrChangeUnknownTasks(){
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(AgentChange.MoveBlock("b",now+3*3600000,now+5*3600000)),tasks,listOf(block),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(AgentChange.RenameTask("alien","Injected")),tasks,listOf(block),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(AgentChange.CategorizeTask("t","admin")),tasks,listOf(block),now,windows)}
    }
    @Test fun duplicateFieldsAndOutsideAvailabilityAreRejected(){
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(AgentChange.RenameTask("t","A"),AgentChange.RenameTask("t","B")),tasks,listOf(block),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(AgentChange.MoveBlock("b",now+13*3600000,now+14*3600000)),tasks,listOf(block),now,windows)}
    }
    @Test fun newScheduleNeedsUnscheduledTaskAndExactEstimatedDuration(){
        val task=AgentTaskFact("new",false,now+8*3600000,30)
        val c=AgentChange.ScheduleTask("new",now+2*3600000,now+2*3600000+1800000)
        AgentChanges.validate(listOf(c),listOf(task),emptyList(),now,windows)
        for(unsafe in listOf(c.copy(end=c.end+60000),c.copy(start=now-1800000,end=now),c.copy(start=now+13*3600000,end=now+13*3600000+1800000))){
            assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(unsafe),listOf(task),emptyList(),now,windows)}
        }
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(c),listOf(task.copy(done=true)),emptyList(),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(c),listOf(task),listOf(block.copy(taskId="new")),now,windows)}
        assertThrows(IllegalArgumentException::class.java){AgentChanges.validate(listOf(c),listOf(task),listOf(block.copy(start=c.start,end=c.end)),now,windows)}
    }
}
