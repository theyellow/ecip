package io.emcip.llm.orchestrator.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "LLM Orchestrator API",
                        description = "Model configuration, prompt templates, and cost tracking",
                        version = "1.0"),
        security = @SecurityRequirement(name = "serviceToken"))
@SecurityScheme(
        name = "serviceToken",
        type = SecuritySchemeType.APIKEY,
        in = SecuritySchemeIn.HEADER,
        paramName = "X-Service-Token",
        description = "Internal service token for service-to-service authentication")
@Configuration
public class OpenApiConfig {}
