import {test} from 'node:test';
import assert from 'node:assert/strict';
import {buildAiApp} from '../src/ai/app.ts';
import type {AiConfig} from '../src/ai/config.ts';
import type {AiStore,AiRow} from '../src/ai/store.ts';
import type {AiInput,ModelReply} from '../src/ai/provider.ts';
import {DIARY_STYLES} from '../src/ai/diary-prose.ts';

test('HTTP style contract admits four styles and old clients; rejects wrong-mode/unknown values before reservation',async()=>{
 let admissions=0;const seen:AiInput[]=[];let row:AiRow;
 const store={authenticate:async()=> 'synthetic-owner',
  reserve:async(_user:string,input:AiInput)=>{admissions++;row={id:input.client_request_id,user_id:'synthetic-owner',client_request_id:input.client_request_id,mode:input.mode,status:'reserved',points:1,response_text:null,error_code:null,usage_tokens:null,created_at:new Date(0),tokens_per_point:3200,billing_strategy:'cumulative_tokens',charged_points:null,input_tokens:null,output_tokens:null,continuation:false,compacted:false,proposal:null,fingerprint:input.sourceFingerprint!};return {fresh:true,row};},
  running:async()=>{},finish:async(_id:string,status:string,reply:ModelReply)=>({...row,status,response_text:reply.text,usage_tokens:reply.tokens,charged_points:0}),
 } as unknown as AiStore;
 const app=buildAiApp({tokensPerPoint:3200,billingStrategy:'cumulative_tokens'} as AiConfig,store,async input=>{seen.push(input);return {text:'我整理了笔记。',tokens:20};});
 const headers={authorization:'Bearer '+'A'.repeat(43)};
 const body={client_request_id:'00000000-0000-4000-a000-000000000099',mode:'diary',model_code:'deepseek-default',prompt:'写下记录'};
 try{
  for(const payload of [{...body,diary_style:'unknown'},{...body,mode:'chat',diary_style:'concise'}])assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload})).statusCode,400);
  assert.equal(admissions,0);
  for(const style of DIARY_STYLES){assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload:{...body,diary_style:style}})).statusCode,200);assert.equal(seen.at(-1)!.diary_style,style);}
  assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload:body})).statusCode,200);
  assert.equal(seen.at(-1)!.diary_style,undefined);
 }finally{await app.close();}
});
