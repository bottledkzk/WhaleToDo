import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import {
  SYNC_OBJECT_TYPES, parsePushBody, decideMutation, batchStatus, isObjectType,
} from '../src/domain/sync.ts';
import type { SyncMutationInput } from '../src/domain/sync.ts';
import { ApiError } from '../src/lib/security.ts';

const m = (over: Partial<SyncMutationInput> = {}): SyncMutationInput => ({
  objectType: 'task', objectId: 'obj-1', op: 'create', expectedVersion: null, payload: { title: 'a' }, ...over,
});

describe('对象类型白名单（他确认的五种）', () => {
  test('包含 task/block/checkin/availability/settings', () => {
    assert.deepEqual([...SYNC_OBJECT_TYPES].sort(), ['availability', 'block', 'checkin', 'settings', 'task']);
  });

  test('未知类型不算合法类型', () => {
    assert.equal(isObjectType('goal'), false);
    assert.equal(isObjectType('task'), true);
  });

  test('未知类型逐项 rejected，不整批失败', () => {
    const r = decideMutation(null, m({ objectType: 'goal' }));
    assert.equal(r.result, 'rejected');
    assert.equal(r.reason, 'unsupported_object_type');
  });
});

describe('逐项判定', () => {
  test('create：对象不存在 → applied v1；payload 必需', () => {
    assert.deepEqual(decideMutation(null, m()), { result: 'applied', serverVersion: 1 });
    const noPayload = decideMutation(null, m({ payload: undefined }));
    assert.equal(noPayload.result, 'rejected');
    assert.equal(noPayload.reason, 'payload_required');
  });

  test('create：已存在且未删除 → conflict already_exists', () => {
    const r = decideMutation({ version: 3, deleted: false }, m());
    assert.equal(r.result, 'conflict');
    assert.equal(r.reason, 'already_exists');
    assert.equal(r.serverVersion, 3);
  });

  test('update：版本一致 → applied v+1；落后 → conflict 并回传服务端版本', () => {
    const ok = decideMutation({ version: 2, deleted: false }, m({ op: 'update', expectedVersion: 2, payload: { x: 1 } }));
    assert.deepEqual(ok, { result: 'applied', serverVersion: 3 });

    const stale = decideMutation({ version: 2, deleted: false }, m({ op: 'update', expectedVersion: 1, payload: { x: 1 } }));
    assert.equal(stale.result, 'conflict');
    assert.equal(stale.reason, 'version_mismatch');
    assert.equal(stale.serverVersion, 2);
  });

  test('update 缺 payload → rejected', () => {
    const r = decideMutation({ version: 1, deleted: false }, m({ op: 'update', expectedVersion: 1, payload: null }));
    assert.equal(r.result, 'rejected');
    assert.equal(r.reason, 'payload_required');
  });

  test('delete：版本一致 → applied；对象不存在 → conflict not_found', () => {
    const ok = decideMutation({ version: 4, deleted: false }, m({ op: 'delete', expectedVersion: 4 }));
    assert.deepEqual(ok, { result: 'applied', serverVersion: 5 });
    const missing = decideMutation(null, m({ op: 'delete', expectedVersion: 1 }));
    assert.equal(missing.result, 'conflict');
    assert.equal(missing.reason, 'not_found');
  });
});

describe('请求体校验', () => {
  test('缺 batch_id / device_id / mutations → 400', () => {
    for (const bad of [{}, { batch_id: 'b' }, { batch_id: 'b', device_id: 'd' }, { batch_id: 'b', device_id: 'd', mutations: [] }]) {
      assert.throws(() => parsePushBody(bad), (e: unknown) => e instanceof ApiError && e.code === 'VALIDATION_ERROR');
    }
  });

  test('单批超过 200 项 → 400', () => {
    const many = Array.from({ length: 201 }, (_, i) => ({ object_type: 'task', object_id: `o${i}`, op: 'create', payload: {} }));
    assert.throws(() => parsePushBody({ batch_id: '11111111-1111-4111-8111-111111111111', device_id: 'd', mutations: many }), (e: unknown) => e instanceof ApiError);
  });

  test('逐项缺字段 → 400 并指出第几项', () => {
    assert.throws(
      () => parsePushBody({ batch_id: '11111111-1111-4111-8111-111111111111', device_id: 'd', mutations: [{ object_type: 'task' }] }),
      /第 1 项/,
    );
  });
});

describe('批次结论', () => {
  const mk = (result: 'applied' | 'conflict' | 'rejected') => ({
    object_type: 'task', object_id: 'x', op: 'create', result, server_version: 1,
  });
  test('全成功 applied / 混合 partial / 全失败 rejected', () => {
    assert.equal(batchStatus([mk('applied'), mk('applied')]), 'applied');
    assert.equal(batchStatus([mk('applied'), mk('conflict')]), 'partial');
    assert.equal(batchStatus([mk('conflict'), mk('rejected')]), 'rejected');
    assert.equal(batchStatus([]), 'rejected');
  });
});
describe('输入前置校验（评审 #12 P2）', () => {
  const uid = '11111111-1111-4111-8111-111111111111';
  test('batch_id 非 UUID → 400', () => {
    assert.throws(() => parsePushBody({ batch_id: 'b', device_id: 'd', mutations: [{ object_type: 'task', object_id: uid, op: 'create', payload: {} }] }),
      /batch_id 需为 UUID/);
  });
  test('object_id 非 UUID → 400（不是 500）', () => {
    assert.throws(() => parsePushBody({ batch_id: uid, device_id: 'd', mutations: [{ object_type: 'task', object_id: 'not-uuid', op: 'create', payload: {} }] }),
      /object_id 需为 UUID/);
  });
  test('payload 超限 → 400', () => {
    const big = 'x'.repeat(70_000);
    assert.throws(() => parsePushBody({ batch_id: uid, device_id: 'd', mutations: [{ object_type: 'task', object_id: uid, op: 'create', payload: { big } }] }),
      /payload 超过大小上限/);
  });
  test('墓碑后重建：版本继续递增而非回到 1', () => {
    const r = decideMutation({ version: 5, deleted: true }, { objectType: 'task', objectId: uid, op: 'create', expectedVersion: null, payload: { a: 1 } });
    assert.deepEqual(r, { result: 'applied', serverVersion: 6 });
  });
});
describe('payload 字节边界（评审 #12 P2：多字节输入）', () => {
  const uid = '11111111-1111-4111-8111-111111111111';
  const push = (payload: unknown) => parsePushBody({
    batch_id: uid, device_id: 'd',
    mutations: [{ object_type: 'task', object_id: uid, op: 'create', payload }],
  });

  test('30000 个汉字（UTF-8 约 90KB）→ 400，不能只在字符数上通过', () => {
    const payload = { title: '中'.repeat(30_000) };
    assert.equal(JSON.stringify(payload).length < 60_000, true, '字符数确实低于阈值');
    assert.equal(Buffer.byteLength(JSON.stringify(payload), 'utf8') > 60_000, true, '字节数超阈值');
    assert.throws(() => push(payload), /payload 超过大小上限/);
  });

  test('emoji（4 字节/字符）同样按字节拒绝', () => {
    const payload = { title: '🐳'.repeat(20_000) };
    assert.throws(() => push(payload), /payload 超过大小上限/);
  });

  test('正常中文短文本可以通过', () => {
    assert.doesNotThrow(() => push({ title: '写周报', note: '今天完成同步接口' }));
  });

  test('32KB 前置守卫：31KB 通过、33KB 拒绝（jsonb 边界留给写库处兜底）', () => {
    assert.doesNotThrow(() => push({ note: 'x'.repeat(31_000) }));
    assert.throws(() => push({ note: 'x'.repeat(33_000) }), /payload 超过大小上限/);
  });
});