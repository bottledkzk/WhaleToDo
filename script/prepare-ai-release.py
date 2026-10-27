"""Package a committed gateway source and only its required append-only migrations.

This command does not connect to a host, read credentials or deploy anything.
"""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

parser = argparse.ArgumentParser()
parser.add_argument('--output', required=True, type=Path)
args = parser.parse_args()
repo = Path(__file__).resolve().parent.parent
if args.output.exists():
    raise SystemExit('OUTPUT_ALREADY_EXISTS')
def git(*params):
    return subprocess.check_output(['git', '-C', str(repo), *params])
commit = git('rev-parse', 'HEAD').decode().strip()
if git('status', '--porcelain', '--untracked-files=normal'):
    raise SystemExit('COMMIT_SOURCE_FIRST')
all_paths = git('ls-tree', '-r', '--name-only', 'HEAD').decode().splitlines()
cutoff = '202610240001_agent_usage'
files = {}
for path in all_paths:
    if (path.startswith('server/src/') or path in ['server/package.json', 'server/package-lock.json',
        'server/tools/database-guard.ts', 'server/prisma/schema.prisma', 'server/prisma/migrations/migration_lock.toml',
        'script/backup-database.sh', 'script/verify-restore.sh', 'env/systemd/whaletodo-ai.service']):
        files[path] = git('show', 'HEAD:' + path)
    if path.startswith('server/prisma/migrations/') and path.endswith('/migration.sql'):
        name = path.split('/')[-2]
        if name <= cutoff:
            files[path] = git('show', 'HEAD:' + path)
checksums = json.loads(git('show', 'HEAD:server/prisma/migrations/checksums.json'))
checksums = [m for m in checksums if m['name'] <= cutoff]
if len(checksums) != 5 or checksums[-1]['name'] != cutoff:
    raise SystemExit('UNEXPECTED_GATEWAY_MIGRATION_BOUNDARY')
for m in checksums:
    raw = files['server/prisma/migrations/' + m['name'] + '/migration.sql']
    if hashlib.sha256(raw).hexdigest() != m['checksum']:
        raise SystemExit('MIGRATION_CHECKSUM_MISMATCH')
files['server/prisma/migrations/checksums.json'] = (json.dumps(checksums, indent=2) + '\n').encode()
files['env/postgresql/ai-usage-grant.sql'] = b'GRANT SELECT(user_id,kind,expires_at) ON whaletodo.entitlements TO whaletodo_ai;\n'
files['DEPLOYMENT.md'] = git('show', 'HEAD:doc/Bottledkzk/2026-10-26-网关与在线验收接续.md')
manifest = {'source_commit': commit, 'gateway_prompt': 'agent-20261025-v3',
            'migration_boundary': cutoff, 'migration_count': 5,
            'billing_email_migration_included': False, 'deployed': False,
            'files': {p: hashlib.sha256(data).hexdigest() for p, data in sorted(files.items())}}
files['manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
args.output.parent.mkdir(parents=True, exist_ok=True)
with tarfile.open(args.output, 'w:gz') as archive:
    for name, data in sorted(files.items()):
        info = tarfile.TarInfo(name)
        info.size = len(data)
        info.mode = 0o755 if name.endswith('.sh') else 0o644
        info.mtime = 0
        archive.addfile(info, io.BytesIO(data))
print(json.dumps({'archive': str(args.output), 'sha256': hashlib.sha256(args.output.read_bytes()).hexdigest(),
                  'source_commit': commit, 'files': len(files), 'deployed': False}))
