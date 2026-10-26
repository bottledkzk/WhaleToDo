#!/bin/bash
set -euo pipefail
umask 077
[[ $EUID == 0 && $# == 0 ]] || exit 1
unset PGHOST PGPORT PGDATABASE PGUSER PGPASSWORD PGSERVICE PGSERVICEFILE PGOPTIONS
exec 9>/run/lock/whaletodo-maintenance.lock
flock -n 9 || exit 0
root=$(cd -- "$(dirname -- "$0")/.." && pwd)
runuser -u postgres -- psql -X -v ON_ERROR_STOP=1 --dbname=whaletodo < "$root/env/postgresql/maintenance.sql"
