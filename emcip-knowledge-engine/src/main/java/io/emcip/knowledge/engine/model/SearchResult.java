package io.emcip.knowledge.engine.model;

public record SearchResult<T>(T item, double score) {}
