#!/bin/bash
# This tool can only CREATE and later remove its own randomly named scratch database.
set -euo pipefail
umask 077
fail(){ printf '%s\n' "$1" >&2; exit 1; }
[[ $EUID == 0 ]] || fail ROOT_REQUIRED
[[ $# -le 1 ]] || fail ONE_ARCHIVE_EXPECTED
unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSERVICE PGSERVICEFILE PGOPTIONS
archive=${1:-}
if [[ -z $archive ]]; then
 archive=$(python3 - <<'PY'
import glob,json,os,re
candidates=[]
for p in glob.glob('/var/backups/whaletodo/whaletodo-*.dump'):
 if not re.fullmatch(r'whaletodo-[0-9TZ]+-[0-9]+\.dump',os.path.basename(p)): continue
 try:
  if json.load(open(p+'.json'))['database']=='whaletodo': candidates.append(p)
 except (OSError,ValueError,KeyError): continue
if not candidates: raise SystemExit('NO_PRODUCTION_BACKUP_WITH_MANIFEST')
print(max(candidates))
PY
 )
fi
[[ $archive =~ ^/var/backups/whaletodo/whaletodo-[0-9TZ]+-[0-9]+\.dump$ ]] || fail ARCHIVE_PATH_REFUSED
[[ -f $archive && ! -L $archive && $(realpath "$archive") == "$archive" && $(stat -c %u "$archive") == 0 ]] || fail ARCHIVE_OWNER_REFUSED
[[ $(stat -c %a "$archive") == 600 ]] || fail ARCHIVE_PERMISSIONS_REFUSED
python3 - "$archive" <<'PY'
import hashlib,json,sys
p=sys.argv[1]
m=json.load(open(p+'.json'))
with open(p,'rb') as f: digest=hashlib.file_digest(f,'sha256').hexdigest()
if m['sha256']!=digest: raise SystemExit('ARCHIVE_CHECKSUM_MISMATCH')
PY
exec 9>/run/lock/whaletodo-restore-check.lock
flock -n 9 || fail RESTORE_CHECK_ALREADY_RUNNING
target=whaletodo_restore_$(date -u +%Y%m%d%H%M%S)_$$_$RANDOM
[[ $target =~ ^whaletodo_restore_[0-9_]+$ ]] || fail RESTORE_TARGET_REFUSED
created=0
cleanup(){ if [[ $created == 1 ]]; then runuser -u postgres -- dropdb --if-exists -- "$target"; fi; }
trap cleanup EXIT
# createdb fails if the name exists. Never use --clean, --create, --force or production as a target.
runuser -u postgres -- createdb --template=template0 --owner=whaletodo_migrator -- "$target"
created=1
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 --dbname="$target" -c "REVOKE CONNECT ON DATABASE $target FROM PUBLIC" >/dev/null
if ! runuser -u postgres -- pg_restore --no-owner --no-acl --single-transaction --exit-on-error --role=whaletodo_migrator --dbname="$target" < "$archive" > /dev/null; then fail RESTORE_FAILED; fi
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 --dbname="$target" <<'SQL'
SET search_path=whaletodo,pg_catalog;
DO $$ BEGIN
 IF (SELECT count(*) FROM users)>10 THEN RAISE EXCEPTION 'RESTORE_CAPACITY_INVALID'; END IF;
 IF EXISTS(SELECT 1 FROM point_wallets WHERE available<0 OR reserved<0) THEN RAISE EXCEPTION 'RESTORE_WALLET_INVALID'; END IF;
 IF EXISTS(SELECT 1 FROM _prisma_migrations WHERE finished_at IS NULL AND rolled_back_at IS NULL) THEN RAISE EXCEPTION 'RESTORE_MIGRATION_INCOMPLETE'; END IF;
 IF EXISTS(SELECT 1 FROM pg_constraint WHERE connamespace='whaletodo'::regnamespace AND NOT convalidated) THEN RAISE EXCEPTION 'RESTORE_CONSTRAINT_UNVALIDATED'; END IF;
END $$;
SELECT 'RESTORE_TABLES',count(*) FROM pg_tables WHERE schemaname='whaletodo';
SELECT 'RESTORE_USERS',count(*) FROM users;
SELECT 'RESTORE_MIGRATIONS',count(*) FROM _prisma_migrations WHERE finished_at IS NOT NULL AND rolled_back_at IS NULL;
SQL
cleanup
created=0
python3 - "$archive" <<'PY'
import datetime,json,sys
p=sys.argv[1];m=json.load(open(p+'.json'))
with open(p+'.restore-ok.json','w') as f:
 json.dump(dict(archive_sha256=m['sha256'],checked_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
 result='restored-transactionally-and-validated',scratch_database_removed=True),f)
PY
printf 'RESTORE_CHECK_OK %s\n' "$(basename "$archive")"
