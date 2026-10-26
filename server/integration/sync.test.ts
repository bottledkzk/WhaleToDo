// 同步的真库集成测试（database job：PG16 + 受限 whaletodo_app）
// 验证：批次幂等、版本冲突回传、RLS 跨用户隔离、locked 原样往返、变更序列游标
import { test, before, beforeEach, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { Pool } from 'pg';
import { PrismaClient } from '@prisma/client';
import { PrismaRepository } from '../src/repo/prisma.ts';

const ownerUrl = process.env.VERIFY_DATABASE_URL!;
const appUrl = process.env.VERIFY_APP_URL!;
if (!ownerUrl || new URL(ownerUrl).pathname !== '/whaletodo_verify') {
  throw new Error('Integration tests require isolated whaletodo_verify database');
}
if (!appUrl) throw new Error('Integration tests require VERIFY_APP_URL');

const appUrlWithSchema = /[?&]schema=/.test(appUrl)
  ? appUrl
  : `${appUrl}${appUrl.includes('?') ? '&' : '?'}schema=whaletodo`;

const owner = new Pool({ connectionString: ownerUrl, max: 2 });
let prisma: PrismaClient;
let repo: PrismaRepository;

const PASSWORD = 'Synthetic-integration-password';

before(async () => {
  await owner.query('SELECT 1');
  prisma = new PrismaClient({ datasourceUrl: appUrlWithSchema });
  repo = new PrismaRepository(prisma);
});

beforeEach(async () => {
  await owner.query('TRUNCATE users CASCADE');   // 级联清 sync_batches / sync_changes / sync_mutations
});

after(async () => {
  await owner.query('TRUNCATE users CASCADE');
  await prisma.$disconnect();
  await owner.end();
});

const taskId = () => randomUUID();

test('创建类变更：applied v1，locked 在 payload 中原样往返', async () => {
  const user = await repo.createUser('sync_one', PASSWORD);
  const batchId = randomUUID();
  const t1 = taskId();
  const b1 = taskId();

  const res = await repo.pushSyncBatch!({
    userId: user.id,
    batchId,
    deviceId: 'dev-1',
    fingerprint: 'f'.repeat(64),
    mutations: [
      { objectType: 'task', objectId: t1, op: 'create', expectedVersion: null, payload: { title: '写周报' } },
      { objectType: 'block', objectId: b1, op: 'create', expectedVersion: null, payload: { locked: true, start: 1, end: 2 } },
    ],
  });
  assert.equal(res.status, 'applied');
  assert.equal(res.replayed, false);
  assert.deepEqual(res.items.map((i) => i.result), ['applied', 'applied']);
  assert.deepEqual(res.items.map((i) => i.server_version), [1, 1]);

  const pull = await repo.pullSyncChanges!(user.id, 0, 100);
  assert.equal(pull.changes.length, 2);
  const block = pull.changes.find((c) => c.objectId === b1)!;
  assert.deepEqual(block.payload, { locked: true, start: 1, end: 2 }, 'locked 必须原样往返');
});

test('批次幂等：同 batch_id 重放返回首次回执，不重复应用', async () => {
  const user = await repo.createUser('sync_replay', PASSWORD);
  const batchId = randomUUID();
  const input = {
    userId: user.id,
    batchId,
    deviceId: 'dev-1',
    fingerprint: 'a'.repeat(64),
    mutations: [{ objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: { x: 1 } }],
  };

  const first = await repo.pushSyncBatch!(input);
  const second = await repo.pushSyncBatch!(input);
  assert.equal(first.replayed, false);
  assert.equal(second.replayed, true, '第二次应识别为重放');
  assert.deepEqual(second.items, first.items);

  const pull = await repo.pullSyncChanges!(user.id, 0, 100);
  assert.equal(pull.changes.length, 1, '重放不应产生新变更');
});

test('版本冲突：expected_version 落后 → conflict 并回传服务端版本', async () => {
  const user = await repo.createUser('sync_conflict', PASSWORD);
  const objId = taskId();
  await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'b'.repeat(64),
    mutations: [{ objectType: 'task', objectId: objId, op: 'create', expectedVersion: null, payload: { v: 1 } }],
  });

  const stale = await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'c'.repeat(64),
    mutations: [{ objectType: 'task', objectId: objId, op: 'update', expectedVersion: 99, payload: { v: 2 } }],
  });
  assert.equal(stale.status, 'rejected');
  assert.equal(stale.items[0]!.result, 'conflict');
  assert.equal(stale.items[0]!.reason, 'version_mismatch');
  assert.equal(stale.items[0]!.server_version, 1);

  const ok = await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'd'.repeat(64),
    mutations: [{ objectType: 'task', objectId: objId, op: 'update', expectedVersion: 1, payload: { v: 2 } }],
  });
  assert.equal(ok.status, 'applied');
  assert.equal(ok.items[0]!.server_version, 2);
});

test('未知对象类型逐项 rejected，不影响同批其他项', async () => {
  const user = await repo.createUser('sync_mixed', PASSWORD);
  const res = await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'e'.repeat(64),
    mutations: [
      { objectType: 'goal', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} },
      { objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} },
    ],
  });
  assert.equal(res.status, 'partial');
  assert.deepEqual(res.items.map((i) => i.result), ['rejected', 'applied']);
});

test('账户隔离：另一个用户拉不到本用户的变更', async () => {
  const a = await repo.createUser('sync_iso_a', PASSWORD);
  const b = await repo.createUser('sync_iso_b', PASSWORD);
  await repo.pushSyncBatch!({
    userId: a.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'f'.repeat(64),
    mutations: [{ objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} }],
  });
  const bPull = await repo.pullSyncChanges!(b.id, 0, 100);
  assert.equal(bPull.changes.length, 0, 'B 不应看到 A 的变更');
});

test('拉取游标：按 seq 升序、next_cursor 可继续', async () => {
  const user = await repo.createUser('sync_cursor', PASSWORD);
  await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: '1'.repeat(64),
    mutations: [
      { objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} },
      { objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} },
      { objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} },
    ],
  });

  const first = await repo.pullSyncChanges!(user.id, 0, 2);
  assert.equal(first.changes.length, 2);
  assert.equal(first.hasMore, true);
  const second = await repo.pullSyncChanges!(user.id, first.nextCursor, 2);
  assert.equal(second.changes.length, 1);
  assert.equal(second.hasMore, false);

  const ids = [...first.changes, ...second.changes].map((c) => c.objectId);
  assert.equal(new Set(ids).size, 3, '三页合计无重复');
});

test('P1 并发：同对象两个 expected_version=1 的更新只有一个成功', async () => {
  const user = await repo.createUser('sync_cas', PASSWORD);
  const objId = taskId();
  await repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: 'a'.repeat(64),
    mutations: [{ objectType: 'task', objectId: objId, op: 'create', expectedVersion: null, payload: { v: 0 } }],
  });

  const upd = (fp: string) => repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: fp.repeat(64),
    mutations: [{ objectType: 'task', objectId: objId, op: 'update', expectedVersion: 1, payload: { v: 1 } }],
  });
  const [a, b] = await Promise.all([upd('b'), upd('c')]);
  const applied = [a, b].filter((r) => r.items[0]!.result === 'applied').length;
  assert.equal(applied, 1, `并发同版本更新只应成功一次，实际 ${applied}`);

  // 库内版本必须严格单调：1 之后只允许一个 2
  const rows = await owner.query('SELECT version FROM sync_changes WHERE user_id=$1 AND object_id=$2 ORDER BY seq', [user.id, objId]);
  assert.deepEqual(rows.rows.map((r: { version: number }) => r.version), [1, 2]);
});

test('P1 并发：跨对象并发写入后，pull 能拿到全部变更（不漏项）', async () => {
  const user = await repo.createUser('sync_water', PASSWORD);
  const push = (fp: string) => repo.pushSyncBatch!({
    userId: user.id, batchId: randomUUID(), deviceId: 'd', fingerprint: fp.repeat(64),
    mutations: [{ objectType: 'task', objectId: taskId(), op: 'create', expectedVersion: null, payload: {} }],
  });
  await Promise.all([push('1'), push('2'), push('3'), push('4')]);

  const all = await repo.pullSyncChanges!(user.id, 0, 100);
  assert.equal(all.changes.length, 4, '四次并发写入必须都能被拉到');
  const seqs = all.changes.map((c) => c.seq);
  assert.deepEqual([...seqs].sort((x, y) => x - y), seqs, 'seq 必须升序');
  assert.equal(new Set(seqs).size, 4, 'seq 不可重复');
});