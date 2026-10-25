import {test} from 'node:test';
import assert from 'node:assert/strict';
import {softCompact,parseProposal,systemPrompt,MODES,formatK} from '../src/ai/policy.ts';
import {callDeepSeek,prepareInput,type AiInput} from '../src/ai/provider.ts';
import type {AiConfig} from '../src/ai/config.ts';
const input:AiInput={client_request_id:'00000000-0000-4000-a000-000000000010',mode:'chat',prompt:'学习建议',model_code:'deepseek-default'};
const config={apiKey:'synthetic-placeholder',model:'deepseek-flash',maxOutput:100,timeoutMs:1000} as AiConfig;
test('all modes have bounded final-answer prompts and no authority to mutate accounts',()=>{for(const mode of MODES){const text=systemPrompt(mode);assert.ok(text.includes('不要输出思维链'));assert.ok(text.includes('不得更改账户'));}assert.ok(systemPrompt('edit').includes('changes'));});
test('soft compression preserves whole boundary paragraphs and advertises omission',()=>{
 const source=Array.from({length:80},(_,i)=>`段落${i}是完整句。\n`).join('');const out=softCompact(source,200);
 assert.ok(out.omitted>0);assert.ok(Buffer.byteLength(out.text)<=200);assert.ok(out.text.includes('段落0'));assert.ok(out.text.includes('段落79'));assert.ok(out.text.includes('未附带'));
});
test('a single giant unsegmented payload is rejected before upstream calls',()=>{assert.throws(()=>softCompact('X'.repeat(5000),100));});
test('request fingerprints still bind omitted text, not only the compressed context',()=>{
 const context=Array.from({length:1500},(_,i)=>`item${i}。`).join('');const a=prepareInput({...input,context});const b=prepareInput({...input,context:context.replace('item700','changed700')});
 assert.notEqual(a.sourceFingerprint,b.sourceFingerprint);assert.ok(a.compacted);
});
test('history is bounded and user/assistant roles do not replace system rules',()=>{
 const x=prepareInput({...input,history:Array.from({length:16},(_,i)=>({role:'user',content:`message${i}`}))});assert.equal(x.history?.length,8);assert.equal(x.history?.[0]?.content,'message8');
});
test('edit proposal rejects privilege fields and arbitrary action types atomically',()=>{
 const change={type:'rename_task',target_id:'t',title:'新标题'};
 assert.equal(parseProposal(JSON.stringify({summary:'建议',changes:[change]})).changes.length,1);
 for(const c of [{type:'set_vip',target_id:'u',vip:true},{...change,role:'admin'},{type:'move_block',target_id:'b',start_ms:10,end_ms:5}])assert.throws(()=>parseProposal(JSON.stringify({summary:'x',changes:[c]})));
});
test('read-only response length ends at complete sentence, displays continuation and keeps usage',async()=>{
 const fetcher=async()=>new Response(JSON.stringify({choices:[{message:{content:'第一步完成。还没有写完'},finish_reason:'length'}],usage:{total_tokens:20,prompt_tokens:12,completion_tokens:8}}));
 const r=await callDeepSeek(config,input,fetcher as typeof fetch);assert.equal(r.text,'第一步完成。');assert.equal(r.continuation,true);assert.equal(r.tokens,20);assert.equal(r.inputTokens,12);
});
test('truncated JSON never becomes actionable edits or automatically retries',async()=>{
 let calls=0;const fetcher=async()=>{calls++;return new Response(JSON.stringify({choices:[{message:{content:'{"summary":"x","changes":[ '},finish_reason:'length'}],usage:{total_tokens:20}}));};
 const r=await callDeepSeek(config,{...input,mode:'edit',model_code:'deepseek-planner'},fetcher as typeof fetch);assert.equal(r.changes,undefined);assert.equal(r.continuation,true);assert.equal(calls,1);
});
test('reasoning_content is neither exposed nor counted as displayed content',async()=>{
 const r=await callDeepSeek(config,input,(async()=>new Response(JSON.stringify({choices:[{message:{content:'最终答复',reasoning_content:'PRIVATE-THOUGHT'},finish_reason:'stop'}],usage:{total_tokens:20}}))) as typeof fetch);
 assert.equal(r.text,'最终答复');assert.ok(!JSON.stringify(r).includes('PRIVATE-THOUGHT'));
});
test('k formatting uses decimal thousand consistently',()=>{assert.equal(formatK(1000),'1.00k');assert.equal(formatK(82),'0.08k');});
