import {test} from 'node:test';
import assert from 'node:assert/strict';
import {truthfulShareCopies} from '../src/ai/share-facts.ts';
test('unfinished plans cannot become fabricated completed effort or feelings in share copy',()=>{
 const text=truthfulShareCopies(['专注已经积成了底气','今天完成了报告','计划在前面，慢慢走稳每一步'],'○ 报告\n截止 明天20:00');
 assert.ok(!text.join('').includes('底气'));assert.ok(!text.join('').includes('完成了'));
 assert.equal(text[2],'计划在前面，慢慢走稳每一步');assert.equal(new Set(text).size,3);
 assert.ok(text.every(s=>[...s].length<=50));
});
test('task estimates cannot be rewritten as recorded actual duration; valid factual copy is kept',()=>{
 const text=truthfulShareCopies(['坚持了两小时，收获满满','今天完成了报告','明天继续把下一步做好'],'✓ 报告\n预计120分钟');
 assert.ok(!text[0]!.includes('两小时'));assert.equal(text[1],'今天完成了报告');
});
test('unfinished task copy cannot claim personal progress through indirect past-tense praise',()=>{
 const text=truthfulShareCopies(['答辩截止就在今晚，你已一步步走到这里，很不容易。','终于走到答辩这一步，辛苦了','接下来逐项准备答辩'],'○ 演示·项目答辩\n截止 10/31 20:00');
 assert.ok(!text.join('').includes('已一步步'));assert.ok(!text.join('').includes('辛苦了'));
 assert.equal(text[2],'接下来逐项准备答辩');assert.equal(new Set(text).size,3);
});
test('a completed fact cannot authorize completion claims about a different unfinished task',()=>{
 const copies=truthfulShareCopies(['今天完成了报告','今天完成了会议','已经收获满满'],'✓ 会议\n○ 报告');
 assert.ok(!copies[0]!.includes('完成了报告'));assert.equal(copies[1],'今天完成了会议');
 assert.ok(!copies[2]!.includes('已经'));assert.equal(new Set(copies).size,3);
});
