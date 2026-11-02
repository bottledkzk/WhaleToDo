import test from 'node:test';
import assert from 'node:assert/strict';
import {validateImage,type AiImage} from '../src/ai/image-input.ts';
import {imageCourses,courseRequest,selectCourseChanges} from '../src/ai/image-courses.ts';
import {prepareInput,providerPayload,tokenReservation,callDeepSeek,type AiInput} from '../src/ai/provider.ts';
import type {AiConfig} from '../src/ai/config.ts';
import {buildAiApp} from '../src/ai/app.ts';
import type {AiStore,AiRow} from '../src/ai/store.ts';

function frame(w=640,h=480,padding=0):AiImage {
 const bytes=Buffer.from([255,216,255,192,0,11,8,h>>8,h&255,w>>8,w&255,1,1,17,0,255,217]);
 return {mime_type:'image/jpeg',data:Buffer.concat([bytes.subarray(0,-2),Buffer.alloc(padding),bytes.subarray(-2)]).toString('base64')};
}
const context=JSON.stringify({timezone:'Asia/Shanghai',date:'2026-10-01',action_version:4,tasks:[],blocks:[],available:[],partial:false});
const input:AiInput={client_request_id:'image-test',mode:'edit',model_code:'deepseek-planner',edit_consent:true,prompt:'请把课表加入下周日程',context,image:frame()};

test('generic image retry carries latest user week rather than assistant or screenshot date',()=>{
 const now=Date.parse('2026-11-02T06:44:00Z');
 const text=JSON.stringify({summary:'课表',week_start:'2025-09-01',courses:[{title:'网络',weekday:2,start_time:'10:00',end_time:'12:00'}]});
 const history=[{role:'user',content:'这周就行'},{role:'assistant',content:'请指定下周或其他日期'}];
 const effective=courseRequest('请识别这张课表并生成待确认的日程提案',history);
 assert.ok(effective.startsWith('这周就行'));
 assert.equal(new Date(imageCourses(text,context,effective,now).changes[0]!.start_ms!).toISOString(),'2026-11-03T02:00:00.000Z');
 assert.equal(new Date(imageCourses(text,context,courseRequest('改为下周',history),now).changes[0]!.start_ms!).toISOString(),'2026-11-10T02:00:00.000Z');
 assert.equal(new Date(imageCourses(text,context,'请识别课表',now).changes[0]!.start_ms!).toISOString(),'2026-11-03T02:00:00.000Z');
 assert.equal(courseRequest('导入课表',[{role:'assistant',content:'下周'}]),'导入课表');
});

test('a collision retains other courses and describes the conflicting task without silently moving it',()=>{
 const now=Date.parse('2026-11-02T06:44:00Z'),start=Date.parse('2026-11-03T02:00:00Z');
 const agenda=JSON.stringify({...JSON.parse(context),tasks:[{id:'old',title:'固定例会',done:false}],blocks:[{id:'b',task_id:'old',start_ms:start,end_ms:start+3600000,locked:true,done:false}]});
 const text=JSON.stringify({summary:'课表',week_start:null,courses:[{title:'网络',weekday:2,start_time:'10:00',end_time:'12:00'},{title:'数据库',weekday:4,start_time:'14:00',end_time:'15:30'}]});
 const result=selectCourseChanges(imageCourses(text,agenda,'这周',now),agenda);
 assert.equal(result.changes.length,1);assert.equal(result.changes[0]!.title,'数据库');assert.equal(result.changes[0]!.target_id,'new:1');
 assert.match(result.summary,/网络.*固定例会/u);assert.match(result.summary,/11(?:月|\/)3/u);
 assert.ok(result.changes.every(c=>c.type==='create_task'));
 const all=selectCourseChanges({changes:imageCourses(text,agenda,'这周',now).changes.slice(0,1),summary:''},agenda);
 assert.equal(all.changes.length,0);assert.ok(all.summary.startsWith('调整后的'));assert.ok(all.summary.length<=200);assert.ok(!/[\r\n]/u.test(all.summary));
});

test('empty image result is an unbilled failed result with actionable feedback',async()=>{
 const config={apiKey:'sk-test',model:'text-only',maxOutput:16384,timeoutMs:1000} as AiConfig;
 const reply=await callDeepSeek(config,prepareInput(input),(async(_url:any,options:any)=>{
  assert.equal(JSON.parse(options.body).max_tokens,16384);
  return new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:JSON.stringify({summary:'需要每节课的钟点',week_start:null,courses:[]})}}],usage:{total_tokens:100,prompt_tokens:60,completion_tokens:40}}));
 }) as typeof fetch);
 assert.equal(reply.usable,false);assert.deepEqual(reply.changes,[]);assert.match(reply.text,/请明确/u);
});

test('full structured agenda above the previous 24KB threshold stays intact',()=>{
 const large=JSON.stringify({...JSON.parse(context),tasks:Array.from({length:100},(_,i)=>({id:String(i),title:'课程资料'.repeat(25),done:false}))});
 assert.ok(Buffer.byteLength(large)>24000);assert.ok(Buffer.byteLength(large)<100000);
 assert.equal(prepareInput({...input,context:large}).context,large);
});
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
test('HTTP image and capability errors stop before reservation, while valid images stay out of receipts',async()=>{
 let admissions=0,row:AiRow;
 const store={authenticate:async()=> 'owner',vip:async()=>true,
  reserve:async(_user:string,body:AiInput)=>{admissions++;row={id:body.client_request_id,user_id:'owner',client_request_id:body.client_request_id,mode:body.mode,status:'reserved',points:1,response_text:null,error_code:null,usage_tokens:null,created_at:new Date(),tokens_per_point:3200,billing_strategy:'cumulative_tokens',charged_points:null,input_tokens:null,output_tokens:null,continuation:false,compacted:false,proposal:null} as AiRow;return {fresh:true,row};},
  running:async()=>{},finish:async(_id:string,status:string,reply:any)=>({...row,status,response_text:reply.text,usage_tokens:reply.tokens,charged_points:1}),
 } as unknown as AiStore;
 const app=buildAiApp({editEnabled:true,tokensPerPoint:3200,billingStrategy:'cumulative_tokens'} as AiConfig,store,async()=>({text:'图片内容已读取',tokens:100}));
 const headers={authorization:'Bearer '+'A'.repeat(43)},body={...input,client_request_id:'00000000-0000-4000-a000-000000000016'};
 try {
  for(const payload of [{...body,image:{mime_type:'image/jpeg',data:'https://example.invalid/private'}},{...body,context:context.replace('"action_version":4','"action_version":3')},{...body,mode:'diary'}])assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload})).statusCode,400);
  assert.equal(admissions,0);
  assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',payload:body})).statusCode,401);
  const response=await app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload:body});assert.equal(response.statusCode,200);assert.equal(admissions,1);assert.ok(!response.body.includes(body.image!.data));
 }finally{await app.close();}
});
