import {test} from 'node:test';
import assert from 'node:assert/strict';
import {prepareInput} from '../src/ai/provider.ts';
test('clipping a single long history message still reports compaction',()=>{
 const source={client_request_id:'synthetic',mode:'chat' as const,model_code:'deepseek-default' as const,prompt:'下一步',history:[{role:'user' as const,content:'合成历史句子。'.repeat(300)}]};
 const result=prepareInput(source);
 assert.equal(result.compacted,true);assert.ok(Buffer.byteLength(result.history![0]!.content)<=1500);
 assert.equal(source.history[0]!.content,'合成历史句子。'.repeat(300));
});
