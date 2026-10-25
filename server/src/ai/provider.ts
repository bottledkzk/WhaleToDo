import type { AiConfig } from './config.ts';
export const PROVIDER_URL='https://api.deepseek.com/chat/completions';
export {MODES,systemPrompt} from './policy.ts';
export type {Mode} from './policy.ts';
import {type Mode,type EditChange,systemPrompt,softCompact,parseProposal,fingerprint} from './policy.ts';
export interface AiInput {client_request_id:string; mode:Mode; prompt:string; context?:string; model_code:'deepseek-default'|'deepseek-planner'; edit_consent?:boolean; history?:{role:'user'|'assistant';content:string}[]; sourceFingerprint?:string; compacted?:boolean;share_style?:string}
export interface ModelReply {text:string; tokens:number;inputTokens?:number;outputTokens?:number;continuation?:boolean;changes?:EditChange[]}
export class ProviderFailure extends Error {
 readonly definite:boolean;
 constructor(definite:boolean){super('Provider request failed');this.definite=definite;}
}
export function prepareInput(input:AiInput):AiInput {
 const prompt=softCompact(input.prompt,7000);const context=softCompact(input.context??'',8000);
 const history=(input.history??[]).slice(-8).map(h=>({...h,content:softCompact(h.content,1500).text}));
 return {...input,prompt:prompt.text,context:context.text,history,sourceFingerprint:fingerprint(input),
 compacted:prompt.omitted>0||context.omitted>0||(input.history?.length??0)>8};
}
export function tokenReservation(input:AiInput,maxOutput:number):number {
 // UTF-8 bytes conservatively bound text tokenization; include fixed framing allowance.
 return Buffer.byteLength(systemPrompt(input.mode,input.share_style)+input.prompt+(input.context??'')+(input.history??[]).map(h=>h.content).join(''),'utf8')+maxOutput+256;
}
async function boundedText(response:Response):Promise<string> {
 if(!response.body) throw new ProviderFailure(false);
 const reader=response.body.getReader();const chunks:Uint8Array[]=[];let size=0;
 try {while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;
 if(size>131072){await reader.cancel();throw new ProviderFailure(false);}chunks.push(value);}}
 finally {reader.releaseLock();}
 return Buffer.concat(chunks).toString('utf8');
}
export async function callDeepSeek(config:AiConfig,input:AiInput,fetcher:typeof fetch=fetch):Promise<ModelReply> {
 try {
 const response=await fetcher(PROVIDER_URL,{method:'POST',redirect:'error',
 headers:{Authorization:`Bearer ${config.apiKey}`,'Content-Type':'application/json'},
 body:JSON.stringify({model:input.model_code==='deepseek-planner'?(config.editModel??'deepseek-v4-pro'):config.model,thinking:{type:'disabled'},stream:false,max_tokens:config.maxOutput,
 ...(input.mode==='edit'?{response_format:{type:'json_object'}}:{}),
 messages:[{role:'system',content:systemPrompt(input.mode,input.share_style)},...(input.history??[]),{role:'user',content:input.prompt+(input.context?'\n用户选择的安排摘要：\n'+input.context:'')}]}),
 signal:AbortSignal.timeout(config.timeoutMs)});
 if(!response.ok){await response.body?.cancel();throw new ProviderFailure([400,401,402,403,404,413,422,429].includes(response.status));}
 const result=JSON.parse(await boundedText(response));
 const choice=result.choices?.[0];const text=choice?.message?.content;const tokens=result.usage?.total_tokens;
 if(typeof text!=='string'||!text.trim()||Buffer.byteLength(text)>65536||!['stop','length'].includes(choice.finish_reason)||
 !Number.isSafeInteger(tokens)||tokens<=0||tokens>tokenReservation(input,config.maxOutput)) throw new ProviderFailure(false);
 const continuation=choice.finish_reason==='length';
 const inputTokens=result.usage?.prompt_tokens;const outputTokens=result.usage?.completion_tokens;
 const usage={tokens,inputTokens:Number.isSafeInteger(inputTokens)&&inputTokens>=0?inputTokens:undefined,outputTokens:Number.isSafeInteger(outputTokens)&&outputTokens>=0?outputTokens:undefined,continuation};
 if(input.mode==='edit'){
 if(continuation)return {text:'本次提案未完整生成，请缩小调整范围后重试。',...usage};
 const proposal=parseProposal(text);return {text:proposal.summary,...usage,changes:proposal.changes};}
 const end=Math.max(...['。','！','？','.','!','?'].map(p=>text.lastIndexOf(p)));
 const safeText=continuation?(end>=0?text.slice(0,end+1):'内容未在本次篇幅内完成，请缩小范围后继续。'):text.trim();
 return {text:safeText,...usage};
 }catch(err){if(err instanceof ProviderFailure)throw err;throw new ProviderFailure(false);}
}
