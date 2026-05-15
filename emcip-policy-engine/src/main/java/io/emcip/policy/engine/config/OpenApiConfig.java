package io.emcip.policy.engine.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Policy Engine API",
                        description = "Deterministic policy evaluation and rule management",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
