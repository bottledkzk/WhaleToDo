import test from 'node:test';
import assert from 'node:assert/strict';
import {validateImage,type AiImage} from '../src/ai/image-input.ts';
import {imageCourses} from '../src/ai/image-courses.ts';
import {prepareInput,providerPayload,tokenReservation,callDeepSeek,type AiInput} from '../src/ai/provider.ts';
import type {AiConfig} from '../src/ai/config.ts';

function frame(w=640,h=480,padding=0):AiImage {
 const bytes=Buffer.from([255,216,255,192,0,11,8,h>>8,h&255,w>>8,w&255,1,1,17,0,255,217]);
 return {mime_type:'image/jpeg',data:Buffer.concat([bytes,Buffer.alloc(padding)]).toString('base64')};
}
const context=JSON.stringify({timezone:'Asia/Shanghai',date:'2026-10-01',action_version:4,tasks:[],blocks:[],available:[],partial:false});
const input:AiInput={client_request_id:'image-test',mode:'edit',model_code:'deepseek-planner',edit_consent:true,prompt:'请把课表加入下周日程',context,image:frame()};
test('inline JPEG validation rejects URLs, spoofed formats, excessive dimensions and oversized files',()=>{
 validateImage(frame());
 assert.throws(()=>validateImage({...frame(),data:'https://example.invalid/image.jpg'}));
 assert.throws(()=>validateImage({...frame(),mime_type:'image/png'} as any));
 assert.throws(()=>validateImage(frame(4096,4096)));
 assert.throws(()=>validateImage(frame(640,480,131073)));
});
test('payload uses visual content blocks while image reservation counts tokens rather than base64 bytes',()=>{
 const messages=providerPayload(input);
 assert.ok(Array.isArray(messages[1]!.content));
 const blocks=messages[1]!.content as any[];
 assert.equal(blocks[1].type,'image_url');assert.ok(blocks[1].image_url.url.startsWith('data:image/jpeg;base64,'));
 assert.equal(tokenReservation(input,2048),tokenReservation({...input,image:frame(640,480,50000)},2048));
 assert.notEqual(prepareInput(input).sourceFingerprint,prepareInput({...input,image:frame(640,480,1)}).sourceFingerprint);
});
test('course extraction builds an explicit next-week proposal without model epoch arithmetic',()=>{
 const now=Date.parse('2026-11-02T00:00:00Z');
 const text=JSON.stringify({summary:'两门课程',week_start:null,courses:[{title:'计算机网络',weekday:2,start_time:'10:00',end_time:'12:00'},{title:'数据库系统',weekday:4,start_time:'14:00',end_time:'15:30'}]});
 const result=imageCourses(text,context,'请加入下周日程',now);
 assert.equal(result.changes.length,2);assert.equal(new Date(result.changes[0]!.start_ms!).toISOString(),'2026-11-10T02:00:00.000Z');
 assert.equal(result.changes[0]!.end_ms!-result.changes[0]!.start_ms!,7200000);
 assert.throws(()=>imageCourses(text.replace('10:00','第1节'),context,'请加入下周日程',now));
 assert.throws(()=>imageCourses(text.replace('"weekday":2','"weekday":9'),context,'请加入下周日程',now));
 assert.equal(imageCourses(JSON.stringify({summary:'请补充第一至第二节的上课时刻',week_start:null,courses:[]}),context,'加入日程',now).changes.length,0);
});
test('image-based editor routes only through the fixed vision model and returns guarded proposal objects',async()=>{
 let body:any;
 const config={apiKey:'sk-test',model:'text-only',editModel:'text-only-planner',maxOutput:2048,timeoutMs:1000} as AiConfig;
 const reply=await callDeepSeek(config,prepareInput(input),(async(_url:any,options:any)=>{
  body=JSON.parse(options.body);
  return new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify({summary:'待确认',week_start:null,courses:[{title:'合成课程',weekday:2,start_time:'10:00',end_time:'12:00'}]})}}],usage:{total_tokens:100,prompt_tokens:60,completion_tokens:40}}),{status:200});
 }) as typeof fetch);
 assert.equal(body.model,'deepseek-flash');assert.equal(reply.changes?.length,1);assert.equal(reply.changes?.[0]?.type,'create_task');
 assert.equal(reply.changes?.[0]?.fixed,true);assert.notEqual(reply.usable,false);
});
