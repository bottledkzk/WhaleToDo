export const DIARY_STYLES = ['literary','rigorous','concise','detailed'] as const;
export type DiaryStyle = typeof DIARY_STYLES[number];

export function diaryStylePrompt(style:DiaryStyle='concise'):string {
 const common='只输出JSON对象{"diary":"日记正文"}，最多800个Unicode字符。正文像用户自己写下的生活记录，以第一人称或自然省略主语表达；不要称呼用户、介绍助手、汇报任务或解释生成过程。不要标题、列表、表格、统计面板、注释、括号说明、保存建议或资料完整性说明。缺少用时、情绪、地点、页数等事实时直接不写，绝不写“未记录实际用时”“信息不足”“根据提供的记录”等说明。历史草稿不是事实来源。严格区分已完成事实与未来计划，预计工时不是实际用时；仅用指定日期的真实打卡和用户明确提供的事实，不补造心情、场景、天气或先后顺序。事实很少时所有风格都可以简短，不为字数凑内容；无可写事实时diary为空。';
 const styles:Record<DiaryStyle,string>={
  literary:'文学：用克制、有节奏的语言记录，可用明确的比喻修饰已有事实；不要把修辞写成未发生的经历，不擅自添加心境或感官细节。通常一至两段。',
  rigorous:'严谨：以平实准确的文字记录事实；有明确时刻时可自然带出，无时刻不编顺序。写成连贯短段落，不写字段、清单、结项报告或“完成时间是”等汇报腔。',
  concise:'精简：只保留最有意义的已知记录，通常一至三句，清楚自然，不补解释、不重复事项。',
  detailed:'详细：事实充足时分两至四个自然段展开已有细节，保留明确的过程、选择和结果；没有记录的过程与感受不写，不把简短事实扩写成虚构故事。'
 };
 return common+styles[style];
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
