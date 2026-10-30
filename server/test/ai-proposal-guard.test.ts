import {test} from 'node:test';
import assert from 'node:assert/strict';
import {proposalProblem} from '../src/ai/proposal-guard.ts';
const now=Date.UTC(2030,0,1,0),start=now+3600000,end=start+1800000;
const snapshot={tasks:[{id:'t',done:false,deadline_ms:now+8*3600000}],blocks:[{id:'b',task_id:'t',start_ms:start,end_ms:end,locked:false,done:false}],available:[{start_ms:start,end_ms:now+9*3600000}]};
const change={type:'move_block' as const,target_id:'b',start_ms:start+3600000,end_ms:end+3600000};
test('actual model done-block counterexample is blocked, without trusting its summary',()=>{
 const context=JSON.stringify({...snapshot,blocks:[{...snapshot.blocks[0],done:true}]});
 assert.match(proposalProblem([change],context,now)!,/已完成/u);
});
test('valid JSON and current client agendaText permit the same bounded move',()=>{
 assert.equal(proposalProblem([change],JSON.stringify(snapshot),now),null);
 const text=`task id=t 标题=阅读 类型=study 工时=30min 截止=${new Date(snapshot.tasks[0]!.deadline_ms).toISOString()} 完成=false\nblock id=b task=t 开始=${new Date(start).toISOString()} 结束=${new Date(end).toISOString()} 固定=false 完成=false`;
 assert.equal(proposalProblem([change],text,now),null);
 assert.equal(proposalProblem([change],JSON.stringify({agenda:text,unaccepted_proposal:['旧提案']}),now),null);
});
test('locked, started, absent targets, deadline, availability and overlap remain protected',()=>{
 assert.ok(proposalProblem([{...change,start_ms:start,end_ms:end}],JSON.stringify(snapshot),now));
 assert.ok(proposalProblem([change],JSON.stringify({...snapshot,blocks:[{...snapshot.blocks[0],locked:true}]}),now));
 assert.ok(proposalProblem([change],JSON.stringify(snapshot),start));
 assert.ok(proposalProblem([{...change,target_id:'other'}],JSON.stringify(snapshot),now));
 assert.ok(proposalProblem([{...change,start_ms:now+10*3600000,end_ms:now+10.5*3600000}],JSON.stringify(snapshot),now));
 assert.ok(proposalProblem([change],JSON.stringify({...snapshot,available:[{start_ms:start,end_ms:end}]}),now));
 assert.ok(proposalProblem([change],JSON.stringify({...snapshot,blocks:[...snapshot.blocks,{...snapshot.blocks[0],id:'other',start_ms:change.start_ms,end_ms:change.end_ms}]}),now));
});
test('a valid but wrong local clock time cannot masquerade as the requested move',()=>{
 const context=JSON.stringify({...snapshot,date:'2030-01-01',timezone:'UTC'});
 assert.ok(proposalProblem([change],context,now,'移动到04:00'));
 assert.equal(proposalProblem([change],context,now,'移动到02:00'),null);
});
