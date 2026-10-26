import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { Pool } from 'pg';
export type Migration={name:string;checksum:string;crlf_checksum?:string};
function hashSql(sql:string,crlf=false):string{const lf=sql.replace(/\r\n/g,'\n');return createHash('sha256').update(crlf?lf.replace(/\n/g,'\r\n'):lf).digest('hex');}
export function localMigrations(dir:string):Migration[]{
 return readdirSync(dir,{withFileTypes:true}).filter(e=>e.isDirectory()).map(e=>({name:e.name,
 checksum:hashSql(readFileSync(join(dir,e.name,'migration.sql'),'utf8')),
 crlf_checksum:hashSql(readFileSync(join(dir,e.name,'migration.sql'),'utf8'),true)})).sort((a,b)=>a.name.localeCompare(b.name));
}
export function compareHistory(local:Migration[],rows:{migration_name:string;checksum:string;finished_at:unknown;rolled_back_at:unknown}[]):string[]{
 const applied=new Set<string>();
 for(const row of rows){
  if(row.rolled_back_at)continue;
  if(!row.finished_at)throw new Error('MIGRATION_UNRESOLVED');
  if(applied.has(row.migration_name))throw new Error('MIGRATION_DUPLICATE');
  const file=local.find(m=>m.name===row.migration_name);
  if(!file||![file.checksum,file.crlf_checksum].includes(row.checksum))throw new Error('MIGRATION_DRIFT');
  applied.add(row.migration_name);
 }
 const pending=local.filter(m=>!applied.has(m.name)).map(m=>m.name);
 if(pending.some(p=>[...applied].some(a=>a>p)))throw new Error('MIGRATION_NON_APPEND');
 return pending;
}
export function validateTarget(raw:string|undefined,expected:string):URL{
 if(!raw)throw new Error('DATABASE_URL_MISSING');
 let u:URL;try{u=new URL(raw);}catch{throw new Error('DATABASE_URL_INVALID');}
 if(!['postgres:','postgresql:'].includes(u.protocol)||!['127.0.0.1','localhost','[::1]'].includes(u.hostname)||
  decodeURIComponent(u.pathname)!=='/'+expected||!/^whaletodo(?:_verify|_ops_[a-z0-9_]{1,40})?$/.test(expected)||
  u.searchParams.get('schema')!=='whaletodo')throw new Error('DATABASE_TARGET_REFUSED');
 return u;
}
export function verifyBackup(path:string,db:string):void{
 if(!/^\/var\/backups\/whaletodo\/whaletodo-[0-9TZ]+-[0-9]+\.dump$/.test(path))throw new Error('BACKUP_PATH_REFUSED');
 const data=readFileSync(path);const meta=JSON.parse(readFileSync(path+'.json','utf8')) as {database:string;sha256:string;created_at:string};
 const age=Date.now()-Date.parse(meta.created_at);
 if(meta.database!==db||!Number.isFinite(age)||age< -60000||age>1800000||statSync(path).size===0||
  createHash('sha256').update(data).digest('hex')!==meta.sha256)throw new Error('BACKUP_INVALID_OR_STALE');
}
export async function main(args:string[]):Promise<void>{
 const [action,expected,backup,...extra]=args;
 if(!['plan','apply'].includes(action??'')||!expected||extra.length||action==='plan'&&backup)throw new Error('USAGE: plan <database> | apply <database> <recent-backup.dump>');
 validateTarget(process.env.DATABASE_URL,expected);
 const dir=fileURLToPath(new URL('../prisma/migrations/',import.meta.url));
 const local=localMigrations(dir);
 const lock=JSON.parse(readFileSync(join(dir,'checksums.json'),'utf8')) as Migration[];
 if(JSON.stringify(local)!==JSON.stringify(lock))throw new Error('LOCAL_MIGRATION_CHECKSUM_MISMATCH');
 const raw=new URL(process.env.DATABASE_URL!);raw.searchParams.delete('schema');

 function connect(){return new Pool({connectionString:raw.toString(),max:1,connectionTimeoutMillis:5000,statement_timeout:10000});}
 async function history():Promise<string[]>{
  const pool=connect();
  try{
   const role=(await pool.query('SELECT current_user AS name,rolsuper,rolcreaterole,rolcreatedb,rolbypassrls FROM pg_roles WHERE rolname=current_user')).rows[0];
   if(role.name!=='whaletodo_migrator'||role.rolsuper||role.rolcreaterole||role.rolcreatedb||role.rolbypassrls)throw new Error('MIGRATOR_ROLE_REFUSED');
   const exists=(await pool.query("SELECT to_regclass('whaletodo._prisma_migrations') IS NOT NULL AS exists")).rows[0].exists;
   const rows=exists?(await pool.query('SELECT migration_name,checksum,finished_at,rolled_back_at FROM whaletodo._prisma_migrations ORDER BY started_at')).rows:[];
   return compareHistory(local,rows);
  }finally{await pool.end();}
 }
 // Release the inspection pool BEFORE Prisma starts: migration role has only 2 connections.
 const pending=await history();
 console.log(JSON.stringify({database:expected,pending,local_migrations:local.length}));
 if(action==='apply'&&pending.length){
  if(!backup)throw new Error('BACKUP_REQUIRED');verifyBackup(backup,expected);
  if(process.platform!=='linux')throw new Error('APPLY_REQUIRES_LINUX_OPERATOR_HOST');
  const result=spawnSync('flock',['--nonblock',`/run/lock/whaletodo-migrate-${expected}.lock`,process.execPath,
   fileURLToPath(new URL('../node_modules/prisma/build/index.js',import.meta.url)),'migrate','deploy'],
   {cwd:fileURLToPath(new URL('../',import.meta.url)),env:process.env,encoding:'utf8',timeout:120000});
  // OS lock plus Prisma's own DB advisory migration lock; never expose CLI output/URLs.
  if(result.error||result.status!==0)throw new Error('MIGRATION_DEPLOY_FAILED_REVIEW_PRIVATE_DATABASE_HISTORY');
  if((await history()).length)throw new Error('MIGRATION_POSTCHECK_FAILED');
  console.log('Migrations applied; run administrator grants and restricted-role checks before application deployment.');
 }

}
if(process.argv[1]&&fileURLToPath(import.meta.url)===process.argv[1]){
 main(process.argv.slice(2)).catch(e=>{const msg=e instanceof Error?e.message:'';console.error(/^[A-Z_]+$/.test(msg)||msg.startsWith('USAGE:')?msg:'DATABASE_OPERATION_FAILED');process.exitCode=1;});
}
