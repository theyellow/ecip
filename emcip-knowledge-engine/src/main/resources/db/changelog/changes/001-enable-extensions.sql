--liquibase formatted sql

--changeset knowledge-engine:1
--comment: Enable pgvector and Apache AGE extensions
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS age;
LOAD 'age';
