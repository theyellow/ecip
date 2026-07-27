-- docker/postgres-knowledge/init-extensions.sql
-- Executed once when the database is first created

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;

-- AGE requires these settings for cypher() to work.
-- Apply to whichever database this image was initialized with (POSTGRES_DB) rather than a
-- hardcoded name, so the image works for any database (e.g. test containers use a different name).
DO $do$
BEGIN
    EXECUTE format(
        'ALTER DATABASE %I SET search_path = ag_catalog, "$user", public',
        current_database());
END
$do$;

-- Load AGE into shared_preload_libraries is handled by postgresql.conf
-- For the init script, we load it in the current session
LOAD 'age';
