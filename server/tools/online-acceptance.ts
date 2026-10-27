import { createHash, randomUUID } from 'node:crypto';
import { closeSync, existsSync, fsyncSync, openSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PROMPT_VERSION, parseDiary } from '../src/ai/policy.ts';
import { prepareInput, tokenReservation, type AiInput } from '../src/ai/provider.ts';

type Wallet = { available: number; reserved: number };
type Entry = { mode: 'diary' | 'share_copy'; id: string; payload_sha256: string; post_attempted: boolean;
  state: 'prepared' | 'unknown' | 'succeeded' | 'failed' | 'not_accepted'; http_status?: number;
  usage_tokens?: number; charged_points?: number; output_sha256?: string; output_chars?: number; structure_checked?: boolean };
type Receipt = { schema_version: 1; kind: 'synthetic_http_acceptance'; base_url: string; declared_source_commit: string;
  owner_sha256: string; prompt_version: string; max_model_requests: 2; max_output: number; tokens_per_point: number;
  wallet_before: Wallet; wallet_after?: Wallet; entries: Entry[]; status: 'running' | 'pending' | 'failed' | 'passed';
  app_acceptance: 'not_tested'; human_quality_review: 'pending'; updated_at: string };
export type AcceptanceOptions = { baseUrl: string; token: string; sourceCommit: string; maxOutput?: number;
  receipt?: string; action: 'preflight' | 'execute' | 'resume' };
type HttpResult = { status: number; body: any };
const digest = (value: string) => createHash('sha256').update(value).digest('hex');
function refused(code: string): never { throw new Error(code); }
function natural(value: unknown): value is number { return Number.isSafeInteger(value) && Number(value) >= 0; }
function payload(entry: Pick<Entry, 'mode' | 'id'>): AiInput {
  return { client_request_id: entry.id, mode: entry.mode, model_code: 'deepseek-default',
    prompt: entry.mode === 'diary' ? '只根据所附合成记录，写一段不超过80字的第一人称日记。' : '只根据所附合成记录，写一句不超过50字的分享配文。',
    context: '这是隔离验收的合成记录，不代表真实经历：完成了20分钟阅读，整理了书桌。',
    history: [], edit_consent: false, share_style: 'minimal' };
}
function save(path: string, receipt: Receipt, initial = false) {
  receipt.updated_at = new Date().toISOString();
  const content = JSON.stringify(receipt, null, 2) + '\n';
  const target = initial ? path : join(dirname(path), `.acceptance-${randomUUID()}.tmp`);
  const fd = openSync(target, 'wx', 0o600);
  try { writeFileSync(fd, content); fsyncSync(fd); } finally { closeSync(fd); }
  if (!initial) {
    try { renameSync(target, path); } catch (error) { unlinkSync(target); throw error; }
  }
}

/** At most two deliberate POST attempts. An attempted request is queried, never re-posted. */
export async function runAcceptance(options: AcceptanceOptions, fetcher: typeof fetch = fetch) {
  const base = new URL(options.baseUrl);
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash ||
      base.pathname.replace(/\/$/, '') !== '/api/v1') refused('ACCEPTANCE_BASE_URL_REFUSED');
  if (!/^[a-f0-9]{40}$/.test(options.sourceCommit) || !/^[A-Za-z0-9_-]{43}$/.test(options.token)) refused('ACCEPTANCE_IDENTITY_CONFIG_REFUSED');
  const baseUrl = base.toString().replace(/\/$/, '');
  const maxOutput = options.maxOutput ?? 2048;
  if (!Number.isSafeInteger(maxOutput) || maxOutput < 32 || maxOutput > 4096) refused('ACCEPTANCE_OUTPUT_LIMIT_REFUSED');
  let lock: number | undefined;
  const receiptPath = options.receipt;
  if (options.action !== 'preflight') {
    if (!receiptPath) refused('ACCEPTANCE_RECEIPT_REQUIRED');
    // A crash leaves a lock for operator inspection; never guess whether another process still runs.
    lock = openSync(receiptPath + '.lock', 'wx', 0o600);
    writeFileSync(lock, JSON.stringify({ pid: process.pid, created_at: new Date().toISOString() }));
  }
  try {
    async function http(path: string, authenticated = true, input?: AiInput): Promise<HttpResult> {
      try {
        const response = await fetcher(baseUrl + path, { method: input ? 'POST' : 'GET', redirect: 'error',
          headers: { Accept: 'application/json', ...(authenticated ? { Authorization: `Bearer ${options.token}` } : {}),
            ...(input ? { 'Content-Type': 'application/json' } : {}) },
          ...(input ? { body: JSON.stringify(input) } : {}), signal: AbortSignal.timeout(60_000) });
        const reader = response.body?.getReader();
        if (!reader) return { status: response.status, body: null };
        const chunks: Uint8Array[] = []; let bytes = 0;
        try {
          while (true) {
            const chunk = await reader.read(); if (chunk.done) break;
            bytes += chunk.value.length;
            if (bytes > 262144) { await reader.cancel(); return { status: response.status, body: null }; }
            chunks.push(chunk.value);
          }
        } finally { reader.releaseLock(); }
        const text = Buffer.concat(chunks).toString('utf8');
        let body: unknown; try { body = JSON.parse(text); } catch { body = null; }
        return { status: response.status, body };
      } catch { return { status: 0, body: null }; }
    }
    async function data(path: string) {
      const response = await http(path);
      if (response.status !== 200 || !response.body?.data) refused('ACCEPTANCE_PREFLIGHT_FAILED');
      return response.body.data;
    }
    const health = await data('/health');
    const aiHealth = await data('/ai/health');
    if (health.status !== 'ok' || aiHealth.status !== 'ok') refused('ACCEPTANCE_SERVICE_NOT_READY');
    if ((await http('/ai/models', false)).status !== 401) refused('ACCEPTANCE_ANONYMOUS_GATE_FAILED');
    const me = await data('/me');
    if (typeof me.id !== 'string' || !/^[a-f0-9-]{36}$/i.test(me.id)) refused('ACCEPTANCE_OWNER_INVALID');
    const models = await data('/ai/models');
    if (models.prompt_version !== PROMPT_VERSION || !natural(models.tokens_per_point) || models.tokens_per_point === 0 ||
        !Array.isArray(models.items) || !models.items.some((m: any) => m.code === 'deepseek-default')) refused('ACCEPTANCE_GATEWAY_VERSION_MISMATCH');
    const wallet = await data('/wallet') as Wallet;
    if (!natural(wallet.available) || !natural(wallet.reserved)) refused('ACCEPTANCE_WALLET_INVALID');
    const reservation = (['diary', 'share_copy'] as const).map(mode =>
      Math.ceil(tokenReservation(prepareInput(payload({ mode, id: '00000000-0000-4000-a000-000000000001' })), maxOutput) / models.tokens_per_point));
    const required = reservation.reduce((a, b) => a + b, 0);
    const owner = digest(me.id);
    if (options.action === 'preflight') {
      return { action: 'preflight', model_post_attempts: 0, ready: wallet.reserved === 0 && wallet.available >= required,
        prompt_version: models.prompt_version, owner_sha256: owner, wallet, required_reservation_points: required,
        declared_source_commit: options.sourceCommit, deployment_source_verified: false,
        app_acceptance: 'not_tested', email_delivery: 'not_tested' };
    }
    let receipt: Receipt;
    if (options.action === 'execute') {
      if (wallet.reserved !== 0 || wallet.available < required) refused('ACCEPTANCE_BALANCE_OR_EXISTING_RESERVATION_REFUSED');
      if (existsSync(receiptPath!)) refused('ACCEPTANCE_RECEIPT_EXISTS_USE_RESUME');
      receipt = { schema_version: 1, kind: 'synthetic_http_acceptance', base_url: baseUrl, declared_source_commit: options.sourceCommit,
        owner_sha256: owner, prompt_version: PROMPT_VERSION, max_model_requests: 2, max_output: maxOutput,
        tokens_per_point: models.tokens_per_point, wallet_before: wallet, status: 'running',
        app_acceptance: 'not_tested', human_quality_review: 'pending', updated_at: '',
        entries: (['diary', 'share_copy'] as const).map(mode => {
          const id = randomUUID(); return { mode, id, payload_sha256: digest(JSON.stringify(payload({ mode, id }))), post_attempted: false, state: 'prepared' };
        }) };
      save(receiptPath!, receipt, true);
    } else {
      receipt = JSON.parse(readFileSync(receiptPath!, 'utf8')) as Receipt;
      if (receipt.schema_version !== 1 || receipt.kind !== 'synthetic_http_acceptance' || receipt.base_url !== baseUrl ||
          receipt.declared_source_commit !== options.sourceCommit || receipt.owner_sha256 !== owner || receipt.prompt_version !== PROMPT_VERSION ||
          receipt.max_model_requests !== 2 || receipt.max_output !== maxOutput || receipt.tokens_per_point !== models.tokens_per_point ||
          !Array.isArray(receipt.entries) || receipt.entries.length !== 2) refused('ACCEPTANCE_RECEIPT_CONTEXT_MISMATCH');
      for (const [index, entry] of receipt.entries.entries()) {
        if (entry.mode !== ['diary', 'share_copy'][index] || !/^[a-f0-9-]{36}$/i.test(entry.id) ||
            entry.payload_sha256 !== digest(JSON.stringify(payload(entry))) || typeof entry.post_attempted !== 'boolean' ||
            !['prepared', 'unknown', 'succeeded', 'failed', 'not_accepted'].includes(entry.state) ||
            !entry.post_attempted && entry.state !== 'prepared') refused('ACCEPTANCE_RECEIPT_INVALID');
      }
      if (receipt.entries[0]!.id === receipt.entries[1]!.id) refused('ACCEPTANCE_RECEIPT_INVALID');
    }
    if (receipt.status === 'failed' || receipt.status === 'passed') return receipt;
    for (const entry of receipt.entries) {
      if (entry.state === 'succeeded') continue;
      if (entry.state === 'failed' || entry.state === 'not_accepted') { receipt.status = 'failed'; break; }
      const wasAttempted = entry.post_attempted;
      if (!wasAttempted) {
        // Persist ambiguity BEFORE sending. Crashing before send may require manual resolution, never a duplicate POST.
        entry.post_attempted = true; entry.state = 'unknown'; save(receiptPath!, receipt);
      }
      const response = wasAttempted ? await http('/ai/requests/' + entry.id) : await http('/ai/requests', true, payload(entry));
      entry.http_status = response.status;
      const view = response.body?.data;
      if (response.status >= 200 && response.status < 300 && view?.id === entry.id && view.mode === entry.mode && view.status === 'succeeded') {
        const text = view.text;
        const tokens = view.usage_tokens;
        const points = view.points_charged;
        if (typeof text !== 'string' || !text.trim() || !natural(tokens) || tokens === 0 || !natural(points) ||
            points !== Math.max(1, Math.ceil(tokens / models.tokens_per_point)) ||
            tokens > tokenReservation(prepareInput(payload(entry)), maxOutput) || view.continuation) {
          receipt.status = 'failed'; entry.state = 'succeeded'; entry.structure_checked = false; save(receiptPath!, receipt); break;
        }
        let valid = true;
        if (entry.mode === 'diary') {
          try { parseDiary(JSON.stringify({ diary: text })); } catch { valid = false; }
          if (text.includes('日记草稿暂不可用')) valid = false;
        }
        entry.state = 'succeeded'; entry.usage_tokens = tokens; entry.charged_points = points;
        entry.output_sha256 = digest(text); entry.output_chars = Array.from(text).length; entry.structure_checked = valid;
        if (!valid) { receipt.status = 'failed'; save(receiptPath!, receipt); break; }
      } else if (view?.id === entry.id && view.mode === entry.mode && view.status === 'failed') {
        entry.state = 'failed'; receipt.status = 'failed'; save(receiptPath!, receipt); break;
      } else if (!wasAttempted && [400, 401, 402, 403, 409, 413, 422, 429].includes(response.status)) {
        entry.state = 'not_accepted'; receipt.status = 'failed'; save(receiptPath!, receipt); break;
      } else {
        entry.state = 'unknown'; receipt.status = 'pending'; save(receiptPath!, receipt); break;
      }
      save(receiptPath!, receipt);
    }
    if (receipt.entries.every(e => e.state === 'succeeded' && e.structure_checked === true)) {
      const after = await data('/wallet') as Wallet;
      if (!natural(after.available) || !natural(after.reserved)) refused('ACCEPTANCE_WALLET_INVALID');
      receipt.wallet_after = after;
      const charged = receipt.entries.reduce((n, e) => n + (e.charged_points ?? 0), 0);
      receipt.status = after.reserved === receipt.wallet_before.reserved &&
        after.available === receipt.wallet_before.available - charged ? 'passed' : 'failed';
    }
    save(receiptPath!, receipt);
    return receipt;
  } finally {
    if (lock !== undefined) { closeSync(lock); unlinkSync(receiptPath! + '.lock'); }
  }
}

export async function main(args: string[]) {
  const values: Record<string, string> = {}; let action: AcceptanceOptions['action'] = 'preflight';
  for (let i = 0; i < args.length; i++) {
    const key = args[i]!;
    if (key === '--execute' || key === '--resume') {
      if (action !== 'preflight') refused('ACCEPTANCE_ARGUMENTS_REFUSED');
      action = key === '--execute' ? 'execute' : 'resume';
    } else if (['--base-url', '--token-file', '--source-commit', '--receipt', '--max-output'].includes(key) && args[i + 1] && !values[key]) {
      values[key] = args[++i]!;
    } else refused('ACCEPTANCE_ARGUMENTS_REFUSED');
  }
  const token = readFileSync(values['--token-file'] ?? '', 'utf8').trim();
  const result = await runAcceptance({ baseUrl: values['--base-url'] ?? '', token,
    sourceCommit: values['--source-commit'] ?? '', receipt: values['--receipt'], action,
    ...(values['--max-output'] ? { maxOutput: Number(values['--max-output']) } : {}) });
  console.log(JSON.stringify(result));
  if ('status' in result && result.status !== 'passed' || 'ready' in result && !result.ready) process.exitCode = 2;
}
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2)).catch(() => { console.error('ONLINE_ACCEPTANCE_STOPPED_INSPECT_PRIVATE_RECEIPT'); process.exitCode = 1; });
}
