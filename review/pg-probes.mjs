// Synthetic disposable PostgreSQL only. No provider/model/email or production connections.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';

if(process.env.GITHUB_ACTIONS!=='true')throw Error('DISPOSABLE_CI_ONLY');
for(const name of ['VERIFY_DATABASE_URL','VERIFY_APP_URL']) {
  const u=new URL(process.env[name]);
  if(u.hostname!=='127.0.0.1'||u.pathname!=='/whaletodo_verify')throw Error('DATABASE_TARGET_REFUSED');
}
const source=resolve(process.env.REVIEW_SOURCE);
const require=createRequire(resolve(source,'package.json'));
const {Pool}=require('pg');const {PrismaClient}=require('@prisma/client');
const load=name=>import(pathToFileURL(resolve(source,name)).href);
const {PrismaRepository}=await load('src/repo/prisma.ts');
const {buildApp}=await load('src/http/app.ts');
const {loadConfig}=await load('src/config.ts');
const owner=new Pool({connectionString:process.env.VERIFY_DATABASE_URL,max:2});
const appUrl=process.env.VERIFY_APP_URL+'?schema=whaletodo&connection_limit=2';
const clients=[];
function client(){const c=new PrismaClient({datasourceUrl:appUrl});clients.push(c);return c;}
const findings=[];const evidence={case:Number(process.env.REVIEW_CASE),postgres:16,production_accessed:false,observations:{},findings};
async function user(){
  const id=randomUUID();
  await owner.query('INSERT INTO users(id,username,password_hash,display_name) VALUES($1,$2,$3,$4)',[id,'rv_'+id.slice(0,8),'synthetic-only-hash','Synthetic review']);
  await owner.query('INSERT INTO point_wallets(user_id,available,reserved) VALUES($1,0,0)',[id]);
  return id;
}
function wrapAtVipRead(prisma,arrive){
  return new Proxy(prisma,{get(target,key){
    if(key==='$transaction')return (fn,options)=>target.$transaction(tx=>fn(new Proxy(tx,{get(t,k){
      const value=t[k];
      if(k==='$queryRaw')return async(...args)=>{
        const result=await value.apply(t,args);
        const sql=Array.isArray(args[0])?args[0].join(''):String(args[0]);
        if(/SELECT\s+max\(expires_at\)/i.test(sql))await arrive();
        return result;
      };
      return typeof value==='function'?value.bind(t):value;
    }})),options);
    const value=target[key];return typeof value==='function'?value.bind(target):value;
  }});
}

try {
  if(evidence.case===23){
    const id=await user();const now=Date.now();
    await owner.query("INSERT INTO products(product_id,title,price_minor,currency,vip_days,points,active) VALUES('review_vip_only','Synthetic VIP',100,'CNY',7,0,true)");
    const rawA=client(),rawB=client();
    const repo=new PrismaRepository(rawA);
    const a=await repo.createOrder({userId:id,productId:'review_vip_only',environment:'mock'});
    const b=await repo.createOrder({userId:id,productId:'review_vip_only',environment:'mock'});
    let entered=0;let release;
    const barrier=new Promise(resolve=>{release=resolve;});
    const arrive=async()=>{
      if(++entered===2)release();
      let timer;
      try{await Promise.race([barrier,new Promise((_,reject)=>{timer=setTimeout(()=>reject(Error('VIP_BARRIER_TIMEOUT')),2500);})]);}
      finally{clearTimeout(timer);}
    };
    const left=new PrismaRepository(wrapAtVipRead(rawA,arrive));
    const right=new PrismaRepository(wrapAtVipRead(rawB,arrive));
    const pay=(r,o)=>r.applyPayment({userId:id,provider:'mock',eventId:randomUUID(),orderId:o.id,amountMinor:100,currency:'CNY',payloadHash:'a'.repeat(64),now});
    const outcomes=await Promise.all([pay(left,a),pay(right,b)]);
    const rows=(await owner.query("SELECT expires_at FROM entitlements WHERE user_id=$1 AND kind='vip' ORDER BY expires_at",[id])).rows;
    const effectiveDays=(Math.max(...rows.map(r=>r.expires_at.getTime()))-now)/86400000;
    const paid=(await owner.query("SELECT count(*)::int AS n FROM orders WHERE user_id=$1 AND status='PAID'",[id])).rows[0].n;
    assert.deepEqual(outcomes,['applied','applied']);assert.equal(paid,2);assert.equal(rows.length,2);
    evidence.observations.concurrent_vip_only_orders={points_per_product:0,vip_days_per_product:7,outcomes,paid_orders:paid,entitlement_rows:rows.length,expected_effective_days:14,actual_effective_days:effectiveDays};
    if(effectiveDays!==14)findings.push('P1 concurrent distinct VIP-only orders both paid but one extension is lost');
  }else if(evidence.case===24){
    const prisma=client();const repo=new PrismaRepository(prisma);
    const {createTicketToken}=await load('src/domain/email.ts');
    const t=createTicketToken();const normal='bound-review@example.invalid';const now=Date.now();
    await repo.createEmailTicket({purpose:'register',email:normal,ticketDigest:t.digest,now,ttlMs:60000});
    const wrongPurpose=await repo.consumeEmailTicket({purpose:'reset',email:normal,ticketDigest:t.digest,now});
    const wrongEmail=await repo.consumeEmailTicket({purpose:'register',email:'other-review@example.invalid',ticketDigest:t.digest,now});
    const correct=await repo.consumeEmailTicket({purpose:'register',email:normal,ticketDigest:t.digest,now});
    assert.deepEqual([wrongPurpose,wrongEmail,correct],[false,false,true]);
    evidence.observations.ticket_binding_control={wrong_purpose:wrongPurpose,wrong_email:wrongEmail,correct};
    const original=repo.createEmailTicket.bind(repo);let failNext=false;const sent=[];
    repo.createEmailTicket=async input=>{if(failNext){failNext=false;throw Error('SYNTHETIC_TICKET_INSERT_FAILURE');}return original(input);};
    const app=buildApp({repo,config:loadConfig({NODE_ENV:'test',PAYMENT_PROVIDER:'disabled',IDEMPOTENCY_SECRET:'synthetic-ci-review-only-secret'}),emailCodeSink:input=>{sent.push(input);}});
    try{
      const email='fault-review@example.invalid';
      const send=await app.inject({method:'POST',url:'/api/v1/auth/email/send-code',payload:{email,purpose:'register'}});
      assert.equal(send.statusCode,202);assert.equal(send.json().data.sent,true);
      const code=sent.find(r=>r.email===email).code;failNext=true;
      const request={method:'POST',url:'/api/v1/auth/email/verify',payload:{email,purpose:'register',code}};
      const first=await app.inject(request);
      const state=(await owner.query('SELECT consumed_at FROM email_verifications WHERE email_normalized=$1',[email])).rows[0];
      const tickets=(await owner.query('SELECT count(*)::int AS n FROM email_verify_tickets WHERE email_normalized=$1',[email])).rows[0].n;
      const retry=await app.inject(request);
      evidence.observations.otp_to_ticket_failure={first_status:first.statusCode,consumed:state.consumed_at!==null,tickets,retry_status:retry.statusCode};
      if(first.statusCode===500&&state.consumed_at!==null&&tickets===0&&retry.statusCode===400)findings.push('P2 OTP consumed before ticket insert; failure prevents correct-code retry');
      const cooldownEmail='cooldown-review@example.invalid';const responses=[];
      for(const purpose of ['register','reset','bind']){
        const r=await app.inject({method:'POST',url:'/api/v1/auth/email/send-code',payload:{email:cooldownEmail,purpose}});
        responses.push({purpose,status:r.statusCode,sent:r.json().data.sent,retry_after_ms:r.json().data.retry_after_ms});
      }
      const hooks=sent.filter(r=>r.email===cooldownEmail).length;
      evidence.observations.cross_purpose_cooldown={responses,delivery_hook_calls:hooks};
      if(hooks>1)findings.push('P2 normalized-email cooldown bypassed by changing purpose');
    }finally{await app.close();}
  }else throw Error('UNKNOWN_REVIEW_CASE');
  writeFileSync('independent-pr'+evidence.case+'-evidence.json',JSON.stringify(evidence,null,2)+'\n');
  console.log(JSON.stringify(evidence,null,2));
  if(findings.length)process.exitCode=2;
}finally{
  await Promise.all(clients.map(c=>c.$disconnect()));await owner.end();
}
