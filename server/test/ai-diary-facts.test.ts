import {test} from 'node:test';
import assert from 'node:assert/strict';
import {unorderedDiaryText,diaryFacts} from '../src/ai/diary-facts.ts';
test('real model counterexample does not assert an unrecorded chronological order',()=>{
 assert.equal(unorderedDiaryText('今天完成阅读，用时30分钟；之后整理书桌，用时10分钟。','阅读，整理书桌'),'今天完成阅读，用时30分钟；另外整理书桌，用时10分钟。');
});

test('legacy done flags and estimates are not promoted to dated actual completion facts',()=>{
 const result=diaryFacts({tasks:[{id:'t',title:'阅读',done:true,estimated_minutes:30}],blocks:[]}) as {completed:unknown[];completion_date_known:boolean};
 assert.deepEqual(result.completed,[]);assert.equal(result.completion_date_known,false);
 const dated={date:'2030-01-01',completed:[{title:'阅读',completed_at:'2030-01-01T01:00:00Z'}],actual_duration_recorded:false};
 assert.deepEqual(diaryFacts(dated),dated);
});
test('source wording and quoted user titles remain untouched',()=>{
 const original='今天完成了“之后再阅读”。';
 assert.equal(unorderedDiaryText(original,'任务标题：之后再阅读'),original);
 assert.equal(unorderedDiaryText('先是阅读，然后整理。','我先是阅读，然后整理。'),'先是阅读，然后整理。');
});
