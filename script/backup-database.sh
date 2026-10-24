#!/bin/sh
set -eu
umask 077
install -d -m 700 /var/backups/whaletodo
stamp=$(date -u +%Y%m%dT%H%M%SZ)
target=/var/backups/whaletodo/whaletodo-$stamp.dump
runuser -u postgres -- pg_dump --format=custom --no-owner --no-acl whaletodo > "$target.tmp"
mv -- "$target.tmp" "$target"
sha256sum "$target" > "$target.sha256"
printf 'Backup completed: %s\n' "$target"
