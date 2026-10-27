// Independent positive regression checks; disposable CI PostgreSQL only.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { writeFileSync } from 'node:fs';
if (process.env.GITHUB_ACTIONS !== 'true') throw Error('DISPOSABLE_CI_ONLY');
for (const key of ['VERIFY_DATABASE_URL', 'VERIFY_APP_URL']) {
  const u = new URL(process.env[key]);
  if (u.hostname !== '127.0.0.1' || u.pathname !== '/whaletodo_verify') throw Error('DATABASE_TARGET_REFUSED');
}
const source = resolve(process.env.REVIEW_SOURCE);
const require = createRequire(resolve(source, 'package.json'));
const { Pool } = require('pg');
const { PrismaClient } = require('@prisma/client');
const load = name => import(pathToFileURL(resolve(source, name)).href);
const { PrismaRepository } = await load('src/repo/prisma.ts');
const { buildApp } = await load('src/http/app.ts');
const { loadConfig } = await load('src/config.ts');
const { hashPassword } = await load('src/lib/security.ts');
const { createTicketToken } = await load('src/domain/email.ts');
const owner = new Pool({ connectionString: process.env.VERIFY_DATABASE_URL, max: 2 });
const clients = [];
function client() {
  const c = new PrismaClient({ datasourceUrl: process.env.VERIFY_APP_URL + '?schema=whaletodo&connection_limit=3' });
  clients.push(c); return c;
}
function wrap(prisma, intercept) {
  return new Proxy(prisma, { get(target, key) {
    if (key === '$transaction') return (fn, options) => target.$transaction(tx => fn(new Proxy(tx, { get(t, k) {
      const v = t[k];
      if (k === '$queryRaw' || k === '$executeRaw') return (...args) => intercept(k, args, () => v.apply(t, args), (...other) => v.apply(t, other));
      return typeof v === 'function' ? v.bind(t) : v;
    } })), options);
    const v = target[key]; return typeof v === 'function' ? v.bind(target) : v;
  } });
}
const sqlOf = args => Array.isArray(args[0]) ? args[0].join('') : String(args[0]);
const evidence = { case: Number(process.env.REVIEW_CASE), postgres: 16, restricted_app_role: true, production_accessed: false, observations: {}, passed: false };
try {
  if (evidence.case === 23) {
    const id = randomUUID(), now = Date.now();
    await owner.query('INSERT INTO users(id,username,password_hash,display_name) VALUES($1,$2,$3,$4)', [id, 'rv_' + id.slice(0,8), hashPassword('Synthetic-review-only-password'), 'Synthetic review']);
    await owner.query('INSERT INTO point_wallets(user_id,available,reserved) VALUES($1,0,0)', [id]);
    await owner.query("INSERT INTO products(product_id,title,price_minor,currency,vip_days,points,active) VALUES('review_vip_only','Synthetic VIP',100,'CNY',7,0,true)");
    const rawA = client(), rawB = client(), repo = new PrismaRepository(rawA);
    const orders = await Promise.all([1,2].map(() => repo.createOrder({ userId: id, productId: 'review_vip_only', environment: 'mock' })));
    let activeReads = 0, maxActiveReads = 0;
    const pauseAfterRead = async (key, args, run) => {
      const result = await run();
      if (key === '$queryRaw' && /SELECT\s+max\(expires_at\)/i.test(sqlOf(args))) {
        maxActiveReads = Math.max(maxActiveReads, ++activeReads);
        // One-sided delay, not the previous two-way barrier: valid user serialization must progress.
        await new Promise(r => setTimeout(r, 250));
        activeReads--;
      }
      return result;
    };
    const repos = [rawA, rawB].map(c => new PrismaRepository(wrap(c, pauseAfterRead)));
    const outcomes = await Promise.all(orders.map((o, i) => repos[i].applyPayment({ userId: id, provider: 'mock', eventId: randomUUID(), orderId: o.id, amountMinor: 100, currency: 'CNY', payloadHash: 'a'.repeat(64), now })));
    const rows = (await owner.query("SELECT expires_at FROM entitlements WHERE user_id=$1 AND kind='vip' ORDER BY expires_at", [id])).rows;
    const paid = (await owner.query("SELECT count(*)::int AS n FROM orders WHERE user_id=$1 AND status='PAID'", [id])).rows[0].n;
    const events = (await owner.query('SELECT count(*)::int AS n FROM payment_events WHERE order_id=ANY($1::uuid[])', [orders.map(o=>o.id)])).rows[0].n;
    const wallet = (await owner.query('SELECT available FROM point_wallets WHERE user_id=$1', [id])).rows[0].available;
    const days = (Math.max(...rows.map(r=>r.expires_at.getTime())) - now) / 86400000;
    evidence.observations.vip_only_concurrency = { outcomes, paid, events, entitlements: rows.length, effective_days: days, wallet, max_concurrent_vip_reads: maxActiveReads };
    assert.deepEqual(outcomes, ['applied','applied']); assert.equal(paid,2); assert.equal(events,2);
    assert.equal(rows.length,2); assert.equal(days,14); assert.equal(wallet,0); assert.equal(maxActiveReads,1);
  } else if (evidence.case === 24) {
    const raw = client(), collision = createTicketToken();
    await new PrismaRepository(raw).createEmailTicket({ purpose:'register', email:'collision@example.invalid', ticketDigest:collision.digest, now:Date.now(), ttlMs:60000 });
    let failNext = false, faultInjected = false;
    const wrapped = wrap(raw, async (key,args,run,rerun) => {
      if (failNext && key === '$executeRaw' && /INSERT INTO email_verify_tickets/i.test(sqlOf(args))) {
        failNext = false; faultInjected = true;
        // Real PG uniqueness error AFTER code update, inside the same transaction.
        const replaced = [...args]; replaced[2] = collision.digest;
        return rerun(...replaced);
      }
      return run();
    });
    const repo = new PrismaRepository(wrapped), sent = [];
    const app = buildApp({ repo, config:loadConfig({ NODE_ENV:'test', PAYMENT_PROVIDER:'disabled', IDEMPOTENCY_SECRET:'synthetic-ci-review-only-secret-20261026' }), emailCodeSink:input=>{sent.push(input);} });
    const send = (email,purpose) => app.inject({ method:'POST',url:'/api/v1/auth/email/send-code',payload:{email,purpose} });
    try {
      const email = 'fault-review@example.invalid';
      assert.equal((await send(email,'register')).json().data.sent,true);
      const request = { method:'POST',url:'/api/v1/auth/email/verify',payload:{email,purpose:'register',code:sent.find(r=>r.email===email).code} };
      failNext = true;
      const first = await app.inject(request);
      const state = (await owner.query('SELECT consumed_at,attempts FROM email_verifications WHERE email_normalized=$1',[email])).rows[0];
      const tickets = (await owner.query('SELECT count(*)::int AS n FROM email_verify_tickets WHERE email_normalized=$1',[email])).rows[0].n;
      const retry = await app.inject(request), replay = await app.inject(request);
      const finalTickets = (await owner.query('SELECT count(*)::int AS n FROM email_verify_tickets WHERE email_normalized=$1',[email])).rows[0].n;
      evidence.observations.atomic_failure_recovery = { faultInjected, first_status:first.statusCode, consumed_after_failure:state.consumed_at!==null, attempts:state.attempts, tickets_after_failure:tickets, retry_status:retry.statusCode, replay_status:replay.statusCode, final_tickets:finalTickets };
      assert.equal(faultInjected,true); assert.equal(first.statusCode,500); assert.equal(state.consumed_at,null); assert.equal(state.attempts,0); assert.equal(tickets,0); assert.equal(retry.statusCode,200); assert.equal(replay.statusCode,400); assert.equal(finalTickets,1);
      const sequential = [];
      for (const purpose of ['register','reset','bind']) {
        const r = await send(' Cooldown-Review@EXAMPLE.INVALID ',purpose);
        sequential.push({ status:r.statusCode,...r.json().data });
      }
      evidence.observations.cross_purpose_sequential = sequential;
      assert.deepEqual(sequential.map(r=>r.sent),[true,false,false]); assert.ok(sequential[1].retry_after_ms>0);
      const concurrent = await Promise.all(['register','reset','bind'].map(p=>send('race-review@example.invalid',p)));
      const rows = (await owner.query('SELECT count(*)::int AS n FROM email_verifications WHERE email_normalized=$1',['race-review@example.invalid'])).rows[0].n;
      evidence.observations.cross_purpose_concurrent = { statuses:concurrent.map(r=>r.statusCode), sent:concurrent.map(r=>r.json().data.sent), rows, hook_calls:sent.filter(r=>r.email==='race-review@example.invalid').length };
      assert.ok(concurrent.every(r=>r.statusCode===202)); assert.equal(concurrent.filter(r=>r.json().data.sent).length,1); assert.equal(rows,1);
      const t = createTicketToken(), normal='bound-review@example.invalid', now=Date.now();
      await repo.createEmailTicket({purpose:'register',email:normal,ticketDigest:t.digest,now,ttlMs:60000});
      const binding = await Promise.all([
        repo.consumeEmailTicket({purpose:'reset',email:normal,ticketDigest:t.digest,now}),
        repo.consumeEmailTicket({purpose:'register',email:'other-review@example.invalid',ticketDigest:t.digest,now}),
      ]);
      binding.push(await repo.consumeEmailTicket({purpose:'register',email:normal,ticketDigest:t.digest,now}));
      evidence.observations.ticket_binding = binding; assert.deepEqual(binding,[false,false,true]);
    } finally { await app.close(); }
  } else throw Error('UNKNOWN_REVIEW_CASE');
  evidence.passed = true;
} catch (error) {
  evidence.error = String(error); process.exitCode = 2;
} finally {
  writeFileSync('independent-pr'+evidence.case+'-evidence.json',JSON.stringify(evidence,null,2)+'\n');
  console.log(JSON.stringify(evidence,null,2));
  await Promise.all(clients.map(c=>c.$disconnect())); await owner.end();
}
