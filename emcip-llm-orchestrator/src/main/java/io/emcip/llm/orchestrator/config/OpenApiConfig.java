package io.emcip.llm.orchestrator.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "LLM Orchestrator API",
                        description = "Model configuration, prompt templates, and cost tracking",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
