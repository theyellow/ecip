package io.emcip.admin.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "EMCIP Admin API",
                        description =
                                "Administrative endpoints for rules, tenants, group profiles, and"
                                        + " Telegram accounts",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
