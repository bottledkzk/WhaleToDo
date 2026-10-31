import {test} from 'node:test';
import assert from 'node:assert/strict';
import {DIARY_STYLES,diaryProse,groundedDiaryProse} from '../src/ai/diary-prose.ts';
import {parseDiary,fingerprint} from '../src/ai/policy.ts';
import {prepareInput,providerMessages,callDeepSeek,type AiInput} from '../src/ai/provider.ts';
import type {AiConfig} from '../src/ai/config.ts';

const input:AiInput={client_request_id:'00000000-0000-4000-a000-000000000010',mode:'diary',model_code:'deepseek-default',prompt:'写下今天的记录',context:JSON.stringify({date:'2030-01-01',completed:[{title:'整理笔记',completed_local:'2030-01-01 15:23'}],actual_duration_recorded:false}),share_style:'minimal',edit_consent:false,history:[]};

test('actual phone diary counterexample loses only the missing-data note',()=>{
 const good='今天打卡完成了「演示·补测记录」，完成时间是15:23。';
 assert.equal(parseDiary(JSON.stringify({diary:good+'这项任务没有记录实际用时。'})),good);
 assert.equal(diaryProse('我整理了笔记。（注：实际用时未记录）'),'我整理了笔记。');
 assert.throws(()=>parseDiary(JSON.stringify({diary:'资料不足，未提供实际用时。'})));
});

test('actual provider embellishments fail closed while explicit facts and source titles survive',()=>{
 const agenda={completed:[{title:'补测记录'},{title:'一直搁在心上'}]};
 for(const prose of [
  '这件事一直搁在心上，做完以后像是把一页该翻过去的纸翻了过去。',
  '剩下的时间过得安静，没有急着再安排什么。',
  '几件事都按部就班地做完了，节奏不快，却也没有落下什么。',
  '到十一点多才弄完。','下午练了一遍项目演示。','完成之后感觉轻松。'
 ])assert.throws(()=>groundedDiaryProse(prose,{completed:[{title:'补测记录'}]},'把这些记录写成日记'));
 assert.equal(groundedDiaryProse('今天完成了《一直搁在心上》。',agenda,'写成日记'),'今天完成了《一直搁在心上》。');
 assert.equal(groundedDiaryProse('下午练了一遍项目演示，感觉轻松。',{},'今天练了一遍项目演示，感觉轻松。'),'下午练了一遍项目演示，感觉轻松。');
 assert.equal(groundedDiaryProse('15:23，笔记整理完了，像在今天的纸页上落下一笔。',{},''),'15:23，笔记整理完了，像在今天的纸页上落下一笔。');
});

test('guarded diary keeps provider usage, does not retry and cannot become a saveable draft',async()=>{
 let attempts=0;
 const result=await callDeepSeek({apiKey:'synthetic',model:'synthetic',maxOutput:100,timeoutMs:1000} as AiConfig,input,(async()=>{
  attempts++;return new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({diary:'下午练了一遍项目演示。'})},finish_reason:'stop'}],usage:{total_tokens:21}}));
 }) as typeof fetch);
 assert.equal(attempts,1);assert.equal(result.tokens,21);assert.equal(result.usable,false);assert.ok(!result.text.includes('一遍'));
});

test('natural negative facts and quoted source titles stay intact; report layouts are refused',()=>{
 for(const prose of ['今天没有完成阅读，先把它留在计划里。','我完成了《未记录实际用时》这份文档。','15:23，我整理了笔记。\n\n留下这笔记录，今天的事情有了一个落点。'])assert.equal(parseDiary(JSON.stringify({diary:prose})),prose);
 for(const prose of ['# 今日汇报\n完成整理笔记。','1. 整理笔记\n2. 实际耗时未知','任务统计：1项'])assert.throws(()=>parseDiary(JSON.stringify({diary:prose})));
});

test('each diary style controls the trusted prompt while missing-data flags are absent from facts',()=>{
 for(const style of DIARY_STYLES){
  const messages=providerMessages({...input,diary_style:style});
  const labels={literary:'文学：',rigorous:'严谨：',concise:'精简：',detailed:'详细：'};
  assert.ok(messages[0]!.content.includes(labels[style]));
  const facts=JSON.parse(messages[1]!.content).agenda;
  assert.equal(facts.actual_duration_recorded,undefined);assert.equal(facts.completed[0].title,'整理笔记');
 }
 assert.ok(providerMessages(input)[0]!.content.includes('精简：'));
 assert.equal(prepareInput({...input,diary_style:undefined}).sourceFingerprint,fingerprint(input));
 assert.notEqual(prepareInput({...input,diary_style:'literary'}).sourceFingerprint,prepareInput({...input,diary_style:'rigorous'}).sourceFingerprint);
});

test('style generation returns saveable prose and real usage; annotation-only output is unusable',async()=>{
 const config={apiKey:'synthetic',model:'synthetic',maxOutput:100,timeoutMs:1000} as AiConfig;
 for(const diary of ['我整理了笔记。这项任务没有记录实际用时。','这项任务没有记录实际用时。']){
  const result=await callDeepSeek(config,{...input,diary_style:'rigorous'},(async()=>new Response(JSON.stringify({choices:[{message:{content:JSON.stringify({diary})},finish_reason:'stop'}],usage:{total_tokens:20}}))) as typeof fetch);
  assert.equal(result.tokens,20);
  if(diary.startsWith('我'))assert.equal(result.text,'我整理了笔记。');else assert.equal(result.usable,false);
 }
});
