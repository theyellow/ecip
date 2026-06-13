--liquibase formatted sql

--changeset knowledge-engine:ke-7
--comment: Create the Apache AGE knowledge graph
SELECT ag_catalog.create_graph('knowledge_graph');
