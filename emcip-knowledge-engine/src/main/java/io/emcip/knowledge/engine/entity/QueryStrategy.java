package io.emcip.knowledge.engine.entity;

public enum QueryStrategy {
    /** "What do we know about X?" — graph traversal from topic node + vector search */
    TOPIC_EXPLORATION,
    /** "What does Person X discuss?" — graph edges from person node + authored messages */
    PERSON_ANALYSIS,
    /** "Who holds what position on X?" — persons connected to topic */
    OPINION_MAPPING,
    /** "How do opinions differ between groups?" — scoped graph queries */
    COMPARISON,
    /** "Is claim X supported?" — search factual knowledge + compare with community */
    FACT_VERIFICATION
}
