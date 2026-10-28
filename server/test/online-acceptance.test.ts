import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { runAcceptance, type AcceptanceOptions } from '../tools/online-acceptance.ts';
import { PROMPT_VERSION } from '../src/ai/policy.ts';
import { buildApp } from '../src/http/app.ts';
import { buildAiApp } from '../src/ai/app.ts';
import type { AiStore } from '../src/ai/store.ts';
import type { AiConfig } from '../src/ai/config.ts';
import { MemoryRepository } from '../src/repo/memory.ts';
import { fromSync } from '../src/repo/from-sync.ts';
import { loadConfig } from '../src/config.ts';

function fixture() {
  const dir = mkdtempSync(join(tmpdir(), 'whaletodo-acceptance-'));
  const options: AcceptanceOptions = { baseUrl: 'https://acceptance.invalid/api/v1', token: 'a'.repeat(43),
    sourceCommit: 'b'.repeat(40), receipt: join(dir, 'receipt.json'), action: 'execute' };
  let balance = 100; let pause = ''; let queryStatus = 200; let owner = '00000000-0000-4000-a000-000000000001';
  let promptVersion = PROMPT_VERSION; let malformed = false;
  const posts: any[] = [], queries: string[] = []; const accepted = new Map<string, any>();
  function response(status: number, body: unknown) { return new Response(JSON.stringify(body), { status }); }
  function succeeded(input: any) {
    if (!accepted.get(input.client_request_id)?.settled) {
      balance--; accepted.set(input.client_request_id, { ...input, settled: true });
    }
    return response(200, { data: { id: input.client_request_id, mode: input.mode, status: 'succeeded',
      text: malformed ? '' : input.mode === 'diary' ? '我完成了二十分钟阅读，也整理了书桌。' : '阅读二十分钟，让书桌和思路都更清楚。',
      usage_tokens: 20, points_charged: 1, continuation: false } });
  }
  const fetcher = (async (url: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(url)).pathname;
    if (path === '/api/v1/health/live' || path === '/api/v1/ai/health') return response(200, { data: { status: 'ok' } });
    if (path.endsWith('/ai/models')) {
      if (!(init?.headers as any)?.Authorization) return response(401, { error: { code: 'UNAUTHENTICATED' } });
      return response(200, { data: { prompt_version: promptVersion, tokens_per_point: 1000, items: [{ code: 'deepseek-default' }] } });
    }
    if (path.endsWith('/me')) return response(200, { data: { user: { id: owner, email: 'private-synthetic@example.invalid' } } });
    if (path.endsWith('/wallet')) return response(200, { data: { available: balance, reserved: 0 } });
    if (path.endsWith('/ai/requests')) {
      const body = JSON.parse(String(init?.body)); posts.push(body); accepted.set(body.client_request_id, body);
      // Inspect the durable journal at the exact HTTP boundary.
      const saved = JSON.parse(readFileSync(options.receipt!, 'utf8'));
      assert.equal(saved.entries.find((e: any) => e.id === body.client_request_id).post_attempted, true);
      assert.equal(saved.entries.find((e: any) => e.id === body.client_request_id).state, 'unknown');
      if (pause === 'disconnect') throw new Error('socket reset with fake secret');
      if (pause === '202') return response(202, { data: { id: body.client_request_id, mode: body.mode, status: 'unknown' } });
      if (pause === '429') return response(429, { error: { code: 'RATE_LIMITED' } });
      return succeeded(body);
    }
    assert.match(path, /^\/api\/v1\/ai\/requests\/[a-f0-9-]{36}$/i, 'unexpected route');
    const id = path.split('/').at(-1)!; queries.push(id);
    if (queryStatus !== 200) return response(queryStatus, { error: { code: 'RATE_LIMITED' } });
    return succeeded(accepted.get(id));
  }) as typeof fetch;
  return { options, fetcher, posts, queries, clean: () => rmSync(dir, { recursive: true, force: true }),
    pause: (value: string) => { pause = value; }, queryStatus: (value: number) => { queryStatus = value; },
    owner: () => { owner = '00000000-0000-4000-a000-000000000002'; }, oldPrompt: () => { promptVersion = 'old'; },
    lowBalance: () => { balance = 1; }, malformed: () => { malformed = true; } };
}

test('preflight uses real account and AI routes, nested identity and no model admission', async () => {
  const repo = new MemoryRepository();
  const user = await repo.seedEmailAccount('route-probe@example.invalid', 'Synthetic-route-passw0rd');
  const { tokens } = repo.createSession(user.id);
  const app = buildApp({ config: loadConfig({ NODE_ENV: 'test' }), repo: fromSync(repo) });
  let modelCalls = 0;
  const store = { ready: async () => {}, authenticate: async (token: string) => {
    const session = repo.findActiveSessionByAccess(token);
    assert.ok(session); return session.userId;
  }, vip: async () => false } as unknown as AiStore;
  const ai = buildAiApp({ tokensPerPoint: 1000 } as AiConfig, store, async () => { modelCalls++; throw new Error('model must not run'); });
  const paths: string[] = [];
  const fetcher = (async (url: string | URL | Request, init?: RequestInit) => {
    const path = new URL(String(url)).pathname; paths.push(path);
    assert.equal(init?.method, 'GET');
    const target = path.startsWith('/api/v1/ai/') ? ai : app;
    const response = await target.inject({ method: 'GET', url: path, headers: init?.headers as Record<string, string> });
    return new Response(response.body, { status: response.statusCode });
  }) as typeof fetch;
  try {
    assert.equal((await app.inject({ method: 'GET', url: '/api/v1/health' })).statusCode, 404);
    const result = await runAcceptance({ baseUrl: 'https://acceptance.invalid/api/v1', token: tokens.accessToken,
      sourceCommit: 'b'.repeat(40), action: 'preflight' }, fetcher);
    assert.equal('model_post_attempts' in result && result.model_post_attempts, 0);
    assert.equal('database_readiness_verified' in result && result.database_readiness_verified, false);
    assert.ok(paths.includes('/api/v1/health/live')); assert.ok(paths.includes('/api/v1/me'));
    assert.equal(modelCalls, 0);
  } finally { await app.close(); await ai.close(); }
});

test('initial POST 429 remains pending and resume queries its original ID without re-POST', async () => {
  const f = fixture(); try {
    f.pause('429'); let result = await runAcceptance(f.options, f.fetcher);
    assert.equal('status' in result && result.status, 'pending'); const id = f.posts[0].client_request_id;
    f.queryStatus(404); result = await runAcceptance({ ...f.options, action: 'resume' }, f.fetcher);
    assert.equal('status' in result && result.status, 'pending'); assert.equal(f.posts.length, 1);
    assert.equal(f.queries.at(-1), id);
    f.pause(''); f.queryStatus(200); result = await runAcceptance({ ...f.options, action: 'resume' }, f.fetcher);
    assert.equal('status' in result && result.status, 'passed'); assert.equal(f.posts.length, 2);
    assert.equal(f.posts.filter(p => p.client_request_id === id).length, 1);
  } finally { f.clean(); }
});
test('preflight performs zero model POSTs and detects insufficient admission points', async () => {
  const f = fixture(); try {
    f.lowBalance(); const result = await runAcceptance({ ...f.options, action: 'preflight' }, f.fetcher);
    assert.equal('ready' in result && result.ready, false); assert.equal(f.posts.length, 0);
  } finally { f.clean(); }
});
test('two explicit synthetic requests settle points without storing token, email or output prose', async () => {
  const f = fixture(); try {
    const result = await runAcceptance(f.options, f.fetcher);
    assert.equal('status' in result && result.status, 'passed'); assert.equal(f.posts.length, 2);
    const saved = readFileSync(f.options.receipt!, 'utf8');
    for (const value of [f.options.token, 'private-synthetic', '我完成了', '阅读二十分钟']) assert.ok(!saved.includes(value));
    await assert.rejects(runAcceptance(f.options, f.fetcher), /RECEIPT_EXISTS/); assert.equal(f.posts.length, 2);
  } finally { f.clean(); }
});
test('disconnect and repeated query 429/503 preserve the original ID and never re-POST', async () => {
  const f = fixture(); try {
    f.pause('disconnect'); let result = await runAcceptance(f.options, f.fetcher);
    assert.equal('status' in result && result.status, 'pending'); const id = f.posts[0].client_request_id;
    for (const status of [429, 503]) {
      f.queryStatus(status); result = await runAcceptance({ ...f.options, action: 'resume' }, f.fetcher);
      assert.equal('status' in result && result.status, 'pending'); assert.equal(f.posts.length, 1);
      assert.equal(f.queries.at(-1), id);
    }
    f.pause(''); f.queryStatus(200); result = await runAcceptance({ ...f.options, action: 'resume' }, f.fetcher);
    assert.equal('status' in result && result.status, 'passed'); assert.equal(f.posts.length, 2);
    assert.equal(f.posts.filter(p => p.client_request_id === id).length, 1);
  } finally { f.clean(); }
});
test('resume rejects another owner or modified payload journal before any POST', async () => {
  const f = fixture(); try {
    f.pause('202'); await runAcceptance(f.options, f.fetcher); f.owner();
    await assert.rejects(runAcceptance({ ...f.options, action: 'resume' }, f.fetcher), /CONTEXT_MISMATCH/);
    assert.equal(f.posts.length, 1);
  } finally { f.clean(); }
  const g = fixture(); try {
    g.pause('202'); await runAcceptance(g.options, g.fetcher);
    const saved = JSON.parse(readFileSync(g.options.receipt!, 'utf8')); saved.entries[0].payload_sha256 = 'tampered';
    writeFileSync(g.options.receipt!, JSON.stringify(saved));
    await assert.rejects(runAcceptance({ ...g.options, action: 'resume' }, g.fetcher), /RECEIPT_INVALID/);
    assert.equal(g.posts.length, 1);
  } finally { g.clean(); }
});
test('old gateway, low balance and occupied receipt lock fail before model admission', async () => {
  const f = fixture(); try { f.oldPrompt(); await assert.rejects(runAcceptance(f.options, f.fetcher), /VERSION_MISMATCH/); assert.equal(f.posts.length, 0); } finally { f.clean(); }
  const g = fixture(); try { g.lowBalance(); await assert.rejects(runAcceptance(g.options, g.fetcher), /BALANCE/); assert.equal(g.posts.length, 0); } finally { g.clean(); }
  const h = fixture(); try { writeFileSync(h.options.receipt! + '.lock', 'another process'); await assert.rejects(runAcceptance(h.options, h.fetcher)); assert.equal(h.posts.length, 0); } finally { h.clean(); }
});
test('structure failure is terminal for this run and resume cannot spend on its second request', async () => {
  const f = fixture(); try {
    f.malformed(); const result = await runAcceptance(f.options, f.fetcher);
    assert.equal('status' in result && result.status, 'failed'); assert.equal(f.posts.length, 1);
    await runAcceptance({ ...f.options, action: 'resume' }, f.fetcher); assert.equal(f.posts.length, 1);
  } finally { f.clean(); }
});
