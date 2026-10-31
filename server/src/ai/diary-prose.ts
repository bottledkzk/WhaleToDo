export const DIARY_STYLES = ['literary','rigorous','concise','detailed'] as const;
export type DiaryStyle = typeof DIARY_STYLES[number];

export function diaryStylePrompt(style:DiaryStyle='concise'):string {
 const common='只输出JSON对象{"diary":"日记正文"}，最多800个Unicode字符。正文像用户自己写下的生活记录，以第一人称或自然省略主语表达；不要称呼用户、介绍助手、汇报任务或解释生成过程。不要标题、列表、表格、统计面板、注释、括号说明、保存建议或资料完整性说明。缺少用时、情绪、地点、页数等事实时直接不写，绝不写“未记录实际用时”“信息不足”“根据提供的记录”等说明。历史草稿不是事实来源。所有风格均保留事项名称的原词，不拆词改写；没有明确次数时连“一遍”也不能写。只按每条事实写一个简短分句，文学至多另加一句明确比喻，不追加总结性心情或整日评价。每个事实分句必须对应agenda.completed中的事项/完成时刻或用户本次明确提供的事实。done状态不能证明完成日期；未来计划不当作已完成。完成时刻不是开始时刻、持续时间、次数或延误证据：不得把15:00完成写成15:00开始练习，不得自行写“一遍”“才弄完”。预计工时不是实际用时。只写指定日期的记录，没有当地时刻不推测早晚或顺序。不得补造心情、场景、天气、先前牵挂、剩余时间的活动、一天的节奏和对全部计划的评价，尤其不写“一直搁在心上”“剩下的时间过得安静”“节奏不快”“没有落下什么”。风格只改变表达，不增加事实。事实很少时所有风格都应简短，不凑段落和字数；无可写事实时diary为空。';
 const styles:Record<DiaryStyle,string>={
  literary:'文学：用克制、有节奏的语言，可用“像”明确标识的比喻修饰已有事实，不能借比喻补造心境和经历。例：事实只有15:23完成整理笔记，可写“15:23，笔记整理完了，像在今天的纸页上落下一笔。”不要追加余下时间或内心独白。',
  rigorous:'严谨：以平实准确的连贯文字记录事实，有明确当地完成时刻时保留HH:mm并用“完成/读完/整理好”等完成动词；无时刻不编顺序。不写字段、清单、结项报告、“完成时间是”、笼统的“多”或带延误意味的“才”。',
  concise:'精简：只保留最有意义的已知记录，通常一至三句，清楚自然，不补解释、不重复事项。保留完整事项名称，例如“补测记录”应写“完成了补测记录”，不要改写成“补测了记录”。',
  detailed:'详细：保留每一条已有事项与明确完成时刻，事实充足时按有依据的当地时间分段；过程、选择、结果只有明确记录才写。详细不等于更长，没有记录的过程、次数与感受不写，尤其不能把“复习算法笔记”写成“复习了一遍算法笔记”；没有次数就只写“复习完算法笔记”。稀疏记录仍可只有一句。'
 };
 return common+styles[style];
}

/** A narrow final guard for observed embellishments; not a semantic truth verifier. */
export function groundedDiaryProse(text:string,agenda:unknown,request:string):string {
 const facts=agenda&&typeof agenda==='object'?agenda as Record<string,unknown>:{};
 const completed=Array.isArray(facts.completed)?facts.completed:[];
 let prose=text;
 // A title is data, even when it contains one of the phrases checked below.
 for(const item of completed){
  if(item&&typeof item==='object'&&typeof item.title==='string'&&item.title)prose=prose.split(item.title).join('【事项】');
 }
 const unsupported=[
  /一直.{0,8}(?:心上|惦记|牵挂)/gu,
  /(?:剩下|余下|其余|接下来)的?(?:时间|一天|半天).{0,20}/gu,
  /(?:节奏|步调)(?:不快|很慢|很快|从容|平稳)|没有落下什么|一切.{0,4}(?:顺利|按计划)|都按部就班/gu,
  /(?:心情|心里|觉得|感到|感觉).{0,12}(?:轻松|平静|踏实|开心|满足|充实|安静)|松了(?:一口气|口气)/gu,
  /才(?:弄完|做完|完成|整理完)/gu,
  /[一二两三四五六七八九十几\d]+遍/gu
 ];
 // Only an explicit matching user fact can supply these feelings/counts; history cannot.
 for(const pattern of unsupported)for(const match of prose.matchAll(pattern)){
  if(!request.includes(match[0]))throw Error('Unsupported diary embellishment');
 }
 return text;
}

/** Remove only stand-alone generation/data-availability notes, never rewrite facts. */
function isNote(sentence:string):boolean {
 const s=sentence.trim().replace(/^[（(]|[）)]$/gu,'');
 return /^(?:注|备注|说明|生成说明|保存提示|温馨提示)[：:]/u.test(s)||
  /^(?:这项任务|该任务|这件事|这份记录|本次记录|记录中|资料中|现有资料|提供的信息|这一天的记录)?\s*(?:没有|未|尚未)(?:记录|提供|注明|说明).{0,24}(?:实际(?:用时|时长|耗时)|心情|感受|地点|天气|页数)/u.test(s)||
  /^(?:实际(?:用时|时长|耗时)|心情|感受|地点|天气|页数).{0,8}(?:未记录|未提供|未知|不详)/u.test(s)||
  /^(?:记录|资料|信息|数据)(?:不足|缺失|不完整|有限)/u.test(s)||
  /^(?:根据|基于)(?:你|您)?(?:提供|给出|现有|所附)的?(?:记录|信息|资料|数据)/u.test(s)||
  /^(?:以上(?:内容|日记|记录)|以下(?:是|为)|这篇日记|本篇日记)/u.test(s)||
  /^(?:请|你可以|您可以|可在预览后).{0,12}(?:补充|保存|编辑|确认).{0,12}(?:日记|信息|内容|记录)/u.test(s);
}

export function diaryProse(text:string):string {
 if(/(^|\n)\s*(?:#{1,6}\s|[-*]\s|\d+[.、]\s|(?:任务汇报|任务报告|任务统计|完成统计|预计工时|实际耗时)[：:])/u.test(text))throw Error('Diary must be prose');
 const withoutNotes=text.replace(/[（(]([^（）()]*)[）)]/gu,(whole,inside:string)=>isNote(inside)?'':whole);
 return withoutNotes.split(/\n+/u).filter(line=>!/^\s*\d{4}[-/]\d{2}[-/]\d{2}\s*$/u.test(line))
  .map(line=>(line.match(/[^。！？!?]+[。！？!?]?/gu)??[]).filter(s=>!isNote(s)).join('').trim())
  .filter(Boolean).join('\n\n').trim();
}
