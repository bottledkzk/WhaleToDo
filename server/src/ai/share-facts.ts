/** Planned work is not evidence of past effort or feelings. Repair only unsupported claims. */
export function truthfulShareCopies(copies:string[],source:string):string[] {
 const planOnly=/○/u.test(source)&&!/[✓✔]/u.test(source);
 const unsupportedPast=/(?:已|终于|不容易|辛苦了|完成了|做完了|收获了|底气|专注了|坚持了|积累了|进步了)/u;
 const actualDuration=/(?:用了|花了|耗时|专注了|坚持了)\s*[一二两三四五六七八九十百\d]+\s*(?:分钟|小时)/u;
 const neutral=['先把计划记下来，再把下一步慢慢做好','给重要的事留出时间，也给自己留一点从容','从一件小事开始，按自己的节奏向前'];
 const result:string[]=[];
 for(const [index,copy] of copies.entries()){
  let text=actualDuration.test(copy)||planOnly&&unsupportedPast.test(copy)?neutral[index%neutral.length]!:copy;
  if(result.includes(text))text=neutral.find(s=>!result.includes(s))!;
  result.push(text);
 }
 return result;
}
