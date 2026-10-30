import {test} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {buildApp} from '../src/http/app.ts';
import {MemoryRepository} from '../src/repo/memory.ts';
import {fromSync} from '../src/repo/from-sync.ts';
import {loadConfig} from '../src/config.ts';
import {validateCloudPayload} from '../src/domain/account-cloud.ts';

const schedule={schema_version:1,tasks:[{id:'t',title:'阅读',minutes:30,deadline:0,importance:3,done:false,created:1,note:'',chunkMinutes:30,splittable:true,category:'study'}],blocks:[],checkins:[],work_times:[],settings:{startHour:9,endHour:21},reminders:[]};
test('manual schedule backups require VIP, profile does not; no cross-account document leak',async()=>{
 const repo=new MemoryRepository();const a=await repo.createUser('cloud_a','Synthetic-pass-123'),b=await repo.createUser('cloud_b','Synthetic-pass-123');
 const tokenA=repo.createSession(a.id).tokens.accessToken,tokenB=repo.createSession(b.id).tokens.accessToken;
 const app=buildApp({repo:fromSync(repo),config:loadConfig({NODE_ENV:'test'})});
 const headers={authorization:'Bearer '+tokenA};
 try{
  assert.equal((await app.inject({url:'/api/v1/account/cloud/schedule'})).statusCode,401);
  for(const url of ['/api/v1/account/cloud/schedule','/api/v1/sync/pull'])assert.equal((await app.inject({url,headers})).statusCode,403);
  assert.equal((await app.inject({url:'/api/v1/sync/push',method:'POST',headers,payload:{}})).statusCode,403);
  assert.equal((await app.inject({url:'/api/v1/account/cloud/schedule',method:'POST',headers,payload:{revision:0,payload:schedule}})).statusCode,403);
  const personal=await app.inject({url:'/api/v1/account/cloud/profile',method:'POST',headers,payload:{revision:0,payload:{entries:{'profile.motto':'Whale to do','diary:2030-01-01':'合成日记'}}}});assert.equal(personal.statusCode,200,personal.body);
  assert.equal((await app.inject({url:'/api/v1/account/cloud/profile',headers:{authorization:'Bearer '+tokenB}})).json().data.revision,0);
  repo.store.entitlements.push({id:randomUUID(),userId:a.id,kind:'vip',expiresAt:Date.now()+60000,sourceOrderId:null,createdAt:Date.now()});
  const write=await app.inject({url:'/api/v1/account/cloud/schedule',method:'POST',headers,payload:{revision:0,payload:schedule}});assert.equal(write.statusCode,200,write.body);assert.equal(write.json().data.revision,1);
  const repeat=await app.inject({url:'/api/v1/account/cloud/schedule',method:'POST',headers,payload:{revision:0,payload:schedule}});assert.equal(repeat.statusCode,200);assert.equal(repeat.json().data.revision,1);
  const changed={...schedule,tasks:[{...schedule.tasks[0],title:'新版'}]};
  assert.equal((await app.inject({url:'/api/v1/account/cloud/schedule',method:'POST',headers,payload:{revision:0,payload:changed}})).statusCode,409);
  repo.store.entitlements.length=0;assert.equal((await app.inject({url:'/api/v1/account/cloud/schedule',headers})).statusCode,403);
 }finally{await app.close()}
});
test('schedule allowlist excludes private content, unknown fields and broken references',()=>{
 assert.deepEqual(validateCloudPayload('schedule',schedule),schedule);
 for(const extra of ['diaries','agent_sessions','token','owner_id'])assert.throws(()=>validateCloudPayload('schedule',{...schedule,[extra]:[]}));
 assert.throws(()=>validateCloudPayload('schedule',{...schedule,tasks:[schedule.tasks[0],schedule.tasks[0]]}));
 assert.throws(()=>validateCloudPayload('schedule',{...schedule,blocks:[{id:'b',taskId:'missing',start:1,end:2,locked:false,done:false}]}));
 assert.throws(()=>validateCloudPayload('profile',{entries:{'diary:2030-02-30':'无效日期'}}));
 assert.throws(()=>validateCloudPayload('profile',{entries:{'agent:chat':'不能上传'}}));
});
test('concurrent stale backup writes cannot both win',async()=>{
 const r=new MemoryRepository();const u=await r.createUser('cloud_c','Synthetic-pass-123');
 const outcomes=await Promise.allSettled(['one','two'].map(async motto=>r.cloudWrite(u.id,'profile',0,{entries:{'profile.motto':motto}})));
 assert.equal(outcomes.filter(x=>x.status==='fulfilled').length,1);assert.equal(r.cloudRead(u.id,'profile').revision,1);
});
