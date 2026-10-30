import {test,beforeEach,after} from 'node:test';
import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {Pool,type PoolClient} from 'pg';
import {PrismaClient} from '@prisma/client';
import {PrismaRepository} from '../src/repo/prisma.ts';
const url=process.env.VERIFY_DATABASE_URL;
if(!url||new URL(url).pathname!=='/whaletodo_verify')throw new Error('Disposable CI database only');
const owner=new Pool({connectionString:url,max:2});
const runtime=new Pool({connectionString:process.env.VERIFY_APP_URL,max:3});
const ai=new Pool({connectionString:process.env.VERIFY_AI_URL,max:1});
const prisma=new PrismaClient({datasources:{db:{url:process.env.VERIFY_APP_URL+'?schema=whaletodo'}}});
const repository=new PrismaRepository(prisma);
let a:string,b:string,order:string;
const snapshot={title:'Demo',price_minor:100,currency:'CNY',points:10,vip_days:7};
async function asUser<T>(id:string,fn:(db:PoolClient)=>Promise<T>):Promise<T>{
 const db=await runtime.connect();try{await db.query('BEGIN');await db.query("SELECT set_config('app.user_id',$1,true)",[id]);const r=await fn(db);await db.query('COMMIT');return r;}
 catch(e){await db.query('ROLLBACK');throw e;}finally{db.release();}
}
async function fails(fn:()=>Promise<unknown>,code:string){await assert.rejects(fn,e=>(e as {code:string}).code===code);}
beforeEach(async()=>{
 await owner.query('TRUNCATE users CASCADE');
 await owner.query("INSERT INTO products(product_id,title,price_minor,currency,points,vip_days) VALUES('ops_test','Demo',100,'CNY',10,7) ON CONFLICT DO NOTHING");
 a=randomUUID();b=randomUUID();order=randomUUID();
 for(const [id,name]of [[a,'ops_a'],[b,'ops_b']])await owner.query('INSERT INTO users(id,username,password_hash,display_name) VALUES($1,$2,$3,$4)',[id,name,'scrypt$synthetic-only',name]);
 await owner.query("INSERT INTO orders(id,user_id,product_id,amount_minor,currency,status,environment,product_snapshot) VALUES($1,$2,'ops_test',100,'CNY','PENDING','mock',$3)",[order,a,snapshot]);
});
after(async()=>{await owner.query('TRUNCATE users CASCADE');await owner.query("DELETE FROM products WHERE product_id='ops_test'");await Promise.all([owner.end(),runtime.end(),ai.end(),prisma.$disconnect()]);});
test('23 business tables and no automatic production environment',async()=>{
 assert.equal((await owner.query("SELECT count(*)::int AS n FROM information_schema.tables WHERE table_schema='whaletodo' AND table_name<>'_prisma_migrations'")).rows[0].n,23);
 await fails(()=>owner.query("INSERT INTO orders(id,user_id,product_id,amount_minor,currency,status,product_snapshot) VALUES($1,$2,'ops_test',100,'CNY','PENDING',$3)",[randomUUID(),a,snapshot]),'23502');
});
test('order and callback RLS fails closed, only owner reads',async()=>{
 assert.equal((await runtime.query('SELECT * FROM orders')).rowCount,0);
 assert.equal(await asUser(a,async db=>(await db.query('SELECT * FROM orders')).rowCount),1);
 assert.equal(await asUser(b,async db=>(await db.query('SELECT * FROM orders')).rowCount),0);
 await fails(()=>asUser(b,async db=>db.query("INSERT INTO orders(id,user_id,product_id,amount_minor,currency,status,environment,product_snapshot) VALUES($1,$2,'ops_test',100,'CNY','PENDING','mock',$3)",[randomUUID(),a,snapshot])),'42501');
 await owner.query("INSERT INTO payment_events(id,provider,event_id,order_id,status,amount_minor,currency,payload_hash) VALUES($1,'mock','owned',$2,'succeeded',100,'CNY',$3)",[randomUUID(),order,'a'.repeat(64)]);
 assert.equal(await asUser(b,async db=>(await db.query('SELECT * FROM payment_events')).rowCount),0);
});
test('runtime cannot edit products, roles, audit rows or schema',async()=>{
 await fails(()=>runtime.query("UPDATE users SET role='admin' WHERE id=$1",[a]),'42501');
 await fails(()=>runtime.query("UPDATE products SET price_minor=0 WHERE product_id='ops_test'"),'42501');
 await fails(()=>asUser(a,db=>db.query('DELETE FROM orders WHERE id=$1',[order])),'42501');
 await fails(()=>runtime.query('DELETE FROM email_verify_tickets'),'42501');
 await fails(()=>runtime.query('CREATE TABLE whaletodo.should_not_exist(id int)'),'42501');
});
test('AI role cannot access billing/email tables',async()=>{
 for(const table of ['products','orders','payment_events','email_verifications','email_throttle','email_verify_tickets']){
  // Test-only fixed allowlist; never use request-provided identifiers.
  const sql={products:'SELECT * FROM products',orders:'SELECT * FROM orders',payment_events:'SELECT * FROM payment_events',email_verifications:'SELECT * FROM email_verifications',email_throttle:'SELECT * FROM email_throttle',email_verify_tickets:'SELECT * FROM email_verify_tickets'}[table]!;
  await fails(()=>ai.query(sql),'42501');
 }
});
test('snapshots and identity are immutable; invalid state reversal rejected',async()=>{
 await fails(()=>owner.query('UPDATE orders SET product_snapshot=$2 WHERE id=$1',[order,{...snapshot,points:999}]),'23514');
 await fails(()=>owner.query("UPDATE orders SET status='REFUNDED',paid_at=now() WHERE id=$1",[order]),'23514');
 await asUser(a,db=>db.query("UPDATE orders SET status='PAID',paid_at=now() WHERE id=$1",[order]));
 await fails(()=>asUser(a,db=>db.query("UPDATE orders SET status='PENDING' WHERE id=$1",[order])),'23514');
 await asUser(a,db=>db.query("UPDATE orders SET status='REFUNDED' WHERE id=$1",[order]));
 await fails(()=>owner.query('DELETE FROM users WHERE id=$1',[a]),'23503');
});
test('invalid JSON snapshot fails and no account can link another owner order entitlement',async()=>{
 await fails(()=>owner.query("INSERT INTO orders(id,user_id,product_id,amount_minor,currency,status,environment,product_snapshot) VALUES($1,$2,'ops_test',100,'CNY','PENDING','mock',$3)",[randomUUID(),a,{}]),'23514');
 await fails(()=>owner.query("INSERT INTO entitlements(id,user_id,kind,expires_at,source_order_id) VALUES($1,$2,'vip',now()+interval '1 day',$3)",[randomUUID(),b,order]),'23503');
});
test('payment callback unique key prevents duplicate events',async()=>{
 const add=()=>asUser(a,db=>db.query("INSERT INTO payment_events(id,provider,event_id,order_id,status,amount_minor,currency,payload_hash) VALUES($1,'mock','one-event',$2,'succeeded',100,'CNY',$3)",[randomUUID(),order,'a'.repeat(64)]));
 const r=await Promise.allSettled([add(),add()]);assert.equal(r.filter(x=>x.status==='fulfilled').length,1);
 assert.equal((r.find(x=>x.status==='rejected') as PromiseRejectedResult).reason.code,'23505');
});
test('ticket consume admits one concurrent caller and cannot replay',async()=>{
 const hash='b'.repeat(64);
 await runtime.query("INSERT INTO email_verify_tickets(id,token_hmac,purpose,email_normalized,expires_at) VALUES($1,$2,'register','synthetic@example.invalid',now()+interval '60 seconds')",[randomUUID(),hash]);
 const consume=()=>runtime.query('UPDATE email_verify_tickets SET consumed_at=now() WHERE token_hmac=$1 AND consumed_at IS NULL AND expires_at>now() RETURNING id',[hash]);
 const r=await Promise.all([consume(),consume()]);assert.deepEqual(r.map(x=>x.rowCount).sort(),[0,1]);assert.equal((await consume()).rowCount,0);
});
test('code uniqueness, attempts and scope constraints prevent invalid storage',async()=>{
 const insert=()=>runtime.query("INSERT INTO email_verifications(id,purpose,email_normalized,code_hmac,expires_at) VALUES($1,'reset','synthetic@example.invalid',$2,now()+interval '5 minutes')",[randomUUID(),'c'.repeat(64)]);
 await insert();await fails(insert,'23505');
 await fails(()=>runtime.query('UPDATE email_verifications SET attempts=6'),'23514');
 await runtime.query('UPDATE email_verifications SET consumed_at=now()');await insert();
 await fails(()=>runtime.query("INSERT INTO email_throttle(scope,scope_key,window_start,count) VALUES('ip','plain-ip',now(),1)"),'23514');
});
test('shared throttle atomic increments survive concurrency',async()=>{
 const window=new Date('2026-01-01T00:00:00Z');
 const run=()=>runtime.query("INSERT INTO email_throttle(scope,scope_key,window_start,count) VALUES('email',$1,$2,1) ON CONFLICT(scope,scope_key,window_start) DO UPDATE SET count=email_throttle.count+1 RETURNING count",['d'.repeat(64),window]);
 const r=await Promise.all(Array.from({length:20},run));assert.deepEqual(r.map(x=>x.rows[0].count).sort((x,y)=>x-y),Array.from({length:20},(_,i)=>i+1));
});
test('SQL injection strings stay data in Prisma lookup and pg values',async()=>{
 for(const value of ["ops_a' OR '1'='1", "'; DROP TABLE users; --", "x'; SELECT pg_sleep(10); --",'ops_%'])assert.equal(await repository.findUserByUsername(value),null);
 const text="quote ' and ; -- /* SQL */ 中文";
 await asUser(a,db=>db.query('INSERT INTO tasks(id,user_id,title,note) VALUES($1,$2,$3,$4)',[randomUUID(),a,'Synthetic injection check',text]));
 assert.equal(await asUser(a,async db=>(await db.query('SELECT note FROM tasks WHERE user_id=$1',[a])).rows[0].note),text);
 assert.equal((await owner.query('SELECT count(*)::int AS n FROM users')).rows[0].n,2);
});

test('maintenance only expires old transient rows and preserves recent rows and orders',async()=>{
 for(const [suffix,old]of [['old',true],['recent',false]] as const){
  await owner.query("INSERT INTO idempotency_receipts(scope,key,fingerprint,response,expires_at) VALUES('ops-maintenance',$1,$2,'{}',now()-($3::int*interval '1 day'))",[suffix,'e'.repeat(64),old?8:1]);
  await owner.query("INSERT INTO email_verifications(id,purpose,email_normalized,code_hmac,created_at,expires_at) VALUES($1,'reset',$2,$3,now()-interval '26 hours',now()+($4::int*interval '1 hour'))",[randomUUID(),suffix+'@maintenance.invalid','f'.repeat(64),old?-25:1]);
  await owner.query("INSERT INTO email_verify_tickets(id,token_hmac,purpose,email_normalized,created_at,expires_at) VALUES($1,$2,'reset',$3,now()-interval '26 hours',now()+($4::int*interval '1 hour'))",[randomUUID(),(old?'1':'2').repeat(64),suffix+'@maintenance.invalid',old?-25:1]);
  await owner.query("INSERT INTO email_throttle(scope,scope_key,window_start,count) VALUES('ip',$1,now()-($2::int*interval '1 hour'),1)",[(old?'3':'4').repeat(64),old?49:47]);
 }
 await owner.query(readFileSync(new URL('../../env/postgresql/maintenance.sql',import.meta.url),'utf8'));
 assert.deepEqual((await owner.query("SELECT key FROM idempotency_receipts WHERE scope='ops-maintenance'")).rows,[{key:'recent'}]);
 assert.deepEqual((await owner.query("SELECT email_normalized FROM email_verifications WHERE email_normalized LIKE '%@maintenance.invalid'")).rows,[{email_normalized:'recent@maintenance.invalid'}]);
 assert.deepEqual((await owner.query("SELECT email_normalized FROM email_verify_tickets WHERE email_normalized LIKE '%@maintenance.invalid'")).rows,[{email_normalized:'recent@maintenance.invalid'}]);
 assert.equal((await owner.query("SELECT count(*)::int AS n FROM email_throttle WHERE scope='ip' AND scope_key=$1",['3'.repeat(64)])).rows[0].n,0);
 assert.equal((await owner.query('SELECT count(*)::int AS n FROM orders')).rows[0].n,1);
});
