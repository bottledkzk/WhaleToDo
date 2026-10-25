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
