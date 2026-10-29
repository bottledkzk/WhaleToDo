/** Counts and ordering apply only to the visible copy, never to omitted records or user authorization. */
function visibleSummary(tasks:Record<string,unknown>[]) {
 const unfinished=tasks.filter(t=>t.done===false);
 const numeric=(v:unknown,fallback:number)=>typeof v==='number'&&Number.isFinite(v)?v:fallback;
 const deadline=(v:unknown)=>numeric(v,0)>0?numeric(v,Infinity):Infinity;
 const priority=[...unfinished].sort((a,b)=>deadline(a.deadline_ms)-deadline(b.deadline_ms)||numeric(b.importance,0)-numeric(a.importance,0));
 return {visible_tasks:tasks.length,completed:tasks.filter(t=>t.done===true).length,unfinished:unfinished.length,
  priority_rule:'可见未完成任务：先截止升序，同截止按重要度降序；不表示已经排入日程',priority_task_ids:priority.slice(0,3).map(t=>t.id)};
}
/** Normalize the current client wire copy before model interpretation; text stays untrusted user data. */
export function normalizedAgenda(context:string):unknown {
 let text=context,wrapper:Record<string,unknown>|null=null;
 try{
  const parsed=JSON.parse(context);
  if(typeof parsed?.agenda==='string'){text=parsed.agenda;wrapper=parsed;}
  else if(Array.isArray(parsed?.tasks)&&Array.isArray(parsed?.blocks)&&typeof parsed.timezone==='string'){
   try{
    const format=new Intl.DateTimeFormat('sv-SE',{timeZone:parsed.timezone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});
    const local=(ms:unknown)=>typeof ms==='number'&&Number.isSafeInteger(ms)&&ms>0?format.format(ms):null;
    return {...parsed,time_semantics:'tasks.estimated_minutes 是预计工时，不是实际耗时；done 不证明完成日期',visible_summary:visibleSummary(parsed.tasks),tasks:parsed.tasks.map((t:Record<string,unknown>)=>({...t,minutes:undefined,estimated_minutes:t.minutes,deadline_local:local(t.deadline_ms)})),blocks:parsed.blocks.map((b:Record<string,unknown>)=>({...b,start_local:local(b.start_ms),end_local:local(b.end_ms)}))};
   }catch{return parsed;}
  }else return parsed;
 }catch{/* Existing client agendaText is intentionally not JSON. */}
 const timezone=/^时区=([^;\r\n]+)/u.exec(text)?.[1];
 if(!timezone)return context;
 let format:Intl.DateTimeFormat;
 try{format=new Intl.DateTimeFormat('sv-SE',{timeZone:timezone,year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'});}catch{return context;}
 const instant=(value:string)=>{const ms=Date.parse(value);return Number.isFinite(ms)?{ms,local:format.format(ms)}:null;};
 const tasks:Record<string,unknown>[]=[],blocks:unknown[]=[],other:string[]=[];
 for(const line of text.split(/\r?\n/u)){
  const task=/^task id=(\S+) 标题=([^\r\n]*) 类型=(\S+)(?: 重要度=(\d))? 工时=(\d+)min 截止=(\S+) 完成=(true|false)$/u.exec(line);
  if(task){const deadline=instant(task[6]!);tasks.push({id:task[1],title:task[2],category:task[3],importance:task[4]?Number(task[4]):null,estimated_minutes:Number(task[5]),done:task[7]==='true',deadline_ms:deadline?.ms??null,deadline_local:deadline?.local??null});continue;}
  const block=/^block id=(\S+) task=(\S+) 开始=(\S+) 结束=(\S+) 固定=(true|false) 完成=(true|false)$/u.exec(line);
  if(block){const start=instant(block[3]!),end=instant(block[4]!);blocks.push({id:block[1],task_id:block[2],start_ms:start?.ms??null,end_ms:end?.ms??null,start_local:start?.local??null,end_local:end?.local??null,locked:block[5]==='true',done:block[6]==='true'});continue;}
  if(line.trim())other.push(line);
 }
 if(!tasks.length&&!blocks.length)return context;
 const agenda={timezone,date:/日期=(\d{4}-\d{2}-\d{2})/u.exec(text)?.[1],tasks,blocks,time_semantics:'estimated_minutes 是预计工时，不是实际耗时；done 不证明完成日期',visible_summary:visibleSummary(tasks),availability_and_notices:other,partial:text.includes('部分段落未附带')||text.includes('其余条目未附带')};
 return wrapper?{...wrapper,agenda}:agenda;
}
