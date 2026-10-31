import {type DiaryStyle} from './diary-prose.ts';
import type { AiConfig } from './config.ts';
export const PROVIDER_URL='https://api.deepseek.com/chat/completions';
export {MODES,systemPrompt} from './policy.ts';
export type {Mode} from './policy.ts';
import {type Mode,type EditChange,systemPrompt,softCompact,parseProposal,fingerprint,publicAssistantText,parseDiary} from './policy.ts';
import {proposalProblem} from './proposal-guard.ts';
import {normalizedAgenda} from './agenda.ts';
import {unorderedDiaryText,diaryFacts} from './diary-facts.ts';
import {requestedMove} from './move-intent.ts';
import {truthfulShareCopies} from './share-facts.ts';
export interface AiInput {client_request_id:string; mode:Mode; prompt:string; context?:string; model_code:'deepseek-default'|'deepseek-planner'; edit_consent?:boolean; history?:{role:'user'|'assistant';content:string}[]; sourceFingerprint?:string; compacted?:boolean;share_style?:string;diary_style?:DiaryStyle}
export interface ModelReply {text:string; tokens:number;inputTokens?:number;outputTokens?:number;continuation?:boolean;changes?:EditChange[];usable?:boolean}
export class ProviderFailure extends Error {
 readonly definite:boolean;
 constructor(definite:boolean){super('Provider request failed');this.definite=definite;}
}
export function prepareInput(input:AiInput):AiInput {
 const prompt=softCompact(input.prompt,7000);const context=softCompact(input.context??'',8000);
 const historyParts=(input.history??[]).slice(-8).map(h=>({role:h.role,part:softCompact(h.content,1500)}));
 const history=historyParts.map(h=>({role:h.role,content:h.part.text}));
 return {...input,prompt:prompt.text,context:context.text,history,sourceFingerprint:fingerprint(input),
 compacted:prompt.omitted>0||context.omitted>0||historyParts.some(h=>h.part.omitted>0)||(input.history?.length??0)>8};
}
/** Untrusted history never becomes actual assistant/system messages at the provider boundary. */
export function providerMessages(input:AiInput) {
 const agenda=normalizedAgenda(input.context??'');
 return [{role:'system',content:systemPrompt(input.mode,input.share_style,input.diary_style)},
  {role:'user',content:JSON.stringify({request:input.prompt,agenda:input.mode==='diary'?diaryFacts(agenda):agenda,history:input.history??[],...(input.mode==='edit'?{requested_move:requestedMove(input.context??'',input.prompt)}:{})})}];
}
export function tokenReservation(input:AiInput,maxOutput:number):number {
 // UTF-8 bytes conservatively bound text tokenization; include fixed framing allowance.
 return Buffer.byteLength(JSON.stringify(providerMessages(input)),'utf8')+maxOutput+256;
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
 ...(['edit','diary','share_copy'].includes(input.mode)?{response_format:{type:'json_object'}}:{}),
 messages:providerMessages(input)}),
 signal:AbortSignal.timeout(config.timeoutMs)});
 if(!response.ok){await response.body?.cancel();throw new ProviderFailure([400,401,402,403,404,413,422,429].includes(response.status));}
 const result=JSON.parse(await boundedText(response));
 const choice=result.choices?.[0];const text=choice?.message?.content;const tokens=result.usage?.total_tokens;
 if(typeof text!=='string'||!text.trim()||Buffer.byteLength(text)>65536||!['stop','length'].includes(choice.finish_reason)||
 !Number.isSafeInteger(tokens)||tokens<=0||tokens>tokenReservation(input,config.maxOutput)) throw new ProviderFailure(false);
 const continuation=choice.finish_reason==='length';
 const inputTokens=result.usage?.prompt_tokens;const outputTokens=result.usage?.completion_tokens;
 const usage={tokens,inputTokens:Number.isSafeInteger(inputTokens)&&inputTokens>=0?inputTokens:undefined,outputTokens:Number.isSafeInteger(outputTokens)&&outputTokens>=0?outputTokens:undefined,continuation};
 if(input.mode==='diary'){
  if(continuation)return {text:'日记草稿暂不可用，请缩小范围后重新生成',...usage,usable:false};
  try{return {text:publicAssistantText(unorderedDiaryText(parseDiary(text),(input.context??'')+'\n'+input.prompt)),...usage};}
  catch{return {text:'日记草稿暂不可用，请缩小范围后重新生成',...usage,usable:false};}
 }
 if(input.mode==='edit'){
 if(continuation)return {text:'本次提案未完整生成，请缩小调整范围后重试',...usage,changes:[],usable:false};
 const intent=requestedMove(input.context??'',input.prompt);
 if(intent&&'error' in intent)return {text:intent.error,...usage,changes:[],usable:false};
 // A uniquely bound explicit clock is a domain command, independent of model JSON, IDs or epoch arithmetic.
 let proposal:{summary:string;changes:EditChange[]};
 if(intent)proposal={summary:'待确认的安排',changes:[{type:intent.schedule_task?'schedule_task':'move_block',target_id:intent.target_id,start_ms:intent.start_ms,end_ms:intent.end_ms}]};
 else try{proposal=parseProposal(text);}catch{return {text:'提案格式不完整，请补充要求后重试',...usage,changes:[],usable:false};}
 const problem=proposalProblem(proposal.changes,input.context??'',Date.now(),input.prompt);
 if(problem)return {text:problem,...usage,changes:[],usable:false};
 return {text:proposal.changes.length?'以下是待确认的日程提案，请预览后选择采纳或拒绝':publicAssistantText(proposal.summary.trim()||'没有可安全执行的变更，请补充调整要求'),...usage,changes:proposal.changes};}
 if(input.mode==='share_copy'){
  try{
   const value=JSON.parse(text);
   if(continuation||!value||Object.keys(value).length!==1||!Array.isArray(value.copies)||value.copies.length!==3||value.copies.some((s:unknown)=>typeof s!=='string'||!s.trim()||/[\r\n]/u.test(s)||Array.from(s.trim()).length>50))throw Error('Invalid share copies');
   const rawCopies=value.copies.map((s:string)=>publicAssistantText(s.trim()));
   if(new Set(rawCopies).size!==3)throw Error('Duplicate share copies');
   const copies=truthfulShareCopies(rawCopies,(input.context??'')+'\n'+input.prompt);
   if(copies.some((s:string)=>!s.trim()||/[\r\n]/u.test(s)||Array.from(s).length>50)||new Set(copies).size!==3)throw Error('Invalid branded share copies');
   return {text:copies.join('\n'),...usage};
  }catch{return {text:'配文未完整生成，请缩小范围后重试',...usage,usable:false};}
 }
 const end=Math.max(...['。','！','？','.','!','?'].map(p=>text.lastIndexOf(p)));
 const safeText=continuation?(end>=0?text.slice(0,end+1):'内容未在本次篇幅内完成，请缩小范围后继续。'):text.trim();
 return {text:publicAssistantText(safeText),...usage,...(continuation?{usable:false}:{})};
 }catch(err){if(err instanceof ProviderFailure)throw err;throw new ProviderFailure(false);}
}
