-- docker/postgres-knowledge/init-extensions.sql
-- Executed once when the database is first created

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;

-- AGE requires these settings for cypher() to work
ALTER DATABASE emcip SET search_path = ag_catalog, "$user", public;

-- Load AGE into shared_preload_libraries is handled by postgresql.conf
-- For the init script, we load it in the current session
LOAD 'age';
