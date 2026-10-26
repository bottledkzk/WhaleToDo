#!/bin/bash
# Local administrator only. Separate data directory/socket/port, never shared whaletodo_verify.
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 0 ]] || { echo ROOT_WITH_NO_ARGUMENTS_REQUIRED >&2; exit 1; }
unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSERVICE PGSERVICEFILE PGOPTIONS DATABASE_URL VERIFY_DATABASE_URL VERIFY_APP_URL VERIFY_AI_URL
exec 9>/run/lock/whaletodo-pgtest.lock
flock -n 9 || { echo TEST_ALREADY_RUNNING >&2; exit 1; }
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
bin=/usr/lib/postgresql/16/bin
port=25432
if ss -lnt | grep -qE '[:.]25432[[:space:]]'; then echo TEST_PORT_BUSY >&2; exit 1; fi
tmp=$(mktemp -d /var/tmp/whaletodo-pgtest.XXXXXXXX)
[[ $tmp == /var/tmp/whaletodo-pgtest.* && $(realpath "$tmp") == "$tmp" && ! -L $tmp ]] || exit 1
chown postgres:postgres "$tmp"
started=0
cleanup(){
 if [[ $started == 1 ]]; then runuser -u postgres -- "$bin/pg_ctl" -D "$tmp/data" -m fast -w stop >/dev/null; fi
 # Only the exact fresh mktemp directory from this invocation.
 if [[ $tmp == /var/tmp/whaletodo-pgtest.* && $(realpath "$tmp") == "$tmp" && ! -L $tmp ]]; then rm -rf -- "$tmp"; fi
}
trap cleanup EXIT
runuser -u postgres -- "$bin/initdb" -D "$tmp/data" --auth-local=peer --auth-host=scram-sha-256 --no-locale --encoding=UTF8 > /dev/null
runuser -u postgres -- "$bin/pg_ctl" -D "$tmp/data" -l "$tmp/postgres.log" -o "-p $port -k $tmp -h 127.0.0.1 -c shared_buffers=16MB -c max_connections=20 -c work_mem=1MB -c fsync=on" -w start > /dev/null
started=1
runuser -u postgres -- "$bin/createdb" -h "$tmp" -p "$port" whaletodo_verify
runuser -u postgres -- "$bin/psql" -X -h "$tmp" -p "$port" -d whaletodo_verify -v ON_ERROR_STOP=1 < "$root/env/postgresql/ci-roles.sql" > /dev/null
export DATABASE_URL="postgresql://whaletodo_migrator:ci-migration-only@127.0.0.1:$port/whaletodo_verify?schema=whaletodo"
export VERIFY_DATABASE_URL=$DATABASE_URL
export VERIFY_APP_URL="postgresql://whaletodo_app:ci-runtime-only@127.0.0.1:$port/whaletodo_verify"
export VERIFY_AI_URL="postgresql://whaletodo_ai:ci-ai-only@127.0.0.1:$port/whaletodo_verify"
cd "$root/server"
node node_modules/prisma/build/index.js migrate deploy
runuser -u postgres -- "$bin/psql" -X -h "$tmp" -p "$port" -d whaletodo_verify -v ON_ERROR_STOP=1 < "$root/env/postgresql/grants.sql" > /dev/null
node tools/database-guard.ts plan whaletodo_verify
node --test --test-concurrency=1 integration/*.test.ts
printf 'ISOLATED_DATABASE_TEST_OK\n'
