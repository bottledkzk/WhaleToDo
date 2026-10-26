#!/bin/bash
set -euo pipefail
umask 077
fail(){ printf '%s\n' "$1" >&2; exit 1; }
[[ $EUID == 0 ]] || fail ROOT_REQUIRED
[[ $# == 0 ]] || fail NO_ARGUMENTS_EXPECTED
db=${BACKUP_DATABASE:-whaletodo}
[[ $db == whaletodo || $db =~ ^whaletodo_ops_[a-z0-9_]{1,40}$ ]] || fail DATABASE_REFUSED
unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSERVICE PGSERVICEFILE PGOPTIONS
install -d -m 700 /var/backups/whaletodo
[[ ! -L /var/backups/whaletodo && $(stat -c %u /var/backups/whaletodo) == 0 ]] || fail BACKUP_DIRECTORY_REFUSED
exec 9>/run/lock/whaletodo-backup.lock
flock -n 9 || fail BACKUP_ALREADY_RUNNING
free=$(df -Pk /var/backups/whaletodo | awk 'NR==2 {print $4}')
[[ $free =~ ^[0-9]+$ && $free -ge 102400 ]] || fail BACKUP_DISK_LOW
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target=/var/backups/whaletodo/whaletodo-$stamp-$$.dump
trap 'rm -f -- "$target.partial" "$target.json.partial" "$target.sha256.partial" "$target.error"' EXIT
if ! runuser -u postgres -- pg_dump --host=/var/run/postgresql --format=custom --no-owner --no-acl --lock-wait-timeout=5s --dbname="$db" > "$target.partial" 2> "$target.error"; then fail BACKUP_DUMP_FAILED; fi
[[ -s $target.partial ]] || fail BACKUP_EMPTY
runuser -u postgres -- pg_restore --list < "$target.partial" > /dev/null 2> "$target.error" || fail BACKUP_ARCHIVE_INVALID
sha=$(sha256sum "$target.partial" | cut -d' ' -f1)
version=$(runuser -u postgres -- psql -XAt --dbname="$db" -c 'SHOW server_version_num')
python3 - "$db" "$sha" "$version" "$target.partial" > "$target.json.partial" <<'PY'
import datetime,json,os,sys
print(json.dumps(dict(database=sys.argv[1],sha256=sys.argv[2],postgres_version_num=int(sys.argv[3]),
 bytes=os.path.getsize(sys.argv[4]),created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(),
 format='pg_dump-custom',encrypted=False,includes_roles=False,includes_external_secrets=False)))
PY
printf '%s  %s\n' "$sha" "$(basename "$target")" > "$target.sha256.partial"
# Publish metadata before archive. Interrupted partial files never count as a completed dump.
mv -- "$target.json.partial" "$target.json"
mv -- "$target.sha256.partial" "$target.sha256"
mv -- "$target.partial" "$target"
printf 'BACKUP_OK %s\n' "$target"
