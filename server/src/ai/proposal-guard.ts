import type {EditChange} from './policy.ts';

type TaskFact={id:string;done:boolean;deadline:number};
type BlockFact={id:string;taskId:string;start:number;end:number;locked:boolean;done:boolean};
function millis(value:unknown):number{
 if(typeof value==='number'&&Number.isSafeInteger(value))return value;
 if(typeof value==='string'){const n=Date.parse(value);if(Number.isFinite(n))return n;}
 return 0;
}
/** Advisory guard over the supplied read-only copy. Client snapshot/owner/permission gates remain mandatory. */
export function proposalProblem(changes:EditChange[],context:string,now=Date.now(),request=''):string|null {
 const tasks:TaskFact[]=[],blocks:BlockFact[]=[];let windows:{start:number;end:number}[]=[];
 let text=context,timezone:string|undefined;
 try{
  const value=JSON.parse(context);
  if(typeof value?.agenda==='string')text=value.agenda;
  else if(Array.isArray(value?.tasks)&&Array.isArray(value?.blocks)){
   timezone=typeof value.timezone==='string'?value.timezone:undefined;
   for(const task of value.tasks){
    if(typeof task?.id!=='string'||typeof task.done!=='boolean')return '日程资料不完整，请重新读取';
    tasks.push({id:task.id,done:task.done,deadline:millis(task.deadline_ms??task.deadline)});
   }
   for(const block of value.blocks){
    if(typeof block?.id!=='string'||typeof block.task_id!=='string'||typeof block.locked!=='boolean'||typeof block.done!=='boolean')return '日程资料不完整，请重新读取';
    blocks.push({id:block.id,taskId:block.task_id,start:millis(block.start_ms),end:millis(block.end_ms),locked:block.locked,done:block.done});
   }
   if(Array.isArray(value.available))windows=value.available.map((w:{start_ms:unknown;end_ms:unknown})=>({start:millis(w.start_ms),end:millis(w.end_ms)}));
   text='';
  }
 }catch{/* Existing clients supply agendaText rather than JSON. Parse only explicit facts, never request/history. */}
 if(text){
  timezone=/^时区=([^;\r\n]+)/u.exec(text)?.[1];
  for(const line of text.split(/\r?\n/u)){
   const task=/^task id=(\S+) 标题=[^\r\n]* 类型=\S+(?: 重要度=\d)? 工时=\d+min 截止=(\S+) 完成=(true|false)$/u.exec(line);
   if(task)tasks.push({id:task[1]!,done:task[3]==='true',deadline:task[2]==='无'?0:millis(task[2])});
   const block=/^block id=(\S+) task=(\S+) 开始=(\S+) 结束=(\S+) 固定=(true|false) 完成=(true|false)$/u.exec(line);
   if(block)blocks.push({id:block[1]!,taskId:block[2]!,start:millis(block[3]),end:millis(block[4]),locked:block[5]==='true',done:block[6]==='true'});
  }
 }
 if(new Set(tasks.map(t=>t.id)).size!==tasks.length||new Set(blocks.map(b=>b.id)).size!==blocks.length)return '日程编号重复，请重新读取';
 const moved=new Map<string,{start:number;end:number}>();
 const requestedTimes=[...request.matchAll(/(?:移到|移动到|挪到|改为|调整到|安排到)\s*([01]?\d|2[0-3]):([0-5]\d)/gu)];
 for(const change of changes){
  if(change.type==='move_block'){
   const start=change.start_ms,end=change.end_ms;
   if(typeof start!=='number'||typeof end!=='number'||!Number.isSafeInteger(start)||!Number.isSafeInteger(end))return '提案时间格式无效';
   const block=blocks.find(b=>b.id===change.target_id),task=tasks.find(t=>t.id===block?.taskId);
   if(!block||!task)return '提案引用的安排不存在，请重新读取';
   if(block.locked||block.done||task.done||block.start<=now)return '固定、已开始或已完成的安排不能修改';
   if(start===block.start&&end===block.end)return '提案没有产生有效时间调整，请核对要求';
   if(timezone&&requestedTimes.length===1&&changes.filter(c=>c.type==='move_block').length===1){
    try{
     const actual=new Intl.DateTimeFormat('en-GB',{timeZone:timezone,hour:'2-digit',minute:'2-digit',hourCycle:'h23'}).format(start);
     const requested=requestedTimes[0]![1]!.padStart(2,'0')+':'+requestedTimes[0]![2];
     if(actual!==requested)return '提案时间与本次要求不一致，请补充要求后重试';
    }catch{return '日程时区无效，请重新读取';}
   }
   if(start<=now||end-start!==block.end-block.start)return '请保留工时并选择未来时段';
   if(task.deadline>0&&end>task.deadline)return '调整后的安排超过事项截止时间';
   if(windows.length&&!windows.some(w=>w.start<=start&&w.end>=end))return '调整后的安排不在可用时间内';
   if(moved.has(block.id))return '同一安排不能重复调整';
   moved.set(block.id,{start,end});
  }else{
   const task=tasks.find(t=>t.id===change.target_id);
   if(!task||task.done)return '事项不存在或已完成';
  }
 }
 for(const [id,window] of moved){
  for(const other of blocks){
   if(other.id===id)continue;
   const target=moved.get(other.id)??other;
   if(window.start<target.end&&target.start<window.end)return '调整后的安排与其他时段冲突';
  }
 }
 return null;
}
