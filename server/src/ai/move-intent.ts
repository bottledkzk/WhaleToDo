import {normalizedAgenda} from './agenda.ts';

type Move={target_id:string;start_ms:number;end_ms:number;timezone:string;schedule_task?:true};
const clarification={error:'请明确一个事项、日期和开始时间，例如“把阅读移到明天 10:00”'};
function clockParts(match:RegExpMatchArray,inherited?:string){
 let hour=Number(match[2]);const minute=Number(match[3]??(match[4]?'30':match[5]??'0'));
 const period=match[1]??inherited;
 if(period){if(hour>12||hour===0)return null;if(/下午|晚上|中午/u.test(period)&&hour<12)hour+=12;if(period==='上午'&&hour===12)hour=0;}
 return String(hour).padStart(2,'0')+':'+String(minute).padStart(2,'0')+':00';
}
function day(value:string):number|null{
 if(!/^\d{4}-\d{2}-\d{2}$/u.test(value))return null;
 const n=Date.parse(value+'T00:00:00Z');return Number.isFinite(n)&&new Date(n).toISOString().slice(0,10)===value?n:null;
}
/** Compile an explicitly requested local date/clock; never trust model epoch arithmetic. */
export function requestedMove(context:string,request:string):Move|{error:string}|null {
 let feedback=request,referenced:string|undefined,referencedTask:string|undefined;
 try{const v=JSON.parse(request);if(typeof v.feedback==='string'){
  feedback=v.feedback;
  if(Array.isArray(v.unaccepted_changes)&&v.unaccepted_changes.length===1&&v.unaccepted_changes[0]?.type==='move_block')referenced=v.unaccepted_changes[0].target_id;
  if(Array.isArray(v.unaccepted_changes)&&v.unaccepted_changes.length===1&&v.unaccepted_changes[0]?.type==='schedule_task')referencedTask=v.unaccepted_changes[0].target_id;
 }}catch{/* plain prompt */}
 if(/改名|标题|命名|分类|积分|会员|VIP|账号|账户|删除/iu.test(feedback))return null;
 if(/(?:不要|别)(?:把|将)?[^，。；\n]{0,80}?(?:移动|移到|调整|修改|安排|改到|改为)|不(?:移动|调整|修改|安排)/u.test(feedback))return {error:'本次要求不执行日程调整，原日程保持不变'};
 if(/从\s*(?:(?:上午|下午|晚上|中午)\s*)?\d{1,2}(?::[0-5]\d|点)[^，。；\n]{0,20}(?:或者|或是)\s*\d{1,2}(?::[0-5]\d|点)/u.test(feedback))return clarification;
 if(!/移到|移动到|挪到|改为|调整到|安排到|安排在|改到/u.test(feedback))return null;
 if(/之前|之后|以前|以后|左右|大后天|下个月|下月|明年|每[天周月]/u.test(feedback))return clarification;
 let clocks=[...feedback.matchAll(/(?<!\d)(?:(上午|下午|晚上|中午)\s*)?([01]?\d|2[0-3])(?::([0-5]\d)|点(?:(半)|([0-5]?\d)分?)?)(?![:\d])/gu)];
 let fromClock:string|null=null;
 if(clocks.length!==1&&clocks.length!==2)return clarification;
 if(clocks.length===2){
  const between=feedback.slice(clocks[0]!.index!+clocks[0]![0].length,clocks[1]!.index);
  if(/^\s*(?:移到|移动到|挪到|改为|调整到|改到)\s*$/u.test(between)&&/从\s*$/u.test(feedback.slice(0,clocks[0]!.index))){
   fromClock=clockParts(clocks[0]!);if(fromClock===null)return clarification;clocks=[clocks[1]!];
  }else if(!/^\s*(?:到|至|[-–—~～])\s*$/u.test(between))return clarification;
 }
 const parsed=normalizedAgenda(context) as any;
 const agenda=parsed?.agenda&&typeof parsed.agenda==='object'?parsed.agenda:parsed;
 const today=agenda?.date??agenda?.today,zone=agenda?.timezone;
 if(typeof today!=='string'||day(today)===null||typeof zone!=='string'||!Array.isArray(agenda.blocks)||!Array.isArray(agenda.tasks))return clarification;
 if(agenda.partial)return {error:'日程资料不完整，请缩小读取范围后重试'};
 const matching=agenda.tasks.filter((t:any)=>typeof t.title==='string'&&t.title.trim()&&(feedback.includes(t.title)||feedback.includes(t.id)));
 const longest=Math.max(0,...matching.map((t:any)=>t.title.length));
 const named=matching.filter((t:any)=>t.title.length===longest);
 const byId=agenda.blocks.filter((b:any)=>typeof b.id==='string'&&feedback.includes(b.id));
 if(!byId.length&&named.length>1)return {error:'存在同名事项，请提供唯一事项或安排编号'};
 const explicit=/(?:把|将)(.+?)(?:移到|移动到|挪到|改为|调整到|安排到|安排在|改到)/u.exec(feedback)?.[1]?.trim();
 if(explicit&&!named.length&&!byId.length&&!referenced&&!referencedTask&&!/^(?:它|这个安排|该安排|任务|事项|日程)$/u.test(explicit))return {error:'未找到指定事项，请核对事项名称或安排编号'};
 let candidates=referencedTask?[]:byId.length?byId:named.length?agenda.blocks.filter((b:any)=>named.some((t:any)=>t.id===b.task_id)):referenced?agenda.blocks.filter((b:any)=>b.id===referenced):agenda.blocks;
 if(fromClock){
  try {const clock=new Intl.DateTimeFormat('en-GB',{timeZone:zone,hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
   candidates=candidates.filter((b:any)=>Number.isSafeInteger(b.start_ms)&&clock.format(b.start_ms)===fromClock);
  }catch{return {error:'日程日期或时区无效，请重新读取'};}
 }
 let scheduleTask=false;
 let block=candidates[0];
 if(candidates.length===0&&(/安排到|安排在/u.test(feedback)||referencedTask)){
  if(agenda.action_version!==2)return {error:'请升级应用后使用新增安排功能'};
  const targets=referencedTask?agenda.tasks.filter((t:any)=>t.id===referencedTask):named.length?named:agenda.tasks;
  const task=targets.length===1?targets[0]:null;
  const minutes=task?.estimated_minutes??task?.minutes;
  if(!task||task.done!==false||!Number.isSafeInteger(minutes)||minutes<1||minutes>1440||agenda.blocks.some((b:any)=>b.task_id===task.id))return {error:'请指定一个尚未安排、未完成且已填写预计工时的事项'};
  block={id:task.id,start_ms:day(today)!+3600000,end_ms:day(today)!+3600000+minutes*60000};scheduleTask=true;
 }else if(candidates.length!==1)return {error:'无法唯一确定要调整的安排，请指定事项名称或安排编号'};
 if(!Number.isSafeInteger(block.start_ms)||!Number.isSafeInteger(block.end_ms)||block.end_ms<=block.start_ms)return clarification;
 try{
  const format=new Intl.DateTimeFormat('sv-SE',{timeZone:zone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
  const clock=clockParts(clocks[0]!);if(!clock)return clarification;
  let date=scheduleTask?today:format.format(block.start_ms).slice(0,10);
  const dates=[...feedback.matchAll(/(\d{4})(?:[-/]|年)(\d{1,2})(?:[-/]|月)(\d{1,2})日?|(\d{1,2})月(\d{1,2})日?|(今天|明天|后天)|(本周|下周|这周|周|星期)([一二三四五六日天])/gu)];
  if(dates.length>1)return clarification;
  if(dates.length){const d=dates[0]!;
   if(d[1])date=d[1]+'-'+d[2]!.padStart(2,'0')+'-'+d[3]!.padStart(2,'0');
   else if(d[4])date=today.slice(0,4)+'-'+d[4].padStart(2,'0')+'-'+d[5]!.padStart(2,'0');
   else if(d[6])date=new Date(day(today)!+['今天','明天','后天'].indexOf(d[6])*86400000).toISOString().slice(0,10);
   else {const wanted='一二三四五六日'.indexOf(d[8]==='天'?'日':d[8]!)+1;const current=new Date(day(today)!).getUTCDay()||7;
    let offset=wanted-current;if(d[7]==='下周')offset+=7;else if((d[7]==='周'||d[7]==='星期')&&offset<0)offset+=7;
    date=new Date(day(today)!+offset*86400000).toISOString().slice(0,10);}
  }else if(/昨天|大后天|下个|下月|下周|周末|月底|明年|每[天周月]|之前|之后|左右|或者|或是|未来|\d{1,2}[-/]\d{1,2}|[一二三四五六七八九十0-9]+天后/u.test(feedback))return clarification;
  if(day(date)===null)return {error:'日期无效，请核对年月日'};
  const resolve=(wallClock:string)=>{
   const wanted=date+' '+wallClock,localUtc=Date.parse(date+'T'+wallClock+'Z'),matches=new Set<number>();
   for(const delta of [-86400000,0,86400000]){
    const probe=localUtc+delta,offset=Date.parse(format.format(probe).replace(' ','T')+'Z')-probe,target=localUtc-offset;
    if(format.format(target)===wanted)matches.add(target);
   }
   return matches.size===1?[...matches][0]!:null;
  };
  const start=resolve(clock);
  if(start===null)return {error:'该本地时间不存在或有时区歧义，请选择其他明确时段'};
  const end=start+block.end_ms-block.start_ms;
  if(clocks.length===2){
   const endClock=clockParts(clocks[1]!,clocks[0]![1]);if(!endClock)return clarification;
   const explicitEnd=resolve(endClock);
   if(explicitEnd===null)return {error:'该本地时间不存在或有时区歧义，请选择其他明确时段'};
   if(explicitEnd!==end)return {error:'所选时段与事项工时不一致，请保留预计工时并选择同一天的完整时段'};
  }
  return {target_id:block.id,start_ms:start,end_ms:end,timezone:zone,...(scheduleTask?{schedule_task:true as const}:{})};
 }catch{return {error:'日程日期或时区无效，请重新读取'};}
}
