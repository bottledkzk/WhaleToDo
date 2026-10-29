import { test,before,after,beforeEach } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID,randomBytes,createHash } from 'node:crypto';
import { Pool } from 'pg';
import { AiStore } from '../src/ai/store.ts';
import { buildAiApp } from '../src/ai/app.ts';
import { ProviderFailure,type AiInput } from '../src/ai/provider.ts';
import { tokenReservation } from '../src/ai/provider.ts';
import { hashPassword } from '../src/lib/security.ts';
import type { AiConfig } from '../src/ai/config.ts';
const url=process.env.VERIFY_DATABASE_URL!;
if(!url||new URL(url).pathname!=='/whaletodo_verify')throw new Error('Integration tests require isolated whaletodo_verify database');
const owner=new Pool({connectionString:url,max:2});
const appDb=new Pool({connectionString:process.env.VERIFY_APP_URL!,max:2});
const store=new AiStore(process.env.VERIFY_AI_URL!);
const users:string[]=[];const tokens:string[]=[];
const hash=hashPassword('Synthetic-integration-password');
const config={model:'deepseek-flash',maxOutput:2048,timeoutMs:1000,userDailyCalls:100,globalDailyCalls:1000,
 userDailyTokens:500000,globalDailyTokens:2000000,businessTimezone:'Asia/Shanghai',tokensPerPoint:10000,editEnabled:true} as AiConfig;
function input():AiInput{return {client_request_id:randomUUID(),mode:'chat',prompt:'合成验证，只需简短学习建议',context:'',model_code:'deepseek-default'};}
before(async()=>{await owner.query('SELECT 1');});
beforeEach(async()=>{
 await owner.query('TRUNCATE users CASCADE');users.length=0;tokens.length=0;
 for(let i=0;i<10;i++){
 const id=randomUUID(),token=randomBytes(32).toString('base64url');users.push(id);tokens.push(token);
 await owner.query('INSERT INTO users(id,username,password_hash,display_name) VALUES($1,$2,$3,$4)',[id,`verify_${i}`,hash,`Synthetic ${i}`]);
 await owner.query(`INSERT INTO auth_sessions(id,user_id,access_hash,refresh_hash,family_id,access_expires_at,refresh_expires_at)
 VALUES($1,$2,$3,$4,$5,now()+interval '15 minutes',now()+interval '30 days')`,[randomUUID(),id,createHash('sha256').update(token).digest('hex'),createHash('sha256').update(randomUUID()).digest('hex'),randomUUID()]);
 await owner.query('INSERT INTO point_wallets(user_id,available) VALUES($1,5)',[id]);}
});
after(async()=>{await owner.query('TRUNCATE users CASCADE');await Promise.all([store.close(),owner.end(),appDb.end()]);});
test('ten demo accounts fit and eleventh is rejected by database capacity',async()=>{
 const q=await owner.query('SELECT count(*)::int AS n,count(distinct demo_slot)::int AS slots FROM users');assert.equal(q.rows[0].n,10);assert.equal(q.rows[0].slots,10);
 await assert.rejects(owner.query('INSERT INTO users(id,username,password_hash,display_name) VALUES($1,$2,$3,$4)',[randomUUID(),'eleventh',hash,'Extra']));
});
test('runtime roles have no DDL or privilege escalation; AI cannot read password hashes',async()=>{
 await assert.rejects(appDb.query('CREATE TABLE forbidden(id int)'));
 await assert.rejects(appDb.query("UPDATE users SET role='admin' WHERE id=$1",[users[0]]));
 await assert.rejects(store.pool.query('SELECT password_hash FROM users'));
 await assert.rejects(store.pool.query('SELECT refresh_hash FROM auth_sessions'));
});
test('ten concurrent logical sessions authenticate through two pooled DB connections',async()=>{
 const ids=await Promise.all(tokens.map(t=>store.authenticate(t)));assert.deepEqual(ids,users);
});
test('settings survive connection roundtrip and task ownership uses RLS',async()=>{
 const c=await appDb.connect();try{
 await c.query('BEGIN');await c.query("SELECT set_config('app.user_id',$1,true)",[users[0]]);
 await c.query('INSERT INTO user_settings(user_id,settings) VALUES($1,$2)',[users[0],{theme:'dark',default_work_start:'08:00'}]);await c.query('COMMIT');
 await c.query('BEGIN');await c.query("SELECT set_config('app.user_id',$1,true)",[users[1]]);
 assert.equal((await c.query('SELECT * FROM user_settings')).rowCount,0);await c.query('ROLLBACK');
 await c.query('BEGIN');await c.query("SELECT set_config('app.user_id',$1,true)",[users[0]]);
 assert.equal((await c.query('SELECT settings FROM user_settings')).rows[0].settings.theme,'dark');await c.query('ROLLBACK');
 }finally{c.release();}
});
test('cross-account task reference and nonpositive plan duration are rejected',async()=>{
 const task=randomUUID();await owner.query('INSERT INTO tasks(id,user_id,title) VALUES($1,$2,$3)',[task,users[0],'Synthetic task']);
 await assert.rejects(owner.query('INSERT INTO plan_blocks(id,user_id,task_id,starts_at,ends_at) VALUES($1,$2,$3,now(),now()+interval \'1 hour\')',[randomUUID(),users[1],task]));
 await assert.rejects(owner.query('INSERT INTO plan_blocks(id,user_id,task_id,starts_at,ends_at) VALUES($1,$2,$3,now(),now())',[randomUUID(),users[0],task]));
});
test('concurrent reservations never overspend; unknown outcome stays reserved',async()=>{
 await owner.query('UPDATE point_wallets SET available=1 WHERE user_id=$1',[users[0]]);
 const r=await Promise.allSettled(Array.from({length:8},()=>store.reserve(users[0]!,input(),config)));
 const successes=r.filter(x=>x.status==='fulfilled');assert.equal(successes.length,1);
 const admitted=(successes[0] as PromiseFulfilledResult<Awaited<ReturnType<AiStore['reserve']>>>).value;
 await store.finish(admitted.row.id,'unknown');const w=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];
 assert.deepEqual(w,{available:0,reserved:1});
});
test('same request is replayed without another model call, changed body conflicts; other account cannot read it',async()=>{
 let calls=0;const app=buildAiApp(config,store,async()=>{calls++;return {text:'建议先做一件小事',tokens:20};});
 const payload=input();const req={method:'POST' as const,url:'/api/v1/ai/requests',headers:{authorization:`Bearer ${tokens[0]}`},payload};
 assert.equal((await app.inject(req)).statusCode,200);assert.equal((await app.inject(req)).statusCode,200);assert.equal(calls,1);
 assert.equal((await app.inject({...req,payload:{...payload,prompt:'不同请求'}})).statusCode,409);
 assert.equal((await app.inject({method:'GET',url:`/api/v1/ai/requests/${payload.client_request_id}`,headers:{authorization:`Bearer ${tokens[1]}`}})).statusCode,403);
 const w=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];assert.deepEqual(w,{available:4,reserved:0});await app.close();
});
test('definite rejection releases once; unknown result is queryable and never resubmitted',async()=>{
 for(const definite of [true,false]){
 let calls=0;const app=buildAiApp(config,store,async()=>{calls++;throw new ProviderFailure(definite);});
 const payload=input();const req={method:'POST' as const,url:'/api/v1/ai/requests',headers:{authorization:`Bearer ${tokens[0]}`},payload};
 const first=await app.inject(req);assert.equal(first.statusCode,definite?503:202);await app.inject(req);assert.equal(calls,1);await app.close();}
 const w=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];assert.deepEqual(w,{available:4,reserved:1});
});
test('anonymous/expired/disabled accounts cannot invoke models; client cannot inject provider parameters',async()=>{
 let calls=0;const app=buildAiApp(config,store,async()=>{calls++;return {text:'x',tokens:1};});
 const payload=input();assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',payload})).statusCode,401);
 await owner.query("UPDATE auth_sessions SET access_expires_at=now()-interval '1 minute' WHERE user_id=$1",[users[0]]);
 assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',payload,headers:{authorization:`Bearer ${tokens[0]}`}})).statusCode,401);
 assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',payload:{...payload,provider_url:'http://untrusted.local'},headers:{authorization:`Bearer ${tokens[2]}`}})).statusCode,400);
 await owner.query("UPDATE users SET status='disabled' WHERE id=$1",[users[1]]);
 assert.equal((await app.inject({method:'POST',url:'/api/v1/ai/requests',payload,headers:{authorization:`Bearer ${tokens[1]}`}})).statusCode,401);
 assert.equal(calls,0);await app.close();
});
test('daily allowance is enforced atomically and success cannot settle twice',async()=>{
 const row=(await store.reserve(users[0]!,input(),{...config,userDailyCalls:1})).row;
 await store.finish(row.id,'succeeded',{text:'Synthetic',tokens:10});await store.finish(row.id,'failed');
 await assert.rejects(store.reserve(users[0]!,input(),{...config,userDailyCalls:1}),/限制/);
 const w=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];assert.deepEqual(w,{available:4,reserved:0});
});

test('missing signup points fails closed; budgets and restart uncertainty remain conservative',async()=>{
 await owner.query('UPDATE point_wallets SET available=0 WHERE user_id=$1',[users[0]]);
 await assert.rejects(store.reserve(users[0]!,input(),config),/签到/);
 await assert.rejects(store.reserve(users[1]!,input(),{...config,userDailyTokens:1}),/限制/);
 const row=(await store.reserve(users[1]!,input(),config)).row;await store.running(row.id);await store.recover();
 assert.equal((await store.get(users[1]!,row.client_request_id))?.status,'unknown');
 const w=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[1]])).rows[0];assert.deepEqual(w,{available:4,reserved:1});
});

test('Token settlement refunds reserved headroom and bills actual k tokens once',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 const row=(await store.reserve(users[0]!,input(),{...config,tokensPerPoint:1000})).row;
 assert.ok(row.points>=2);await store.finish(row.id,'succeeded',{text:'合成答复',tokens:1200,inputTokens:900,outputTokens:300});
 await store.finish(row.id,'succeeded',{text:'重复',tokens:1200});
 const wallet=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];assert.deepEqual(wallet,{available:18,reserved:0});
 const usage=await store.usage(users[0]!,config.businessTimezone);assert.equal(usage.total_tokens,1200);assert.equal(usage.charged_points,2);assert.equal(usage.requests,1);
});

test('cumulative billing combines half-point tasks without repeating a minimum fee',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 const policy={...config,tokensPerPoint:1400,billingStrategy:'cumulative_tokens' as const};
 const first=(await store.reserve(users[0]!,input(),policy)).row;
 const a=await store.finish(first.id,'succeeded',{text:'合成一',tokens:700});
 const second=(await store.reserve(users[0]!,input(),policy)).row;
 const b=await store.finish(second.id,'succeeded',{text:'合成二',tokens:700});
 assert.equal(a.charged_points,1);assert.equal(b.charged_points,0);
 await store.finish(second.id,'succeeded',{text:'重复回执',tokens:700});
 const wallet=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];
 assert.deepEqual(wallet,{available:19,reserved:0});
 assert.equal((await store.usage(users[0]!,config.businessTimezone)).charged_points,1);
 assert.equal((await owner.query("SELECT count(*)::int AS n FROM point_ledger WHERE user_id=$1 AND type='settle'",[users[0]])).rows[0].n,2);
 const another=(await store.reserve(users[1]!,input(),policy)).row;
 assert.equal((await store.finish(another.id,'succeeded',{text:'另一合成账户',tokens:700})).charged_points,1);
});

test('unusable completed output refunds points but keeps actual provider usage and idempotency',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 let calls=0;const policy={...config,billingStrategy:'cumulative_tokens' as const};
 const app=buildAiApp(policy,store,async()=>{calls++;return {text:'提案格式不完整，请补充要求后重试',tokens:350,inputTokens:300,outputTokens:50,changes:[],usable:false};});
 try{
  const body=input(),request={method:'POST' as const,url:'/api/v1/ai/requests',headers:{authorization:`Bearer ${tokens[0]}`},payload:body};
  const first=await app.inject(request);assert.equal(first.statusCode,503);assert.equal(first.json().data.status,'failed');
  assert.equal(first.json().data.points_charged,0);assert.equal(first.json().data.error_code,'MODEL_OUTPUT_INVALID');
  const replay=await app.inject(request);assert.equal(replay.statusCode,200);assert.equal(replay.json().data.status,'failed');assert.equal(calls,1);
  assert.deepEqual((await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0],{available:20,reserved:0});
  const usage=await store.usage(users[0]!,config.businessTimezone);assert.equal(usage.total_tokens,350);assert.equal(usage.charged_points,0);
  const next=input();await assert.rejects(store.reserve(users[0]!,next,{...policy,userDailyTokens:tokenReservation(next,policy.maxOutput)+349}),/限制/);
 }finally{await app.close();}
});

test('concurrent cumulative finishes serialize the wallet and preserve one total charge',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 const policy={...config,tokensPerPoint:1400,billingStrategy:'cumulative_tokens' as const};
 const a=(await store.reserve(users[0]!,input(),policy)).row,b=(await store.reserve(users[0]!,input(),policy)).row;
 const results=await Promise.all([store.finish(a.id,'succeeded',{text:'合成一',tokens:700}),store.finish(b.id,'succeeded',{text:'合成二',tokens:700})]);
 assert.equal(results.reduce((n,r)=>n+r.charged_points!,0),1);
 const wallet=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];
 assert.deepEqual(wallet,{available:19,reserved:0});
 await assert.rejects(store.pool.query('SELECT reason FROM point_ledger'));
});

test('legacy billing and a frozen rate bucket are not silently repriced',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 for(const [strategy,rate] of [['per_request',1400],['cumulative_tokens',1400],['cumulative_tokens',2000]] as const){
  const row=(await store.reserve(users[0]!,input(),{...config,tokensPerPoint:rate,billingStrategy:strategy})).row;
  const settled=await store.finish(row.id,'succeeded',{text:'合成',tokens:700});
  assert.equal(settled.charged_points,1);assert.equal(settled.billing_strategy,strategy);assert.equal(settled.tokens_per_point,rate);
 }
 assert.equal((await owner.query('SELECT available FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0].available,17);
});

test('failed and unknown calls do not consume a successful cumulative remainder',async()=>{
 await owner.query('UPDATE point_wallets SET available=20 WHERE user_id=$1',[users[0]]);
 const policy={...config,tokensPerPoint:1400,billingStrategy:'cumulative_tokens' as const};
 const failed=(await store.reserve(users[0]!,input(),policy)).row;await store.finish(failed.id,'failed');
 const good=(await store.reserve(users[0]!,input(),policy)).row;assert.equal((await store.finish(good.id,'succeeded',{text:'合成',tokens:700})).charged_points,1);
 const pending=(await store.reserve(users[0]!,input(),policy)).row;const unknown=await store.finish(pending.id,'unknown');
 assert.equal(unknown.charged_points,null);
 const wallet=(await owner.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1',[users[0]])).rows[0];
 assert.deepEqual(wallet,{available:19-pending.points,reserved:pending.points});
 assert.equal((await store.usage(users[0]!,config.businessTimezone)).charged_points,1);
});
test('editing requires VIP, allowlisted model and per-request user consent; it only returns a proposal',async()=>{
 let calls=0;const task=randomUUID();await owner.query('INSERT INTO tasks(id,user_id,title) VALUES($1,$2,$3)',[task,users[0],'原始事项']);
 const app=buildAiApp({...config,editEnabled:true},store,async()=>{calls++;return {text:'建议修改标题',tokens:30,changes:[{type:'rename_task',target_id:task,title:'建议事项'}]};});
 const body={...input(),mode:'edit',model_code:'deepseek-planner',edit_consent:true,context:`task id=${task} 原始事项`};
 const req={method:'POST' as const,url:'/api/v1/ai/requests',headers:{authorization:`Bearer ${tokens[0]}`},payload:body};
 assert.equal((await app.inject(req)).statusCode,403);
 await owner.query("INSERT INTO entitlements(id,user_id,kind,expires_at) VALUES($1,$2,'vip',now()+interval '1 day')",[randomUUID(),users[0]]);
 assert.equal((await app.inject({...req,payload:{...body,model_code:'deepseek-default'}})).statusCode,403);
 assert.equal((await app.inject({...req,payload:{...body,edit_consent:false}})).statusCode,403);
 const answer=await app.inject(req);assert.equal(answer.statusCode,200);assert.equal(answer.json().data.proposal.changes[0].type,'rename_task');assert.equal(calls,1);
 assert.equal((await owner.query('SELECT title FROM tasks WHERE id=$1',[task])).rows[0].title,'原始事项');await app.close();
});
test('usage endpoint is owner-scoped and unauthorized request cannot read counts',async()=>{
 const row=(await store.reserve(users[0]!,input(),config)).row;await store.finish(row.id,'succeeded',{text:'ok',tokens:100});
 const app=buildAiApp(config,store,async()=>({text:'x',tokens:1}));
 assert.equal((await app.inject({method:'GET',url:'/api/v1/ai/usage'})).statusCode,401);
 const response=await app.inject({method:'GET',url:'/api/v1/ai/usage',headers:{authorization:`Bearer ${tokens[1]}`}});
 assert.equal(response.json().data.requests,0);assert.equal(response.json().data.total_tokens,0);await app.close();
});
