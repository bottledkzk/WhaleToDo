import {test,before,after,beforeEach} from 'node:test';
import assert from 'node:assert/strict';
import {Pool} from 'pg';
import {PrismaClient} from '@prisma/client';
import {PrismaRepository} from '../src/repo/prisma.ts';
const ownerUrl=process.env.VERIFY_DATABASE_URL!,appUrl=process.env.VERIFY_APP_URL!,aiUrl=process.env.VERIFY_AI_URL!;
if(!ownerUrl||new URL(ownerUrl).pathname!=='/whaletodo_verify'||!appUrl)throw Error('ISOLATED_VERIFY_DATABASE_REQUIRED');
const owner=new Pool({connectionString:ownerUrl,max:1});
const prisma=new PrismaClient({datasourceUrl:appUrl+(appUrl.includes('?')?'&':'?')+'schema=whaletodo'});
const repo=new PrismaRepository(prisma);
before(async()=>{await owner.query('SELECT 1')});
beforeEach(async()=>{await owner.query('TRUNCATE users CASCADE')});
after(async()=>{await owner.query('TRUNCATE users CASCADE');await prisma.$disconnect();await owner.end()});
test('profile persistence and concurrent compare-and-swap use real restricted PostgreSQL role',async()=>{
 const u=await repo.createUser('cloud_pg','Synthetic-pass-123');
 const result=await Promise.allSettled(['a','b'].map(m=>repo.cloudWrite(u.id,'profile',0,{entries:{'profile.motto':m}})));
 assert.equal(result.filter(x=>x.status==='fulfilled').length,1);assert.equal(result.filter(x=>x.status==='rejected').length,1);
 const doc=await repo.cloudRead(u.id,'profile');assert.equal(doc.revision,1);
 const replay=await repo.cloudWrite(u.id,'profile',0,doc.payload);assert.equal(replay.revision,1);
});
test('VIP checked for reads and writes in production repository, profile remains free',async()=>{
 const u=await repo.createUser('cloud_vip','Synthetic-pass-123');
 await assert.rejects(repo.cloudRead(u.id,'schedule'),{code:'FORBIDDEN'});
 await assert.rejects(repo.cloudWrite(u.id,'schedule',0,{}),{code:'FORBIDDEN'});
 await owner.query("INSERT INTO entitlements(id,user_id,kind,expires_at) VALUES(gen_random_uuid(),$1,'vip',now()+interval '1 hour')",[u.id]);
 assert.equal((await repo.cloudWrite(u.id,'schedule',0,{schema_version:1})).revision,1);
 await owner.query("UPDATE entitlements SET expires_at=now()-interval '1 second' WHERE user_id=$1",[u.id]);
 await assert.rejects(repo.cloudRead(u.id,'schedule'),{code:'FORBIDDEN'});
 assert.equal((await repo.cloudWrite(u.id,'profile',0,{entries:{}})).revision,1);
});
test('new table enforces RLS with no tenant context, cross owner and AI role denial',async()=>{
 const a=await repo.createUser('cloud_rlsa','Synthetic-pass-123'),b=await repo.createUser('cloud_rlsb','Synthetic-pass-123');
 await repo.cloudWrite(a.id,'profile',0,{entries:{'diary:2030-01-01':'合成私有日记'}});
 assert.equal((await repo.cloudRead(b.id,'profile')).revision,0);
 assert.equal((await prisma.$queryRaw<unknown[]>`SELECT * FROM account_documents`).length,0);
 await assert.rejects(prisma.$executeRaw`DELETE FROM account_documents WHERE user_id=${a.id}::uuid`);
 await prisma.$transaction(async tx=>{await tx.$executeRaw`SELECT set_config('app.user_id',${b.id},true)`;assert.equal((await tx.$queryRaw<unknown[]>`SELECT * FROM account_documents WHERE user_id=${a.id}::uuid`).length,0);
  await assert.rejects(tx.$executeRaw`INSERT INTO account_documents(user_id,kind,payload) VALUES(${a.id}::uuid,'profile','{}')`);
 });
 if(aiUrl){const ai=new Pool({connectionString:aiUrl,max:1});try{await assert.rejects(ai.query('SELECT * FROM whaletodo.account_documents'))}finally{await ai.end()}}
});
