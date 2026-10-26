// 同步的纯逻辑：对象类型白名单、逐项判定、批次结论、拉取游标。
// 依据 Bottledkzk 2026-10-25 评审确认：
//  - 首版 object_type 仅 task / block / checkin / availability / settings，未知类型拒绝
//  - expected_version 是服务端对象版本；落后即 conflict 并回传 server_version，不静默覆盖
//  - 手动块的 locked 与版本/删除标记原样往返，服务端不解释业务字段
import { ApiError } from '../lib/security.ts';

export const SYNC_OBJECT_TYPES = ['task', 'block', 'checkin', 'availability', 'settings'] as const;
export type SyncObjectType = (typeof SYNC_OBJECT_TYPES)[number];

export const SYNC_OPS = ['create', 'update', 'delete'] as const;
export type SyncOp = (typeof SYNC_OPS)[number];

export type SyncResultKind = 'applied' | 'conflict' | 'rejected';

export interface SyncMutationInput {
  objectType: string;
  objectId: string;
  op: string;
  expectedVersion: number | null;
  payload: unknown;
}

export interface SyncMutationResult {
  object_type: string;
  object_id: string;
  op: string;
  result: SyncResultKind;
  server_version: number | null;
  reason?: string;
}

export interface CurrentObject {
  version: number;
  deleted: boolean;
}

const MAX_BATCH_MUTATIONS = 200;
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_PAYLOAD_BYTES = 60_000;   // 表上约束 64KiB，留余量

/** 批次指纹不一致（同 batch_id 异体）：路由映射为 409，不覆盖首次回执 */
export class SyncBatchConflict extends Error {
  constructor() {
    super('SYNC_BATCH_CONFLICT');
    this.name = 'SyncBatchConflict';
  }
}

export function isUuid(value: string): boolean {
  return UUID_RE.test(value);
}

export function isObjectType(value: string): value is SyncObjectType {
  return (SYNC_OBJECT_TYPES as readonly string[]).includes(value);
}

export function isSyncOp(value: string): value is SyncOp {
  return (SYNC_OPS as readonly string[]).includes(value);
}

/** 校验 push 请求体：任何结构性问题都是 400，而不是逐项 rejected */
export function parsePushBody(body: unknown): {
  batchId: string;
  deviceId: string;
  mutations: SyncMutationInput[];
} {
  const b = (body ?? {}) as Record<string, unknown>;
  const batchId = String(b.batch_id ?? '').trim();
  const deviceId = String(b.device_id ?? '').trim();
  if (!batchId || !isUuid(batchId)) throw new ApiError('VALIDATION_ERROR', 'batch_id 需为 UUID');
  if (!deviceId || deviceId.length > 64) throw new ApiError('VALIDATION_ERROR', 'device_id 缺失或过长');
  if (!Array.isArray(b.mutations) || b.mutations.length === 0) {
    throw new ApiError('VALIDATION_ERROR', 'mutations 不能为空');
  }
  if (b.mutations.length > MAX_BATCH_MUTATIONS) {
    throw new ApiError('VALIDATION_ERROR', `单批最多 ${MAX_BATCH_MUTATIONS} 项`);
  }

  const mutations = b.mutations.map((raw, i) => {
    const m = (raw ?? {}) as Record<string, unknown>;    if (!m.object_type || !m.object_id || !m.op) {
      throw new ApiError('VALIDATION_ERROR', `第 ${i + 1} 项缺少 object_type/object_id/op`);
    }
    if (m.object_type && typeof m.object_type === 'string' && !isObjectType(m.object_type)) {
      // 未知类型：逐项 rejected，但要先于任何 UUID/数据库校验
      return {
        objectType: String(m.object_type),
        objectId: String(m.object_id ?? ''),
        op: String(m.op ?? ''),
        expectedVersion: typeof m.expected_version === 'number' ? m.expected_version : null,
        payload: m.payload,
      } satisfies SyncMutationInput;
    }
    if (typeof m.object_id !== 'string' || !isUuid(m.object_id)) {
      throw new ApiError('VALIDATION_ERROR', `第 ${i + 1} 项 object_id 需为 UUID`);
    }
    if (m.payload !== undefined && m.payload !== null && JSON.stringify(m.payload).length > MAX_PAYLOAD_BYTES) {
      throw new ApiError('VALIDATION_ERROR', `第 ${i + 1} 项 payload 超过大小上限`);
    }

    return {
      objectType: String(m.object_type),
      objectId: String(m.object_id),
      op: String(m.op),
      expectedVersion: typeof m.expected_version === 'number' ? m.expected_version : null,
      payload: m.payload,
    } satisfies SyncMutationInput;
  });
  return { batchId, deviceId, mutations };
}

/**
 * 判定单条变更：
 *  - 未知 object_type / op → rejected（只影响该项，不整批失败）
 *  - create 但对象已存在（且未删除）→ conflict
 *  - update/delete 需要 expected_version 与当前版本一致，否则 conflict 并回传当前版本
 *  - delete 已删除视作 applied（幂等）
 */
export function decideMutation(
  current: CurrentObject | null,
  m: SyncMutationInput,
): { result: SyncResultKind; serverVersion: number | null; reason?: string } {
  if (!isObjectType(m.objectType)) return { result: 'rejected', serverVersion: null, reason: 'unsupported_object_type' };
  if (!isSyncOp(m.op)) return { result: 'rejected', serverVersion: null, reason: 'unsupported_op' };
  if (!m.objectId || m.objectId.length > 64) return { result: 'rejected', serverVersion: null, reason: 'invalid_object_id' };

  if (m.op === 'create') {
    if (current && !current.deleted) {
      return { result: 'conflict', serverVersion: current.version, reason: 'already_exists' };
    }
    if (m.payload === undefined || m.payload === null) {
      return { result: 'rejected', serverVersion: null, reason: 'payload_required' };
    }
    // 墓碑之后重建：版本必须继续递增，否则旧离线 update(expected=1) 会覆盖新内容
    return { result: 'applied', serverVersion: (current?.version ?? 0) + 1 };
  }

  if (!current || current.deleted) {
    return { result: 'conflict', serverVersion: current?.version ?? null, reason: 'not_found' };
  }
  if (m.expectedVersion === null || m.expectedVersion !== current.version) {
    return { result: 'conflict', serverVersion: current.version, reason: 'version_mismatch' };
  }
  if (m.op === 'update' && (m.payload === undefined || m.payload === null)) {
    return { result: 'rejected', serverVersion: current.version, reason: 'payload_required' };
  }
  return { result: 'applied', serverVersion: current.version + 1 };
}

/** 批次结论：全 applied → applied；有成功也有失败 → partial；全失败 → rejected */
export function batchStatus(results: SyncMutationResult[]): 'applied' | 'partial' | 'rejected' {
  if (results.length === 0) return 'rejected';
  const applied = results.filter((r) => r.result === 'applied').length;
  if (applied === results.length) return 'applied';
  if (applied === 0) return 'rejected';
  return 'partial';
}
