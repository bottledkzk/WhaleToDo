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
}
