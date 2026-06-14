--liquibase formatted sql

--changeset knowledge-engine:ke-7 failOnError:false
--comment: Create the Apache AGE knowledge graph (skipped if AGE not installed)
SELECT ag_catalog.create_graph('knowledge_graph');
