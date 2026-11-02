import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync,writeFileSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {loadAiConfig} from '../src/ai/config.ts';

test('capacity defaults match model ceiling and ten million daily tokens; unsupported settings fail early',()=>{
 const dir=mkdtempSync(join(tmpdir(),'ai-capacity-'));
 try{
  writeFileSync(join(dir,'database.url'),'postgresql://placeholder:placeholder@127.0.0.1/whaletodo');
  writeFileSync(join(dir,'deepseek.key'),'sk-synthetic-placeholder');
  const env={CREDENTIALS_DIRECTORY:dir};const config=loadAiConfig(env);
  assert.equal(config.maxOutput,393216);assert.equal(config.globalDailyTokens,10000000);
  for(const values of [{AI_MAX_OUTPUT:'1000000'},{AI_GLOBAL_DAILY_TOKENS:'10000001'},{AI_MAX_OUTPUT:'NaN'},{AI_GLOBAL_DAILY_TOKENS:'0'}])assert.throws(()=>loadAiConfig({...env,...values}));
 }finally{rmSync(dir,{recursive:true,force:true});}
});
