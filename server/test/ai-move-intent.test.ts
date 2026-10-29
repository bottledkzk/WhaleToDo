import {test} from 'node:test';
import assert from 'node:assert/strict';
import {requestedMove} from '../src/ai/move-intent.ts';
const context=JSON.stringify({date:'2030-01-01',timezone:'Asia/Shanghai',tasks:[],blocks:[{id:'b',start_ms:Date.UTC(2030,0,1,1),end_ms:Date.UTC(2030,0,1,1,30)}]});
test('current feedback binds the old target and resolves the clock with code, not model arithmetic',()=>{
 const request=JSON.stringify({unaccepted_changes:[{type:'move_block',target_id:'b',start_ms:1,end_ms:2}],feedback:'改为10:30，不要沿用旧提案'});
 assert.deepEqual(requestedMove(context,request),{target_id:'b',start_ms:Date.UTC(2030,0,1,2,30),end_ms:Date.UTC(2030,0,1,3),timezone:'Asia/Shanghai'});
});
test('ambiguous clocks, relative dates, multiple targets and negated edits are not bound automatically',()=>{
 assert.equal(requestedMove(context,'移到10:30之前'),null);assert.equal(requestedMove(context,'明天移到10:30'),null);
 assert.equal(requestedMove(context,'不要移动，改为10:30'),null);
 const value=JSON.parse(context);value.blocks.push({...value.blocks[0],id:'other'});assert.equal(requestedMove(JSON.stringify(value),'改为10:30'),null);
 for(const [date,time] of [['2030-03-10','02:30'],['2030-11-03','01:30']]){
  assert.ok(requestedMove(JSON.stringify({...JSON.parse(context),date,timezone:'America/New_York'}),'改为'+time)?.hasOwnProperty('error'));
 }
});
