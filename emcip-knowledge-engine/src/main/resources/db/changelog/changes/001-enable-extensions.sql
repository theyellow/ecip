--liquibase formatted sql

--changeset knowledge-engine:1
--comment: Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

--changeset knowledge-engine:1b failOnError:false
--comment: Enable Apache AGE extension (optional — not available in all environments)
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
