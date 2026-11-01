import {normalizedAgenda} from './agenda.ts';
import {runtimeTime,localMillis} from './runtime-time.ts';
import type {EditChange} from './policy.ts';

function number(text:string):number {if(/^\d+$/u.test(text))return Number(text);const digits='零一二三四五六七八九';text=text.replaceAll('两','二').replaceAll('〇','零');if(text==='十')return 10;if(text.includes('十')) {const [a,b]=text.split('十');return (a?digits.indexOf(a):1)*10+(b?digits.indexOf(b):0);}return digits.indexOf(text);}
function validDate(y:number,m:number,d:number) {const date=`${y}-${String(m).padStart(2,'0')}-${String(d).padStart(2,'0')}`;return Number.isFinite(Date.parse(date+'T00:00:00Z'))&&new Date(date+'T00:00:00Z').toISOString().slice(0,10)===date?date:null;}

/** Expand a clearly requested new event. No IDs, permissions or mutations are inferred from model prose. */
export function requestedCreate(context:string,request:string,now=Date.now()):{changes:EditChange[]}|{error:string}|null {
 let text=request;
 try {const v=JSON.parse(request);if(typeof v?.feedback==='string')text=v.feedback;}catch{/* plain request */}
 const weekly=/每(?:周|星期|礼拜)([一二三四五六日天])/u.exec(text);
 const named=/一(?:节|门)\s*([^，。；\n]{1,50}?(?:课程|课))/u.exec(text)?.[1]??/(?:新增|添加|创建|安排|记录)(?:一(?:个|项|条|节))?\s*[“「"]([^”」"]{1,120})[”」"]/u.exec(text)?.[1]??/(?:新增|添加|创建)(?:一(?:个|项|条|节))?\s*([^，。；\n\s]{2,50})(?=[，。；\s]|$)/u.exec(text)?.[1];
 if(!named||(!weekly&&!/(?:新增|添加|创建)/u.test(text)))return null;
 if(/(?:不要|别|取消)(?:新增|添加|创建|安排)/u.test(text))return {error:'本次要求保持原日程不变'};
 const parsed=normalizedAgenda(context) as any,agenda=parsed?.agenda??parsed;
 if(!agenda||!Array.isArray(agenda.tasks)||!Array.isArray(agenda.blocks)||agenda.partial)return {error:'请开启日程读取并提供完整日程后再新增'};
 if(![3,4].includes(agenda.action_version))return {error:'请更新应用后使用新增课程或事项功能'};
 const runtime=runtimeTime(context,now),today=runtime.today;
 const normalized=text.replace(/[零〇一二两三四五六七八九十]{1,3}(?=[点时分])/gu,value=>String(number(value)));
 const clocks=[...normalized.matchAll(/(?<!\d)(?:(上午|下午|晚上|中午)\s*)?([01]?\d|2[0-3])(?::([0-5]\d)|[点时](?:(半)|([0-5]?\d)分?)?)(?![:\d])/gu)];
 if(clocks.length!==2)return {error:'请给出课程或新事项的开始和结束时间'};
 const clock=(m:RegExpMatchArray,inherited?:string)=>{let h=Number(m[2]);const period=m[1]??inherited;if(period&&h>12)return null;if(/下午|晚上|中午/u.test(period??'')&&h<12)h+=12;return `${String(h).padStart(2,'0')}:${(m[3]??(m[4]?'30':m[5]??'0')).padStart(2,'0')}:00`;};
 const startClock=clock(clocks[0]!),endClock=clock(clocks[1]!,clocks[0]![1]);
 if(!startClock||!endClock||endClock<=startClock)return {error:'请选择同一天内有效的起止时间'};
 const dates=[...text.matchAll(/(?:(\d{4})[-/年])?(\d{1,2})[-/月](\d{1,2})日?/gu)].map(m=>validDate(Number(m[1]??today.slice(0,4)),Number(m[2]),Number(m[3])));
 if(dates.some(d=>!d))return {error:'日期无效，请核对年月日'};
 const changes:EditChange[]=[];
 const add=(date:string)=>{const start=localMillis(date,startClock,runtime.timezone),end=localMillis(date,endClock,runtime.timezone);if(start===null||end===null)return false;if(start>now)changes.push({type:'create_task',target_id:`new:${changes.length+1}`,title:named.trim(),category:/课程|课|复习|阅读|学习/u.test(named)?'study':'none',start_ms:start,end_ms:end,fixed:!!weekly});return true;};
 if(weekly) {
  const until=dates.at(-1);if(!until)return {error:'请给出每周课程的结束日期'};
  const first=dates.length>1&&dates[0]!>today?dates[0]!:today;
  if(until<today||until>new Date(Date.parse(today+'T00:00:00Z')+90*86400000).toISOString().slice(0,10))return {error:'请选择未来90天内的课程结束日期'};
  const wanted=('一二三四五六日'.indexOf(weekly[1]==='天'?'日':weekly[1]!)+1)%7;
  for(let day=Date.parse(first+'T00:00:00Z');new Date(day).toISOString().slice(0,10)<=until;day+=86400000) {if(new Date(day).getUTCDay()===wanted&&!add(new Date(day).toISOString().slice(0,10)))return {error:'所选时刻存在时区歧义，请选择其他时刻'};if(changes.length>12)return {error:'一次可预览最多12次课程，请缩短日期范围'};}
 }else {
  const offset=/后天/u.test(text)?2:/明天|明日/u.test(text)?1:0;
  const date=dates[0]??new Date(Date.parse(today+'T00:00:00Z')+offset*86400000).toISOString().slice(0,10);
  if(!add(date))return {error:'所选时刻存在时区歧义，请选择其他时刻'};
 }
 return changes.length?{changes}:{error:'所选时段已过去，请选择未来的日期和时间'};
}
