package io.emcip.audit.service.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Audit Service API",
                        description = "Audit event querying and pipeline metrics",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
