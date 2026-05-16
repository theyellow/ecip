package io.emcip.moderation.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Moderation Service API",
                        description = "Moderation rule management and toxicity filtering",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
