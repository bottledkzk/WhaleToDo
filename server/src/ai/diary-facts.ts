/** Do not invent an order for an unordered completion list. Never modify quoted source titles. */
export function unorderedDiaryText(text:string,source:string):string {
 let result=text;
 for(const word of ['先是','之后','随后','然后','接着']){
  if(source.includes(word))continue;
  const pattern=new RegExp('(^|[。！？；，\\n]|今天)\\s*'+word,'gu');
  result=result.replace(pattern,(_all,prefix:string)=>prefix+(word==='先是'?'':'另外'));
 }
 return result;
}

/** Older clients only sent task estimates. Keep them out of a first-person completed-facts draft. */
export function diaryFacts(agenda:unknown):unknown {
 if(!agenda||typeof agenda!=='object')return agenda;
 const value=agenda as Record<string,unknown>;
 if(Array.isArray(value.completed)){
  const facts={...value};delete facts.completion_date_known;delete facts.actual_duration_recorded;delete facts.notice;
  return facts;
 }
 const source=(value.agenda&&typeof value.agenda==='object'?value.agenda:value) as Record<string,unknown>;
 if(Array.isArray(source.tasks))return {completed:[]};
 return agenda;
}
