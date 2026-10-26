import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
export function changedHistoricalSql(diff:string):string[]{
 return diff.split(/\r?\n/).filter(line=>line&&line[0]!=='A'&&/\/migration\.sql$/.test(line));
}
export function main():void{
 const base=process.env.MIGRATION_BASE_SHA?.trim()||undefined;
 if(base&&!/^[0-9a-f]{40}$/.test(base))throw new Error('INVALID_MIGRATION_BASE');
 const root=fileURLToPath(new URL('../../',import.meta.url));
 const branch=base??spawnSync('git',['merge-base','HEAD','origin/main'],{cwd:root,encoding:'utf8'}).stdout.trim();
 if(!/^[0-9a-f]{40}$/.test(branch))throw new Error('MIGRATION_BASE_UNAVAILABLE');
 const result=spawnSync('git',['diff','--no-renames','--name-status',branch,'--','server/prisma/migrations/*/migration.sql'],{cwd:root,encoding:'utf8'});
 if(result.status!==0)throw new Error('MIGRATION_HISTORY_READ_FAILED');
 const changes=changedHistoricalSql(result.stdout);
 if(changes.length)throw new Error('HISTORICAL_MIGRATION_CHANGED: '+changes.join(', '));
 console.log('Historical migration SQL unchanged relative to main/base.');
}
if(process.argv[1]&&fileURLToPath(import.meta.url)===process.argv[1])try{main();}catch(e){console.error((e as Error).message);process.exitCode=1;}
