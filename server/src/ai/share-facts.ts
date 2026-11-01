import {runtimeTime} from './runtime-time.ts';

/** ShareStudio supplies its selected zone in the source even when context is empty. */
export function shareRuntimeTime(source:string,context='',now=Date.now()) {
 const zone=/^([A-Za-z_+-]+(?:\/[A-Za-z0-9_+\/-]+)+)\s*·\s*来自 WhaleToDo\s*$/mu.exec(source)?.[1];
 return runtimeTime(context.trim()||JSON.stringify({timezone:zone??'UTC'}),now);
}

/** Planned work is not evidence of past effort or feelings. Repair only unsupported claims. */
export function truthfulShareCopies(copies:string[],source:string,time?:{today:string;yesterday:string;tomorrow:string}):string[] {
 const facts=[...source.matchAll(/([○✓✔])\s*([^\n\r]+)/gu)];
 const unfinished=facts.filter(f=>f[1]==='○').map(f=>f[2]!.trim());
 const completed=facts.filter(f=>f[1]!=='○').map(f=>f[2]!.trim());
 const mentions=(copy:string,title:string)=>{
  const short=title.split('·').at(-1)!.trim();
  return title.length>0&&(copy.includes(title)||title.includes('·')&&short.length>=2&&copy.includes(short));
 };
 const unsupportedPast=/(?:已|终于|不容易|辛苦了|完成了|做完了|收获了|底气|专注了|坚持了|积累了|进步了)/u;
 const actualDuration=/(?:用了|花了|耗时|专注了|坚持了)\s*[一二两三四五六七八九十百\d]+\s*(?:分钟|小时)/u;
 const neutral=['先把计划记下来，再把下一步慢慢做好','给重要的事留出时间，也给自己留一点从容','从一件小事开始，按自己的节奏向前'];
 const dates=new Set([...source.matchAll(/^(?:(\d{4})[-/])?(\d{1,2})[/-](\d{1,2})\s+\d{1,2}:\d{2}/gmu)].map(m=>`${m[1]??time?.today.slice(0,4)}-${m[2]!.padStart(2,'0')}-${m[3]!.padStart(2,'0')}`));
 const dated=dates.size===1?[...dates][0]:null;
 const relative:[RegExp,string|undefined][]=[[/今天|今日|今晚|今夜/u,time?.today],[/明天|明日|明晚/u,time?.tomorrow],[/昨天|昨日|昨晚/u,time?.yesterday]];
 const result:string[]=[];
 for(const [index,copy] of copies.entries()){
  const unsupportedClaim=unfinished.length>0&&unsupportedPast.test(copy)&&
   (unfinished.some(title=>mentions(copy,title))||!completed.some(title=>mentions(copy,title)));
  const wrongDay=!!time&&!!dated&&relative.some(([words,date])=>words.test(copy)&&date!==dated);
  let text=actualDuration.test(copy)||unsupportedClaim||wrongDay?neutral[index%neutral.length]!:copy;
  if(result.includes(text))text=neutral.find(s=>!result.includes(s))!;
  result.push(text);
 }
 return result;
}
