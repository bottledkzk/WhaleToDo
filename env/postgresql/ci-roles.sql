-- Only for the disposable CI container. Never use these synthetic passwords on a real server.
CREATE ROLE whaletodo_migrator LOGIN PASSWORD 'ci-migration-only' NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 2;
CREATE ROLE whaletodo_app LOGIN PASSWORD 'ci-runtime-only' NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 6;
CREATE ROLE whaletodo_ai LOGIN PASSWORD 'ci-ai-only' NOSUPERUSER NOCREATEDB NOCREATEROLE CONNECTION LIMIT 3;
CREATE SCHEMA whaletodo AUTHORIZATION whaletodo_migrator;
ALTER ROLE whaletodo_migrator SET search_path=whaletodo;
ALTER ROLE whaletodo_app SET search_path=whaletodo;
ALTER ROLE whaletodo_ai SET search_path=whaletodo;
GRANT USAGE ON SCHEMA whaletodo TO whaletodo_app,whaletodo_ai;
