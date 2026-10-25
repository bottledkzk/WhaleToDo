-- A real SQL default lets Prisma omit demo_slot while the database serializes allocation.
CREATE FUNCTION whaletodo.next_demo_slot() RETURNS integer LANGUAGE plpgsql
 SET search_path = whaletodo, pg_temp AS $$
DECLARE slot integer;
BEGIN
 PERFORM pg_advisory_xact_lock(27102310);
 SELECT s INTO slot FROM generate_series(1,10) AS s
 WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.demo_slot=s) ORDER BY s LIMIT 1;
 IF slot IS NULL THEN RAISE EXCEPTION 'DEMO_USER_LIMIT' USING ERRCODE='23514'; END IF;
 RETURN slot;
END $$;
ALTER TABLE users ALTER COLUMN demo_slot SET DEFAULT whaletodo.next_demo_slot();
