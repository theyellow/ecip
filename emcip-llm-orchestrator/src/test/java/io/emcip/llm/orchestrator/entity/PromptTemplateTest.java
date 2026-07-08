package io.emcip.llm.orchestrator.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PromptTemplateTest {

    @Test
    void systemDefaultsToFalse() {
        PromptTemplate template = new PromptTemplate();
        assertThat(template.getSystem()).isFalse();
    }

    @Test
    void temperatureCanBeNull() {
        PromptTemplate template = new PromptTemplate();
        template.setTemperature(null);
        assertThat(template.getTemperature()).isNull();
    }

    @Test
    void modelConfigCanBeNull() {
        PromptTemplate template = new PromptTemplate();
        assertThat(template.getModelConfig()).isNull();
    }

    @Test
    void modelConfigCanBeSet() {
        PromptTemplate template = new PromptTemplate();
        ModelConfig model = new ModelConfig();
        model.setModelName("qwen3-30b-a3b");
        template.setModelConfig(model);
        assertThat(template.getModelConfig().getModelName()).isEqualTo("qwen3-30b-a3b");
    }
}
