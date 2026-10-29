import { Pool, type PoolClient } from 'pg';
import { createHash, randomUUID } from 'node:crypto';
import { ApiError } from '../lib/security.ts';
import type { AiConfig } from './config.ts';
import { type AiInput, tokenReservation, type ModelReply } from './provider.ts';
import {cumulativeCharge,CUMULATIVE_LEDGER_REASON} from './billing.ts';
export interface AiRow {id:string;user_id:string;client_request_id:string;fingerprint:string;mode:string;status:string;points:number;response_text:string|null;error_code:string|null;usage_tokens:number|null;created_at:Date;tokens_per_point:number;billing_strategy?:'per_request'|'cumulative_tokens';charged_points:number|null;input_tokens:number|null;output_tokens:number|null;continuation:boolean;compacted:boolean;proposal:{changes:unknown[]}|null}
export class AiStore {
 readonly pool:Pool;
 constructor(url:string){this.pool=new Pool({connectionString:url,max:2,idleTimeoutMillis:10000,connectionTimeoutMillis:3000});
 this.pool.on('error',()=>{/* health probe and API report sanitized dependency failure */});}
 async transaction<T>(fn:(db:PoolClient)=>Promise<T>):Promise<T>{
 const db=await this.pool.connect();try{await db.query('BEGIN');const result=await fn(db);await db.query('COMMIT');return result;}
 catch(e){await db.query('ROLLBACK');throw e;}finally{db.release();}}
 async authenticate(raw:string):Promise<string> {
 if(!/^[A-Za-z0-9_-]{43}$/.test(raw))throw new ApiError('UNAUTHENTICATED','请先登录');
 const hash=createHash('sha256').update(raw).digest('hex');
 const r=await this.pool.query(`SELECT s.user_id FROM auth_sessions s JOIN users u ON u.id=s.user_id
 WHERE s.access_hash=$1 AND s.revoked_at IS NULL AND s.access_expires_at>now() AND u.status='active'`,[hash]);
 if(!r.rowCount)throw new ApiError('UNAUTHENTICATED','登录已失效');return r.rows[0].user_id;
 }
 async get(userId:string,clientId:string):Promise<AiRow|null>{
 const r=await this.pool.query<AiRow>('SELECT * FROM ai_requests WHERE user_id=$1 AND client_request_id=$2',[userId,clientId]);return r.rows[0]??null;}
 async reserve(userId:string,input:AiInput,config:AiConfig):Promise<{row:AiRow;fresh:boolean}> {
 const fingerprint=input.sourceFingerprint??createHash('sha256').update(JSON.stringify(input)).digest('hex');
 return this.transaction(async db=>{
 // Serialize admission checks across both workers and future API processes.
 await db.query('SELECT pg_advisory_xact_lock(28102301)');
 const old=await db.query<AiRow>('SELECT * FROM ai_requests WHERE user_id=$1 AND client_request_id=$2',[userId,input.client_request_id]);
 if(old.rows[0]){if(old.rows[0].fingerprint!==fingerprint)throw new ApiError('CONFLICT','相同请求编号的内容不同');return {row:old.rows[0],fresh:false};}
 const limits=await db.query(`SELECT count(*)::int AS calls, count(*) FILTER(WHERE user_id=$1)::int AS user_calls,
 COALESCE(sum(CASE WHEN status IN ('failed','succeeded') THEN COALESCE(usage_tokens,0) ELSE budget_tokens END),0)::int AS tokens,
 COALESCE(sum(CASE WHEN status IN ('failed','succeeded') THEN COALESCE(usage_tokens,0) ELSE budget_tokens END) FILTER(WHERE user_id=$1),0)::int AS user_tokens
 FROM ai_requests WHERE created_at >= (date_trunc('day',now() AT TIME ZONE $2) AT TIME ZONE $2)`,[userId,config.businessTimezone]);
 const active=await db.query("SELECT count(*)::int AS n FROM ai_requests WHERE status IN ('reserved','running')");
 const recent=await db.query('SELECT count(*)::int AS n FROM ai_requests WHERE user_id=$1 AND created_at>now()-interval \'1 minute\'',[userId]);
 const q=limits.rows[0];const budget=tokenReservation(input,config.maxOutput);
 if(q.calls>=config.globalDailyCalls||q.user_calls>=config.userDailyCalls||q.tokens+budget>config.globalDailyTokens||
 q.user_tokens+budget>config.userDailyTokens||active.rows[0].n>=2||recent.rows[0].n>=3)
 throw new ApiError('RATE_LIMITED','已达到本次演示的调用或用量限制');
 const rate=config.tokensPerPoint??1000;const points=Math.max(1,Math.ceil(budget/rate));
 const wallet=await db.query('UPDATE point_wallets SET available=available-$2,reserved=reserved+$2,updated_at=now() WHERE user_id=$1 AND available>=$2 RETURNING available,reserved',[userId,points]);
 if(!wallet.rowCount)throw new ApiError('INSUFFICIENT_POINTS',`请先签到领取积分；本次最多预占${points}积分，成功后按实际用量结算`,{required_points:points,tokens_per_point:rate});
 const id=randomUUID();const inserted=await db.query<AiRow>(`INSERT INTO ai_requests(id,user_id,client_request_id,fingerprint,mode,model_code,status,points,budget_tokens,tokens_per_point,compacted,billing_strategy)
 VALUES($1,$2,$3,$4,$5,$6,'reserved',$8,$7,$9,$10,$11) RETURNING *`,[id,userId,input.client_request_id,fingerprint,input.mode,input.model_code,budget,points,rate,input.compacted??false,config.billingStrategy??'per_request']);
 await this.ledger(db,userId,id,'reserve',-points,points,wallet.rows[0],config.billingStrategy==='cumulative_tokens'?CUMULATIVE_LEDGER_REASON:'ai-demo');return {row:inserted.rows[0]!,fresh:true};
 });
 }
 async running(id:string):Promise<void>{await this.pool.query("UPDATE ai_requests SET status='running',updated_at=now() WHERE id=$1 AND status='reserved'",[id]);}
 private async ledger(db:PoolClient,user:string,id:string,type:string,a:number,r:number,w:{available:number;reserved:number},reason='ai-demo'):Promise<void>{
 await db.query(`INSERT INTO point_ledger(id,user_id,event_id,request_id,type,available_delta,reserved_delta,balance_after,reserved_after,reason)
 VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10)`,[randomUUID(),user,`ai:${id}:${type}`,id,type,a,r,w.available,w.reserved,reason]);}
 async finish(id:string,outcome:'succeeded'|'failed'|'unknown',reply?:ModelReply):Promise<AiRow>{
 return this.transaction(async db=>{
 const owner=await db.query<{user_id:string}>('SELECT user_id FROM ai_requests WHERE id=$1',[id]);
 if(!owner.rows[0])throw new Error('Missing AI request');
 // Serialize all finishes for the same wallet before reading the cumulative bucket.
 await db.query('SELECT user_id FROM point_wallets WHERE user_id=$1 FOR UPDATE',[owner.rows[0].user_id]);
 const r=await db.query<AiRow>('SELECT * FROM ai_requests WHERE id=$1 FOR UPDATE',[id]);const row=r.rows[0];if(!row)throw new Error('Missing AI request');
 if(!['reserved','running'].includes(row.status))return row;
 const cumulative=row.billing_strategy==='cumulative_tokens';
 let charged=0;
 if(outcome==='succeeded'){
  if(cumulative){
   const previous=await db.query<{tokens:string;charged:string}>(`SELECT COALESCE(sum(usage_tokens),0)::text AS tokens,COALESCE(sum(charged_points),0)::text AS charged
    FROM ai_requests WHERE user_id=$1 AND tokens_per_point=$2 AND billing_strategy='cumulative_tokens' AND status='succeeded'`,[row.user_id,row.tokens_per_point]);
   charged=cumulativeCharge(BigInt(previous.rows[0]!.tokens),BigInt(previous.rows[0]!.charged),reply!.tokens,row.tokens_per_point);
   if(charged>row.points)throw new Error('Usage exceeded reservation');
  }else charged=Math.min(row.points,Math.max(1,Math.ceil(reply!.tokens/row.tokens_per_point)));
 }
 const refund=row.points-charged;
 if(outcome!=='unknown'){
 const wallet=await db.query(`UPDATE point_wallets SET reserved=reserved-$2,available=available+$3,updated_at=now()
 WHERE user_id=$1 AND reserved>=$2 RETURNING available,reserved`,[row.user_id,row.points,refund]);
 if(!wallet.rowCount)throw new Error('Reservation invariant violated');
 await this.ledger(db,row.user_id,id,outcome==='failed'?'release':'settle',refund,-row.points,wallet.rows[0],cumulative?CUMULATIVE_LEDGER_REASON:'ai-demo');}
 const updated=await db.query<AiRow>(`UPDATE ai_requests SET status=$2,response_text=$3,usage_tokens=$4,error_code=$5,charged_points=$6,input_tokens=$7,output_tokens=$8,continuation=$9,proposal=$10,updated_at=now() WHERE id=$1 RETURNING *`,
 [id,outcome,reply?.text??null,reply?.tokens??null,outcome==='succeeded'?null:outcome==='failed'?(reply?.usable===false?'MODEL_OUTPUT_INVALID':'UPSTREAM_UNAVAILABLE'):'REQUEST_PENDING',outcome==='unknown'?null:charged,reply?.inputTokens??null,reply?.outputTokens??null,reply?.continuation??false,reply?.changes?{changes:reply.changes}:null]);return updated.rows[0]!;
 });}

 async vip(userId:string):Promise<boolean>{
 const r=await this.pool.query("SELECT EXISTS(SELECT 1 FROM entitlements WHERE user_id=$1 AND kind='vip' AND expires_at>now()) AS active",[userId]);return r.rows[0].active;}
 async usage(userId:string,timeZone:string,rate=1000){
 return this.transaction(async db=>{
 const wallet=await db.query('SELECT available,reserved FROM point_wallets WHERE user_id=$1 FOR SHARE',[userId]);
 const r=await db.query(`SELECT count(*)::int AS requests,count(*) FILTER(WHERE status='succeeded')::int AS completed,
 count(*) FILTER(WHERE status IN ('reserved','running','unknown'))::int AS pending,
 COALESCE(sum(usage_tokens),0)::int AS total_tokens,
 COALESCE(sum(input_tokens),0)::int AS input_tokens,
 COALESCE(sum(output_tokens),0)::int AS output_tokens,
 COALESCE(sum(charged_points),0)::int AS charged_points
 FROM ai_requests WHERE user_id=$1 AND created_at >= (date_trunc('day',now() AT TIME ZONE $2) AT TIME ZONE $2)`,[userId,timeZone]);
 const bucket=await db.query(`SELECT COALESCE(sum(usage_tokens),0)::text AS billing_bucket_tokens,COALESCE(sum(charged_points),0)::text AS billing_bucket_points
 FROM ai_requests WHERE user_id=$1 AND tokens_per_point=$2 AND billing_strategy='cumulative_tokens' AND status='succeeded'`,[userId,rate]);
 return {...r.rows[0],...bucket.rows[0],available_points:wallet.rows[0]?.available??0,reserved_points:wallet.rows[0]?.reserved??0};
 });
 }
 async recover():Promise<void>{await this.pool.query("UPDATE ai_requests SET status='unknown',error_code='REQUEST_PENDING',updated_at=now() WHERE status IN ('reserved','running')");}
 async ready():Promise<void>{await this.pool.query('SELECT 1 FROM ai_requests LIMIT 1');}
 async close():Promise<void>{await this.pool.end();}
}
