import {normalizedAgenda} from './agenda.ts';

/** Bind an explicit, unambiguous local clock to milliseconds in code, never by model arithmetic. */
export function requestedMove(context:string,request:string):{target_id:string;start_ms:number;end_ms:number;timezone:string}|{error:string}|null {
 let feedback=request,referenced:string|undefined;
 try{
  const value=JSON.parse(request);
  if(typeof value.feedback==='string'){
   feedback=value.feedback;
   if(Array.isArray(value.unaccepted_changes)&&value.unaccepted_changes.length===1&&value.unaccepted_changes[0]?.type==='move_block')referenced=value.unaccepted_changes[0].target_id;
  }
 }catch{/* Current plain prompts remain supported. */}
 if(/明天|后天|昨天|\d{4}[-/]\d|改名|标题|命名|分类|积分|会员|VIP|账号|账户|删除|不(?:要)?(?:移动|调整|修改)/iu.test(feedback))return null;
 const times=[...feedback.matchAll(/(?:移到|移动到|挪到|改为|调整到|安排到)\s*([01]?\d|2[0-3]):([0-5]\d)(?!\d|\s*(?:之前|之后|以前|以后|前|后|左右))/gu)];
 if(times.length!==1)return null;
 const parsed=normalizedAgenda(context) as any;
 const agenda=parsed?.agenda&&typeof parsed.agenda==='object'?parsed.agenda:parsed;
 const date=agenda?.date??agenda?.today,zone=agenda?.timezone;
 if(typeof date!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(date)||typeof zone!=='string'||!Array.isArray(agenda.blocks))return null;
 const candidates=agenda.blocks.filter((b:any)=>typeof b.id==='string'&&(referenced?b.id===referenced:agenda.blocks.length===1||feedback.includes(b.id)));
 if(candidates.length!==1)return null;
 const block=candidates[0];
 if(!Number.isSafeInteger(block.start_ms)||!Number.isSafeInteger(block.end_ms)||block.end_ms<=block.start_ms)return null;
 const hour=times[0]![1]!.padStart(2,'0'),minute=times[0]![2]!,wanted=date+' '+hour+':'+minute+':00';
 try{
  const format=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
  const localUtc=Date.parse(date+'T'+hour+':'+minute+':00Z');
  if(!Number.isFinite(localUtc))return null;
  const matches=new Set<number>();
  for(const delta of [-86400000,0,86400000]){
   const probe=localUtc+delta,wall=format.format(probe);
   const offset=Date.parse(wall.replace(' ','T')+'Z')-probe;
   const target=localUtc-offset;
   if(format.format(target)===wanted)matches.add(target);
  }
  // DST gaps and ambiguous repeated clocks need clarification, never an automatic choice.
  if(matches.size!==1)return {error:'该本地时间不存在或有时区歧义，请明确日期与时间偏移后重试'};
  const start=[...matches][0]!;
  return {target_id:block.id,start_ms:start,end_ms:start+block.end_ms-block.start_ms,timezone:zone};
 }catch{return null;}
}
