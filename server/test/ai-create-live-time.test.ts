import test from 'node:test';
import assert from 'node:assert/strict';
import {requestedCreate} from '../src/ai/create-intent.ts';
import {proposalProblem} from '../src/ai/proposal-guard.ts';
import {runtimeTime,localMillis} from '../src/ai/runtime-time.ts';
import {parseProposal} from '../src/ai/policy.ts';
import {providerMessages} from '../src/ai/provider.ts';

const now=Date.parse('2026-11-02T00:30:00Z');
const context=JSON.stringify({date:'2026-10-01',timezone:'Asia/Shanghai',action_version:3,tasks:[],blocks:[],available:[],partial:false});
const request='我每周二上午10点到十二点都有一节计算机网络课程，一直上到12月10日，请帮我在日程中安排';

test('the exact reported request expands six future Tuesday courses through December 10',()=>{
 const result=requestedCreate(context,request,now);assert.ok(result&&'changes' in result);
 assert.equal(result.changes.length,6);
 assert.deepEqual(result.changes.map(c=>new Date(c.start_ms!).toISOString()),['2026-11-03T02:00:00.000Z','2026-11-10T02:00:00.000Z','2026-11-17T02:00:00.000Z','2026-11-24T02:00:00.000Z','2026-12-01T02:00:00.000Z','2026-12-08T02:00:00.000Z']);
 assert.ok(result.changes.every(c=>Number(c.end_ms)-Number(c.start_ms)===120*60000&&c.fixed&&c.title==='计算机网络课程'));
 assert.equal(proposalProblem(result.changes,context,now,request),null);
 assert.deepEqual(parseProposal(JSON.stringify({summary:'待确认',changes:result.changes})).changes,result.changes);
});
test('new events never collide, mutate protected objects, or use an unrequested date',()=>{
 const r=requestedCreate(context,request,now);assert.ok(r&&'changes' in r);
 const first=r.changes[0]!;
 const occupied=JSON.stringify({date:'2026-11-02',timezone:'Asia/Shanghai',action_version:3,tasks:[{id:'t',done:false,minutes:120}],blocks:[{id:'fixed',task_id:'t',start_ms:first.start_ms,end_ms:first.end_ms,locked:true,done:false}]});
 assert.ok(proposalProblem(r.changes,occupied,now,request));
 assert.ok(proposalProblem([{...first,start_ms:first.start_ms!+3600000}],context,now,request));
 assert.ok(proposalProblem([first],context,now,''));
 const invalid=requestedCreate(context,request.replace('12月10日','11月31日'),now);assert.ok(invalid&&'error' in invalid);
});
test('server time and validated timezone override stale or forged client clocks',()=>{
 const time=runtimeTime(context,now);assert.equal(time.today,'2026-11-02');assert.equal(time.yesterday,'2026-11-01');assert.equal(time.tomorrow,'2026-11-03');
 const messages=providerMessages({client_request_id:'clock',mode:'chat',model_code:'deepseek-default',prompt:'你好',context},now);
 assert.match(messages[0]!.content,/2026-11-02/);assert.match(messages[0]!.content,/服务端即时/);
 assert.equal(runtimeTime('{"timezone":"bad zone","now_ms":0}',now).timezone,'UTC');
});
test('ambiguous DST times, elapsed occurrences and oversized recurrences are rejected',()=>{
 assert.equal(localMillis('2026-11-01','01:30:00','America/Los_Angeles'),null);
 assert.equal(localMillis('2026-03-08','02:30:00','America/Los_Angeles'),null);
 const later=requestedCreate(context,request,Date.parse('2026-11-03T05:00:00Z'));assert.ok(later&&'changes' in later);assert.equal(later.changes.length,5);
 const tooLong=requestedCreate(context,request.replace('12月10日','2027年2月1日'),now);assert.ok(tooLong&&'error' in tooLong);
});
