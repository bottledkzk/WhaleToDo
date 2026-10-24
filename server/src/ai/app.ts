import Fastify from 'fastify';
import { randomUUID } from 'node:crypto';
import { ApiError } from '../lib/security.ts';
import type { AiConfig } from './config.ts';
import { AiStore,type AiRow } from './store.ts';
import { callDeepSeek,ProviderFailure,MODES,type AiInput,type ModelReply } from './provider.ts';
export function requestView(row:AiRow){return {id:row.client_request_id,status:row.status,mode:row.mode,
 text:row.response_text,usage_tokens:row.usage_tokens,error_code:row.error_code,created_at:row.created_at.toISOString()};}
export function buildAiApp(config:AiConfig,store:AiStore,provider:(input:AiInput)=>Promise<ModelReply>=(input)=>callDeepSeek(config,input)){
 const app=Fastify({bodyLimit:32768,ajv:{customOptions:{removeAdditional:false}},trustProxy:'127.0.0.1',logger:false,requestTimeout:55000,genReqId:()=>randomUUID()});
 app.setErrorHandler((err,req,reply)=>{
 const status=(err as {statusCode?:number}).statusCode;
 const e=err instanceof ApiError?err:new ApiError(status===400||status===413?'VALIDATION_ERROR':'INTERNAL',
 status===413?'输入内容过长':status===400?'请求格式无效':'服务暂时不可用');
 reply.code(e.status).send(e.toBody(req.id));
 });
 app.addHook('onSend',async(_req,reply,payload)=>{reply.header('Cache-Control','no-store');return payload;});
 app.get('/api/v1/ai/health',async(req)=>{await store.ready();return {request_id:req.id,data:{status:'ok'}};});
 async function auth(header:string|undefined){const m=/^Bearer ([A-Za-z0-9_-]{43})$/.exec(header??'');
 if(!m)throw new ApiError('UNAUTHENTICATED','请先登录');return store.authenticate(m[1]!);}
 app.get('/api/v1/ai/models',async(req)=>{await auth(req.headers.authorization);return {request_id:req.id,data:{items:[{code:'deepseek-default',title:'DeepSeek'}]}};});
 const uuid={type:'string',format:'uuid'};
 app.post<{Body:AiInput}>('/api/v1/ai/requests',{schema:{body:{type:'object',additionalProperties:false,
 required:['client_request_id','mode','prompt','model_code'],properties:{client_request_id:uuid,mode:{type:'string',enum:MODES},
 model_code:{const:'deepseek-default'},prompt:{type:'string',minLength:1,maxLength:4000},context:{type:'string',maxLength:2000}}}}},async(req,reply)=>{
 const user=await auth(req.headers.authorization);const body=req.body;
 if(!body.prompt.trim())throw new ApiError('VALIDATION_ERROR','请输入内容');
 // Rebuild a canonical supported-field object. Caller cannot set role, model URL, token limit or wallet.
 const input:AiInput={client_request_id:body.client_request_id,mode:body.mode,prompt:body.prompt,context:body.context??'',model_code:'deepseek-default'};
 const admission=await store.reserve(user,input,config);
 if(!admission.fresh)return reply.code(['reserved','running','unknown'].includes(admission.row.status)?202:200).send({request_id:req.id,data:requestView(admission.row)});
 const row=admission.row;
 try{
 await store.running(row.id);const result=await provider(input);const done=await store.finish(row.id,'succeeded',result);
 return {request_id:req.id,data:requestView(done)};
 }catch(error){
 const definite=error instanceof ProviderFailure&&error.definite;
 const done=await store.finish(row.id,definite?'failed':'unknown');
 return reply.code(definite?503:202).send({request_id:req.id,data:requestView(done)});
 }
 });
 app.get<{Params:{id:string}}>('/api/v1/ai/requests/:id',{schema:{params:{type:'object',required:['id'],properties:{id:uuid}}}},async(req)=>{
 const user=await auth(req.headers.authorization);const row=await store.get(user,req.params.id);
 if(!row)throw new ApiError('FORBIDDEN','无法访问此请求');return {request_id:req.id,data:requestView(row)};
 });
 return app;
}
