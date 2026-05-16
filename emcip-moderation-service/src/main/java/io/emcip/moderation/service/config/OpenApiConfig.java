package io.emcip.moderation.service.config;

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
                        title = "Moderation Service API",
                        description = "Moderation rule management and toxicity filtering",
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
