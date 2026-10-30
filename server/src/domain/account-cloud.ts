import {ApiError} from '../lib/security.ts';

export type CloudKind = 'schedule'|'profile';
export type CloudDocument = {revision:number;payload:Record<string,unknown>;updated_at:number|null};
export function cloudKind(value:string):CloudKind {
 if(value!=='schedule'&&value!=='profile')throw new ApiError('VALIDATION_ERROR','无效云端内容类型');
 return value;
}
const invalid=():never=>{throw new ApiError('VALIDATION_ERROR','云端内容格式无效或超过限制');};
function obj(v:unknown):Record<string,any>{if(!v||typeof v!=='object'||Array.isArray(v))invalid();return v as Record<string,any>;}
function keys(v:Record<string,unknown>,allowed:string[]){if(Object.keys(v).some(k=>!allowed.includes(k)))invalid();}
function text(v:unknown,max:number){if(typeof v!=='string'||[...v].length>max)invalid();}
function num(v:unknown,min=0,max=Number.MAX_SAFE_INTEGER){if(typeof v!=='number'||!Number.isSafeInteger(v)||v<min||v>max)invalid();}
function bool(v:unknown){if(typeof v!=='boolean')invalid();}
function date(v:unknown){text(v,10);const d=new Date((v as string)+'T00:00:00Z');if(!/^\d{4}-\d{2}-\d{2}$/.test(v as string)||!Number.isFinite(d.getTime())||d.toISOString().slice(0,10)!==v)invalid();}

/** Separate allowlists prevent a schedule backup from carrying sessions, tokens, diaries or chat. */
export function validateCloudPayload(kind:CloudKind,input:unknown):Record<string,unknown>{
 const p=obj(input);
 if(Buffer.byteLength(JSON.stringify(p),'utf8')>1_500_000)invalid();
 if(kind==='profile'){
  keys(p,['entries']);const entries=obj(p.entries);
  if(Object.keys(entries).length>1500)invalid();
  for(const [k,v] of Object.entries(entries)){
   if(k==='profile.motto'){text(v,30);if(/[\p{Cc}\u2028\u2029]/u.test(v as string))invalid();}
   else if(k==='profile.nickname'){text(v,16);if(!(v as string).trim()||/[\p{Cc}\u2028\u2029]/u.test(v as string)||[...(v as string)].reduce((n,c)=>n+(/\p{Script=Han}/u.test(c)?2:1),0)>16)invalid();}
   else if(k.startsWith('diary:')){date(k.slice(6));text(v,800);if(!(v as string).trim())invalid();}
   else invalid();
  }
  return structuredClone(p);
 }
 keys(p,['schema_version','tasks','blocks','checkins','work_times','settings','reminders']);
 if(p.schema_version!==1)invalid();
 const rows=(name:string,allowed:string[])=>{
  if(!Array.isArray(p[name])||p[name].length>10000)invalid();
  return (p[name] as unknown[]).map(raw=>{const r=obj(raw);keys(r,allowed);return r;});
 };
 const tasks=rows('tasks',['id','title','minutes','deadline','importance','done','created','note','chunkMinutes','splittable','category']);
 const ids=new Set<string>();
 for(const t of tasks){text(t.id,64);text(t.title,160);text(t.note,4096);text(t.category,24);if(!t.id||!t.title.trim()||ids.has(t.id))invalid();ids.add(t.id);num(t.minutes,0,525600);num(t.deadline);num(t.importance,1,5);bool(t.done);num(t.created);num(t.chunkMinutes,1,525600);bool(t.splittable);if(!['none','study','exercise','entertainment','work','social','travel','rest'].includes(t.category))invalid();}
 const blockIds=new Set<string>();
 for(const b of rows('blocks',['id','taskId','start','end','locked','done'])){text(b.id,64);text(b.taskId,64);if(!b.id||blockIds.has(b.id)||!ids.has(b.taskId))invalid();blockIds.add(b.id);num(b.start);num(b.end);if(b.end<=b.start)invalid();bool(b.locked);bool(b.done);}
 const checkIds=new Set<string>();
 for(const c of rows('checkins',['id','taskId','completedAt','rating'])){text(c.id,64);text(c.taskId,64);if(!c.id||checkIds.has(c.id))invalid();checkIds.add(c.id);num(c.completedAt);num(c.rating,1,5);}
 const dates=new Set<string>();
 for(const w of rows('work_times',['date','startMinute','endMinute'])){date(w.date);if(dates.has(w.date))invalid();dates.add(w.date);num(w.startMinute,0,1439);num(w.endMinute,1,1440);if(w.endMinute<=w.startMinute)invalid();}
 const settings=obj(p.settings);keys(settings,['startHour','endHour']);num(settings.startHour,0,23);num(settings.endHour,1,24);if(settings.endHour<=settings.startHour)invalid();
 const reminderIds=new Set<string>();
 for(const r of rows('reminders',['taskId','enabled','method','timing'])){text(r.taskId,64);if(!ids.has(r.taskId)||reminderIds.has(r.taskId))invalid();reminderIds.add(r.taskId);bool(r.enabled);if(!['message','alarm','both'].includes(r.method)||!['start','end','both'].includes(r.timing))invalid();}
 return structuredClone(p);
}
