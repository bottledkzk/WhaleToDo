import {test} from 'node:test';
import assert from 'node:assert/strict';
import {requestedMove} from '../src/ai/move-intent.ts';
import {proposalProblem} from '../src/ai/proposal-guard.ts';
const base={date:'2030-01-01',timezone:'Asia/Shanghai',action_version:2,tasks:[{id:'t',title:'阅读',done:false,minutes:30,deadline_ms:Date.UTC(2030,0,15)}],blocks:[],available:[{start_ms:Date.UTC(2030,0,1),end_ms:Date.UTC(2030,0,14)}]};
const compile=(text:string,context:unknown=base)=>requestedMove(JSON.stringify(context),text) as any;
test('explicit date, tomorrow, afternoon, next weekday compile to exact local instants',()=>{
 assert.equal(compile('把阅读安排到明天10:30').start_ms,Date.UTC(2030,0,2,2,30));
 assert.equal(compile('把阅读安排到2030-01-03 下午3点半').start_ms,Date.UTC(2030,0,3,7,30));
 assert.equal(compile('把阅读安排到下周一 09:00').start_ms,Date.UTC(2030,0,7,1));
 assert.equal(compile('把阅读安排到1月4日 10:00').start_ms,Date.UTC(2030,0,4,2));
 assert.ok(compile('把阅读安排到2030-02-30 10:00').error);
 assert.ok(compile('把阅读安排到明天或者后天10:00').error);
});
test('new schedule is still only a bounded proposal; wrong day, duration, overlap and done are rejected',()=>{
 const intent=compile('把阅读安排到明天10:30');assert.equal(intent.schedule_task,true);
 const change={type:'schedule_task' as const,target_id:intent.target_id,start_ms:intent.start_ms,end_ms:intent.end_ms};
 const context=JSON.stringify(base),now=Date.UTC(2030,0,1);
 assert.equal(proposalProblem([change],context,now,'把阅读安排到明天10:30'),null);
 assert.ok(proposalProblem([{...change,start_ms:change.start_ms-86400000,end_ms:change.end_ms-86400000}],context,now,'把阅读安排到明天10:30'));
 assert.ok(proposalProblem([{...change,end_ms:change.end_ms+60000}],context,now,'把阅读安排到明天10:30'));
 assert.ok(proposalProblem([change],context,now,''));
 assert.ok(compile('把阅读安排到明天10:30',{...base,tasks:[{...base.tasks[0]!,done:true}]}).error);
 const conflict={...base,blocks:[{id:'other',task_id:'other-task',start_ms:intent.start_ms,end_ms:intent.end_ms,locked:true,done:false}]};
 assert.ok(proposalProblem([change],JSON.stringify(conflict),now,'把阅读安排到明天10:30'));
});
test('opinion can bind a not-yet-accepted new schedule target without applying its old proposal',()=>{
 const text=JSON.stringify({feedback:'改为后天11点',unaccepted_changes:[{type:'schedule_task',target_id:'t',start_ms:1,end_ms:2}]});
 assert.equal(compile(text).start_ms,Date.UTC(2030,0,3,3));
});

test('one explicit time range preserves duration instead of rejecting two unambiguous clocks',()=>{
 const range=compile('请把阅读安排在2030年1月3日16:00到16:30，其他安排保持不变');
 assert.equal(range.start_ms,Date.UTC(2030,0,3,8));assert.equal(range.end_ms,Date.UTC(2030,0,3,8,30));
 assert.equal(range.schedule_task,true);
 assert.equal(compile('把阅读安排到明天下午1点到1点半').start_ms,Date.UTC(2030,0,2,5));
 assert.ok(compile('把阅读安排到明天16:00到16:20').error);
 assert.ok(compile('把阅读安排到明天16:00或者17:00').error);
 assert.ok(compile('把阅读安排到明天23:45到00:15').error);
 const ambiguous={...base,date:'2030-11-03',timezone:'America/New_York'};
 assert.ok(compile('把阅读安排到2030-11-03 00:30到01:00',ambiguous).error);
});

test('source clock selects exactly one segment of a split task while preserving its duration',()=>{
 const context={...base,blocks:[{id:'first',task_id:'t',start_ms:Date.UTC(2030,0,1,1),end_ms:Date.UTC(2030,0,1,1,30),locked:false,done:false},
  {id:'second',task_id:'t',start_ms:Date.UTC(2030,0,1,5),end_ms:Date.UTC(2030,0,1,5,30),locked:false,done:false}]};
 const intent=compile('把阅读从13:00移到17:00',context);
 assert.equal(intent.target_id,'second');assert.equal(intent.start_ms,Date.UTC(2030,0,1,9));assert.equal(intent.end_ms,Date.UTC(2030,0,1,9,30));
 assert.ok(compile('把阅读从14:00移到17:00',context).error);
 assert.ok(compile('把阅读从13:00或者17:00',context).error);
 const repeated={...context,blocks:[...context.blocks,{...context.blocks[1]!,id:'third',start_ms:Date.UTC(2030,0,2,5),end_ms:Date.UTC(2030,0,2,5,30)}]};
 assert.ok(compile('把阅读从13:00移到17:00',repeated).error);
});
