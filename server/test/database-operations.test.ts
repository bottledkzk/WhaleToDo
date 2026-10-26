import { test } from 'node:test';
import assert from 'node:assert/strict';
import { auditSql, auditDirectory } from '../tools/sql-policy.ts';
import { compareHistory, localMigrations, validateTarget, verifyBackup } from '../tools/database-guard.ts';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
test('SQL policy accepts placeholders and Prisma value tags',()=>{
 assert.deepEqual(auditSql('db.query("SELECT * FROM t WHERE id=$1", [input]); await tx.$queryRaw`SELECT * FROM t WHERE id=${input}`; await tx.$executeRaw(Prisma.sql`SELECT ${input}`);'),[]);
});
test('SQL policy rejects injection concatenation, interpolation, raw and query config',()=>{
 for(const src of ['db.query("SELECT " + input)', 'db.query(`SELECT ${input}`)', 'db.query(sql)', 'db.query({text:sql})',
 'db.$queryRawUnsafe("SELECT $1", input)', 'db["$executeRawUnsafe"](sql)', 'db.$queryRaw(sql)', 'Prisma.raw(input)'])assert.ok(auditSql(src).length,src);
});
test('all current runtime SQL obeys parameterization syntax policy',()=>assert.deepEqual(auditDirectory(fileURLToPath(new URL('../src/',import.meta.url))),[]));
test('migration history rejects edited, absent, pending failure and retroactive migrations',()=>{
 const a={name:'001',checksum:'a'},b={name:'002',checksum:'b'};
 const row={migration_name:'001',checksum:'a',finished_at:'done',rolled_back_at:null};
 assert.deepEqual(compareHistory([a,b],[row]),['002']);
 assert.deepEqual(compareHistory([{...a,crlf_checksum:'crlf'}],[{...row,checksum:'crlf'}]),[]);
 assert.throws(()=>compareHistory([a],[{...row,checksum:'changed'}]),/DRIFT/);
 assert.throws(()=>compareHistory([],[row]),/DRIFT/);
 assert.throws(()=>compareHistory([a],[{...row,finished_at:null}]),/UNRESOLVED/);
 assert.deepEqual(compareHistory([a],[{...row,finished_at:null,rolled_back_at:'rolledback'},row]),[]);
 assert.throws(()=>compareHistory([a,b],[{...row,migration_name:'002',checksum:'b'}]),/NON_APPEND/);
});
test('migration checksums cover every migration including unchanged historical files',()=>{
 const dir=fileURLToPath(new URL('../prisma/migrations/',import.meta.url));
 assert.deepEqual(localMigrations(dir),JSON.parse(readFileSync(dir+'/checksums.json','utf8')));
});
test('target guard rejects production lookalikes, remote hosts and missing schema',()=>{
 assert.equal(validateTarget('postgresql://x:y@127.0.0.1:5432/whaletodo?schema=whaletodo','whaletodo').pathname,'/whaletodo');
 for(const url of ['postgresql://x:y@internet/whaletodo?schema=whaletodo','postgresql://x:y@localhost/other?schema=whaletodo','postgresql://x:y@localhost/whaletodo','postgresql://x:y@localhost/whaletodo?schema=public'])assert.throws(()=>validateTarget(url,'whaletodo'));
 assert.throws(()=>verifyBackup('/tmp/not-a-backup.dump','whaletodo'),/PATH_REFUSED/);
});

// Base-history guard complements the editable local checksum manifest.
import {changedHistoricalSql} from '../tools/check-migration-history.ts';
test('main migration history rejects edits/deletes while accepting additive SQL',()=>{
 assert.deepEqual(changedHistoricalSql('A\tserver/prisma/migrations/new/migration.sql\nM\tserver/prisma/migrations/checksums.json'),[]);
 assert.equal(changedHistoricalSql('M\tserver/prisma/migrations/old/migration.sql\nD\tserver/prisma/migrations/older/migration.sql').length,2);
});
