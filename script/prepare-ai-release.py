"""Package committed server sources; never connects, migrates, enables units or deploys."""
import argparse
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile

GATEWAY_BOUNDARY = '202610240001_agent_usage'
FULL_BOUNDARY = '202610250001_billing_email'


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def prepare(repo, output, profile='gateway'):
    if profile not in ('gateway', 'full'):
        raise ValueError('PROFILE_REFUSED')
    if output.exists():
        raise ValueError('OUTPUT_ALREADY_EXISTS')
    if git(repo, 'status', '--porcelain', '--untracked-files=normal'):
        raise ValueError('COMMIT_SOURCE_FIRST')
    commit = git(repo, 'rev-parse', 'HEAD').decode().strip()
    paths = git(repo, 'ls-tree', '-r', '--name-only', 'HEAD').decode().splitlines()
    cutoff = FULL_BOUNDARY if profile == 'full' else GATEWAY_BOUNDARY
    count = 6 if profile == 'full' else 5
    files = {}
    fixed = {'server/package.json', 'server/package-lock.json', 'server/tsconfig.json', 'server/prisma/schema.prisma',
             'server/prisma/migrations/migration_lock.toml', 'script/backup-database.sh',
             'script/verify-restore.sh', 'env/systemd/whaletodo-ai.service',
             'env/systemd/ai-limits.env.example'}
    if profile == 'full':
        fixed |= {'env/systemd/whaletodo-api.service', 'env/nginx/whaletodo-api-proxy.conf',
                  'env/postgresql/grants.sql'}
    for path in paths:
        if (path.startswith(('server/src/', 'server/tools/')) and path.endswith('.ts') or path in fixed or
                path.startswith('server/prisma/migrations/') and path.endswith('/migration.sql') and
                path.split('/')[-2] <= cutoff):
            files[path] = git(repo, 'show', 'HEAD:' + path)
    required = fixed | {'server/src/ai/main.ts', 'server/tools/database-guard.ts'}
    if profile == 'full':
        required |= {'server/src/main.ts', 'server/src/ops/api-main.ts', 'server/src/ops/api-config.ts'}
    if not required <= files.keys():
        raise ValueError('REQUIRED_RELEASE_FILE_MISSING')
    checksums = json.loads(git(repo, 'show', 'HEAD:server/prisma/migrations/checksums.json'))
    if profile == 'full' and (len(checksums) != 6 or any(p.split('/')[-2] > FULL_BOUNDARY
            for p in paths if p.startswith('server/prisma/migrations/') and p.endswith('/migration.sql'))):
        raise ValueError('FULL_RELEASE_BOUNDARY_CHANGED')
    checksums = [m for m in checksums if m['name'] <= cutoff]
    actual_names = sorted(p.split('/')[-2] for p in files if p.endswith('/migration.sql'))
    if len(checksums) != count or checksums[-1]['name'] != cutoff or actual_names != [m['name'] for m in checksums]:
        raise ValueError('UNEXPECTED_MIGRATION_BOUNDARY')
    for migration in checksums:
        raw = files['server/prisma/migrations/' + migration['name'] + '/migration.sql']
        if hashlib.sha256(raw).hexdigest() != migration['checksum']:
            raise ValueError('MIGRATION_CHECKSUM_MISMATCH')
    files['server/prisma/migrations/checksums.json'] = (json.dumps(checksums, indent=2) + '\n').encode()
    files['env/postgresql/ai-usage-grant.sql'] = b'GRANT SELECT(user_id,kind,expires_at) ON whaletodo.entitlements TO whaletodo_ai;\n'
    files['DEPLOYMENT.md'] = git(repo, 'show', 'HEAD:doc/Bottledkzk/2026-10-27-生产发布与在线验收.md')
    prompt = re.search(r"PROMPT_VERSION\s*=\s*'([^']+)'", files.get('server/src/ai/policy.ts', b'').decode())
    if not prompt:
        raise ValueError('PROMPT_VERSION_MISSING')
    manifest = {'schema_version': 1, 'source_commit': commit, 'profile': profile,
                'components': ['account_api', 'ai_gateway'] if profile == 'full' else ['ai_gateway'],
                'gateway_prompt': prompt.group(1), 'node_version': '24.15.0',
                'migration_boundary': cutoff, 'migration_count': count,
                'billing_email_migration_included': profile == 'full',
                'account_api_start_permitted': profile == 'full',
                'email_delivery_integrated': False, 'deployed': False, 'online_acceptance_passed': False,
                'files': {p: hashlib.sha256(data).hexdigest() for p, data in sorted(files.items())}}
    files['manifest.json'] = (json.dumps(manifest, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open('xb') as destination:
        with gzip.GzipFile(filename='', mode='wb', fileobj=destination, mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode='w', format=tarfile.USTAR_FORMAT) as archive:
                for name, data in sorted(files.items()):
                    info = tarfile.TarInfo(name)
                    info.size = len(data)
                    info.mode = 0o755 if name.endswith('.sh') else 0o644
                    info.mtime = 0
                    archive.addfile(info, io.BytesIO(data))
    with tarfile.open(output, 'r:gz') as archive:
        for name, digest in manifest['files'].items():
            if hashlib.sha256(archive.extractfile(name).read()).hexdigest() != digest:
                raise ValueError('ARCHIVE_READBACK_MISMATCH')
    return {'source_commit': commit, 'profile': profile, 'migration_count': count,
            'sha256': hashlib.sha256(output.read_bytes()).hexdigest(), 'files': len(files), 'deployed': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--profile', choices=['gateway', 'full'], default='gateway',
                        help='full includes six migrations and the account service; gateway cannot start the account API')
    args = parser.parse_args()
    print(json.dumps(prepare(Path(__file__).resolve().parent.parent, args.output, args.profile)))
