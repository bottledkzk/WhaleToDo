import { test } from 'node:test';
import assert from 'node:assert/strict';
import {callDeepSeek,ProviderFailure,tokenReservation,type AiInput} from '../src/ai/provider.ts';
import type { AiConfig } from '../src/ai/config.ts';
const config={apiKey:'test-only-placeholder',model:'deepseek-flash',maxOutput:100,timeoutMs:1000} as AiConfig;
const input:AiInput={client_request_id:'00000000-0000-4000-a000-000000000001',mode:'chat',prompt:'给我一个学习建议',model_code:'deepseek-default'};
const reply=(body:unknown,status=200)=>async()=>new Response(JSON.stringify(body),{status});
test('fixed HTTPS provider, no thinking, bounded tokens and supported messages',async()=>{
 let calls=0;const fetcher=async(url:unknown,init?:RequestInit)=>{calls++;assert.equal(url,'https://api.deepseek.com/chat/completions');
 assert.equal(init?.redirect,'error');const b=JSON.parse(String(init?.body));assert.equal(b.thinking.type,'disabled');assert.equal(b.max_tokens,100);
 assert.equal(b.model,'deepseek-flash');assert.ok(!JSON.stringify(b).includes(config.apiKey));
 return new Response(JSON.stringify({choices:[{message:{content:'先完成一件小事'},finish_reason:'stop'}],usage:{total_tokens:12}}));};
 const result=await callDeepSeek(config,input,fetcher as typeof fetch);assert.equal(result.text,'先完成一件小事');assert.equal(result.tokens,12);assert.equal(result.continuation,false);assert.equal(calls,1);
});
test('HTTP auth/quota rejection is definite without exposing upstream body',async()=>{
 await assert.rejects(callDeepSeek(config,input,reply({detail:'sensitive-upstream'},401) as typeof fetch),(e:unknown)=>e instanceof ProviderFailure&&e.definite&&!e.message.includes('sensitive'));
});
test('timeout/server errors are unknown, never silently retried',async()=>{
 let calls=0;await assert.rejects(callDeepSeek(config,input,(async()=>{calls++;throw new Error('private-network-detail');}) as typeof fetch),(e:unknown)=>e instanceof ProviderFailure&&!e.definite&&!e.message.includes('private'));assert.equal(calls,1);
 await assert.rejects(callDeepSeek(config,input,reply({},500) as typeof fetch),(e:unknown)=>e instanceof ProviderFailure&&!e.definite);
});
test('empty and oversized output are unusable rather than successful',async()=>{
 for(const [text,reason] of [['','stop'],['x'.repeat(70000),'stop']]){
 await assert.rejects(callDeepSeek(config,input,reply({choices:[{message:{content:text},finish_reason:reason}],usage:{total_tokens:5}}) as typeof fetch),ProviderFailure);}
});
test('token reservation is conservative across Chinese text and output budget',()=>{
 assert.ok(tokenReservation(input,100)>Buffer.byteLength(input.prompt)+100);
});

test('completed malformed JSON is recoverably failed with real usage instead of unknown or a billable draft',async()=>{
 const body={choices:[{message:{content:'{"summary":"oops",bad-json'},finish_reason:'stop'}],usage:{total_tokens:25,prompt_tokens:20,completion_tokens:5}};
 const result=await callDeepSeek(config,{...input,mode:'edit'},reply(body) as typeof fetch);
 assert.equal(result.usable,false);assert.equal(result.tokens,25);assert.deepEqual(result.changes,[]);
 const blank={choices:[{message:{content:'{"summary":"","changes":[]}'},finish_reason:'stop'}],usage:{total_tokens:25}};
 const refusal=await callDeepSeek(config,{...input,mode:'edit'},reply(blank) as typeof fetch);
 assert.ok(refusal.text.trim());assert.notEqual(refusal.usable,false);
});

test('share output is exactly three bounded plain lines or an unbilled unusable result',async()=>{
 for(const copies of [['读书30分钟','完成阅读','阅读第3章'],['只有第一条','只有第二条'],['a'.repeat(51),'b','c']]){
  const result=await callDeepSeek(config,{...input,mode:'share_copy'},reply({choices:[{message:{content:JSON.stringify({copies})},finish_reason:'stop'}],usage:{total_tokens:30}}) as typeof fetch);
  assert.equal(result.tokens,30);
  if(copies.length===3&&copies[0]!.length<=50)assert.equal(result.text,copies.join('\n'));else assert.equal(result.usable,false);
 }
});

test('branding cannot collapse a billed three-copy result into a single identity line',async()=>{
 const result=await callDeepSeek(config,{...input,mode:'share_copy'},reply({choices:[{message:{content:JSON.stringify({copies:['DeepSeek 建议','GPT 建议','Claude 建议']})},finish_reason:'stop'}],usage:{total_tokens:30}}) as typeof fetch);
 assert.equal(result.usable,false);assert.equal(result.tokens,30);
});

test('actual model wrong-epoch counterexample uses the explicit clock before all domain checks',async()=>{
 const start=Date.UTC(2030,0,1,1),expected=Date.UTC(2030,0,1,2,30);
 const facts={date:'2030-01-01',timezone:'Asia/Shanghai',tasks:[{id:'t',done:false,deadline_ms:Date.UTC(2030,0,1,9)}],blocks:[{id:'b',task_id:'t',start_ms:start,end_ms:start+1800000,locked:false,done:false}]};
 const body={choices:[{message:{content:JSON.stringify({summary:'建议10:30',changes:[{type:'move_block',target_id:'b',start_ms:expected+3600000,end_ms:expected+5400000}]})},finish_reason:'stop'}],usage:{total_tokens:25}};
 const request={...input,mode:'edit' as const,prompt:'改为10:30',context:JSON.stringify(facts)};
 const result=await callDeepSeek(config,request,reply(body) as typeof fetch);
 assert.equal(result.changes?.[0]?.start_ms,expected);assert.equal(result.changes?.[0]?.end_ms,expected+1800000);
 const blocked=await callDeepSeek(config,{...request,context:JSON.stringify({...facts,blocks:[{...facts.blocks[0],locked:true}]})},reply(body) as typeof fetch);
 assert.deepEqual(blocked.changes,[]);assert.equal(blocked.usable,false);
 const refusal={choices:[{message:{content:JSON.stringify({summary:'没有该时间可用，且无冲突',changes:[]})},finish_reason:'stop'}],usage:{total_tokens:25}};
 const compiled=await callDeepSeek(config,request,reply(refusal) as typeof fetch);
 assert.equal(compiled.changes?.[0]?.start_ms,expected);
 const protectedRefusal=await callDeepSeek(config,{...request,context:JSON.stringify({...facts,blocks:[{...facts.blocks[0],done:true}]})},reply(refusal) as typeof fetch);
 assert.deepEqual(protectedRefusal.changes,[]);assert.equal(protectedRefusal.usable,false);
});

test('unambiguous domain move does not fail because model JSON is malformed, but fixed guards still apply',async()=>{
 const start=Date.UTC(2030,0,1,1),facts={date:'2030-01-01',timezone:'Asia/Shanghai',tasks:[{id:'t',title:'阅读',done:false,deadline_ms:Date.UTC(2030,0,1,12)}],blocks:[{id:'b',task_id:'t',start_ms:start,end_ms:start+1800000,locked:false,done:false}]};
 const body={choices:[{message:{content:'{broken model JSON'},finish_reason:'stop'}],usage:{total_tokens:25}};
 const request={...input,mode:'edit' as const,prompt:'把阅读从09:00移到17:00',context:JSON.stringify(facts)};
 const result=await callDeepSeek(config,request,reply(body) as typeof fetch);assert.equal(result.changes?.[0]?.start_ms,Date.UTC(2030,0,1,9));
 const blocked=await callDeepSeek(config,{...request,context:JSON.stringify({...facts,blocks:[{...facts.blocks[0],locked:true}]})},reply(body) as typeof fetch);
 assert.equal(blocked.usable,false);assert.deepEqual(blocked.changes,[]);
});
