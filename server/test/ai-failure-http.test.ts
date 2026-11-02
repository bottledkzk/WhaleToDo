import {test} from 'node:test';
import assert from 'node:assert/strict';
import {buildAiApp} from '../src/ai/app.ts';
import type {AiConfig} from '../src/ai/config.ts';
import type {AiStore,AiRow} from '../src/ai/store.ts';
import {ProviderFailure,type AiInput,type ModelReply} from '../src/ai/provider.ts';

test('completed proposal rejection keeps its explanation in HTTP 200 on submit, replay and query; outages retain 503/202',async()=>{
 const text='调整后的课程均与已有安排冲突：计算机网络（11/3 10:00）与“固定例会”重叠';
 let row:AiRow|undefined,calls=0,outcome='';
 let behavior:'rejected'|'unavailable'|'unknown'='rejected';
 const store={authenticate:async()=> 'synthetic-owner',vip:async()=>true,
  reserve:async(_user:string,input:AiInput)=>{
   if(row)return {fresh:false,row};
   row={id:input.client_request_id,user_id:'synthetic-owner',client_request_id:input.client_request_id,mode:input.mode,status:'reserved',points:1,response_text:null,error_code:null,usage_tokens:null,created_at:new Date(0),tokens_per_point:3200,billing_strategy:'cumulative_tokens',charged_points:null,input_tokens:null,output_tokens:null,continuation:false,compacted:false,proposal:null} as AiRow;
   return {fresh:true,row};
  },running:async()=>{},
  finish:async(_id:string,status:string,reply?:ModelReply)=>{
   outcome=status;
   row={...row!,status,response_text:reply?.text??null,usage_tokens:reply?.tokens??null,charged_points:status==='unknown'?null:0,error_code:reply?.usable===false?'MODEL_OUTPUT_INVALID':status==='unknown'?'REQUEST_PENDING':'UPSTREAM_UNAVAILABLE'} as AiRow;
   return row;
  },get:async()=>row,
 } as unknown as AiStore;
 const app=buildAiApp({editEnabled:true,tokensPerPoint:3200} as AiConfig,store,async()=>{
  calls++;
  if(behavior!=='rejected')throw new ProviderFailure(behavior==='unavailable');
  return {text,tokens:120,changes:[],usable:false};
 });
 const headers={authorization:'Bearer '+'A'.repeat(43)};
 const payload={client_request_id:'00000000-0000-4000-a000-000000000073',mode:'edit',model_code:'deepseek-planner',prompt:'导入这周课表',context:'已有日程',edit_consent:true};
 const submit=()=>app.inject({method:'POST',url:'/api/v1/ai/requests',headers,payload});
 try{
  for(const response of [await submit(),await submit(),await app.inject({method:'GET',url:'/api/v1/ai/requests/'+payload.client_request_id,headers})]){
   assert.equal(response.statusCode,200);
   const data=response.json().data;
   assert.equal(data.status,'failed');assert.equal(data.error_code,'MODEL_OUTPUT_INVALID');
   assert.equal(data.text,text);assert.equal(data.points_charged,0);
  }
  assert.equal(calls,1);assert.equal(outcome,'failed');
  row=undefined;behavior='unavailable';assert.equal((await submit()).statusCode,503);assert.equal(outcome,'failed');
  row=undefined;behavior='unknown';assert.equal((await submit()).statusCode,202);assert.equal(outcome,'unknown');
 }finally{await app.close();}
});
