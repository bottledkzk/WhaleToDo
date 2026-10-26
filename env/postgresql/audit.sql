-- Read-only posture report. No row values, usernames, addresses, tokens, or URL output.
SELECT current_setting('server_version_num') AS postgres_version,
 current_setting('listen_addresses') AS listen_addresses,
 current_setting('max_connections') AS connection_limit,
 pg_size_pretty(pg_database_size(current_database())) AS size;
SELECT rolname,rolsuper,rolcreaterole,rolcreatedb,rolbypassrls,rolconnlimit
 FROM pg_roles WHERE rolname IN ('whaletodo_app','whaletodo_ai','whaletodo_migrator');
SELECT tablename,rowsecurity FROM pg_tables WHERE schemaname='whaletodo' ORDER BY tablename;
SELECT migration_name,checksum,finished_at IS NOT NULL AS complete,rolled_back_at IS NOT NULL AS rolled_back
 FROM whaletodo._prisma_migrations ORDER BY started_at;
SELECT count(*) AS users FROM whaletodo.users;
SELECT datname,count(*) AS connections,count(*) FILTER(WHERE state='active') AS active
 FROM pg_stat_activity WHERE datname=current_database() GROUP BY datname;
