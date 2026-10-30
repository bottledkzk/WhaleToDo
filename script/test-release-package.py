"""Offline release regression tests; every repository and asset is synthetic."""
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest
import zipfile

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('release_package', HERE / 'prepare-ai-release.py')
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class ReleasePackageTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='whaletodo-release-')
        self.root = Path(self.temp.name)
        self.repo = self.root / 'source'
        self.repo.mkdir()
        self.names = ['202610230001_initial', '202610230002_tenant_policies', '202610230003_schema_scope',
                      '202610230004_slot_default', '202610240001_agent_usage', '202610250001_billing_email', '202610290001_agent_billing', package.GATEWAY_BOUNDARY, package.FULL_BOUNDARY]
        paths = ['server/package.json', 'server/package-lock.json', 'server/tsconfig.json', 'server/prisma/schema.prisma',
                 'server/prisma/migrations/migration_lock.toml', 'script/backup-database.sh', 'script/verify-restore.sh',
                 'env/systemd/whaletodo-ai.service', 'env/systemd/ai-limits.env.example', 'env/systemd/whaletodo-api.service',
                 'env/nginx/whaletodo-api-proxy.conf', 'env/postgresql/grants.sql', 'server/src/main.ts',
                 'server/src/ai/main.ts', 'server/src/ops/api-main.ts', 'server/src/ops/api-config.ts',
                 'server/tools/database-guard.ts', 'doc/Bottledkzk/2026-10-27-生产发布与在线验收.md']
        for path in paths:
            self.write(path, 'synthetic\n')
        self.write('server/src/ai/policy.ts', "export const PROMPT_VERSION='synthetic-policy';\n")
        checksums = []
        for name in self.names:
            sql = b'-- synthetic migration\n'
            self.write('server/prisma/migrations/' + name + '/migration.sql', sql)
            checksums.append({'name': name, 'checksum': hashlib.sha256(sql).hexdigest()})
        self.write('server/prisma/migrations/checksums.json', json.dumps(checksums))
        for path in ['server/.env', 'server/node_modules/private.js', 'signing.key']:
            self.write(path, 'synthetic-not-for-package')
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)
        self.commit()

    def tearDown(self): self.temp.cleanup()
    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content if isinstance(content, bytes) else content.encode('utf-8'))
    def commit(self):
        subprocess.run(['git', '-C', str(self.repo), 'add', '.'], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(['git', '-C', str(self.repo), '-c', 'user.name=Synthetic Test', '-c', 'user.email=synthetic@example.invalid',
                        'commit', '-qm', 'Synthetic fixture'], check=True)

    def test_full_bundle_has_nine_migrations_without_secrets_or_dependencies(self):
        output = self.root / 'full.tar.gz'
        self.assertEqual(package.prepare(self.repo, output, 'full')['migration_count'], 9)
        with tarfile.open(output) as archive:
            manifest = json.load(archive.extractfile('manifest.json'))
            self.assertTrue(manifest['account_api_start_permitted'])
            self.assertFalse(manifest['deployed'])
            self.assertIn('server/src/ops/api-main.ts', archive.getnames())
            self.assertIn('server/tsconfig.json', archive.getnames())
            for name, digest in manifest['files'].items():
                self.assertNotIn('.env', name.replace('.env.example', '.example'))
                self.assertNotIn('node_modules', name)
                self.assertFalse(name.endswith('.key'))
                self.assertEqual(hashlib.sha256(archive.extractfile(name).read()).hexdigest(), digest)

    def test_gateway_boundary_and_byte_reproducibility(self):
        a, b = self.root / 'one.tar.gz', self.root / 'two.tar.gz'
        package.prepare(self.repo, a, 'gateway')
        package.prepare(self.repo, b, 'gateway')
        self.assertEqual(a.read_bytes(), b.read_bytes())
        with tarfile.open(a) as archive:
            manifest = json.load(archive.extractfile('manifest.json'))
            self.assertEqual(manifest['migration_count'], 8)
            self.assertFalse(manifest['account_api_start_permitted'])
            self.assertNotIn('env/systemd/whaletodo-api.service', archive.getnames())
        with self.assertRaisesRegex(ValueError, 'OUTPUT_ALREADY_EXISTS'): package.prepare(self.repo, a)

    def test_dirty_source_and_sql_drift_are_refused(self):
        self.write('server/src/main.ts', 'uncommitted')
        with self.assertRaisesRegex(ValueError, 'COMMIT_SOURCE_FIRST'): package.prepare(self.repo, self.root / 'dirty.tar.gz', 'full')
        self.commit()
        self.write('server/prisma/migrations/' + self.names[0] + '/migration.sql', '-- changed\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'MIGRATION_CHECKSUM_MISMATCH'): package.prepare(self.repo, self.root / 'drift.tar.gz', 'full')

    def test_full_profile_does_not_silently_omit_a_newer_migration(self):
        self.write('server/prisma/migrations/202610300003_future/migration.sql', '-- future\n')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'FULL_RELEASE_BOUNDARY_CHANGED'):
            package.prepare(self.repo, self.root / 'future.tar.gz', 'full')

    def test_website_uses_candidate_metadata_but_leaves_public_template_unchanged(self):
        self.write('script/package-website.py', (HERE / 'package-website.py').read_bytes())
        self.write('web/index.html', '0.5.2-preview.1 · code 9 downloads/v0.5.2-preview.1/WhaleToDo-0.5.2-preview.1.apk APP 账户与在线 AI 尚待接入。 https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.2-preview.1')
        self.write('release/preview-certificate.sha256', 'c'*64)
        self.write('web/app.js', 'fetch("downloads/v0.5.2-preview.1/release.json")')
        self.commit()
        commit = package.git(self.repo, 'rev-parse', 'HEAD').decode().strip()
        artifacts = self.root / 'artifacts'
        artifacts.mkdir()
        apk = b'synthetic non-distributable fixture'
        version, asset = '0.5.3-preview.1', 'WhaleToDo-0.5.3-preview.1.apk'
        meta = {'version': version, 'version_code': 10, 'tag': 'v' + version, 'package': 'io.whaletodo.app.preview',
                'asset': asset, 'sha256': hashlib.sha256(apk).hexdigest(), 'source_commit': commit,
                'online_account_agent': 'client_integrated_production_acceptance_pending', 'certificate_sha256': 'c'*64}
        (artifacts / asset).write_bytes(apk)
        for name, text in [('release.json', json.dumps(meta)), ('SHA256SUMS.txt', meta['sha256']), ('RELEASE-NOTES.md', 'synthetic')]:
            (artifacts / name).write_text(text, encoding='utf-8')
        subprocess.run([sys.executable, str(self.repo / 'script/package-website.py'), str(artifacts)], check=True, stdout=subprocess.DEVNULL)
        with zipfile.ZipFile(artifacts / ('WhaleToDo-website-v' + version + '.zip')) as archive:
            html = archive.read('index.html').decode()
            self.assertIn('0.5.3-preview.1 · code 10', html)
            self.assertIn('https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.2-preview.1', html)
            self.assertNotIn('https://github.com/bottledkzk/WhaleToDo/releases/tag/v0.5.3-preview.1', html)
            self.assertIn('生产联调尚待验收', html)
            self.assertIn('downloads/v' + version + '/' + asset, archive.namelist())
        self.assertIn('0.5.2-preview.1', (self.repo / 'web/index.html').read_text(encoding='utf-8'))
        meta['certificate_sha256'] = 'd'*64
        (artifacts / 'release.json').write_text(json.dumps(meta), encoding='utf-8')
        bad = subprocess.run([sys.executable, str(self.repo / 'script/package-website.py'), str(artifacts)], capture_output=True)
        self.assertNotEqual(bad.returncode, 0)
        self.assertIn(b'PREVIEW_CERTIFICATE_MISMATCH', bad.stderr)


if __name__ == '__main__': unittest.main()
