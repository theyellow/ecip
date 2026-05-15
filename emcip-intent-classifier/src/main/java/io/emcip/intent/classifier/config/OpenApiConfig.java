package io.emcip.intent.classifier.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Intent Classifier API",
                        description = "Rule-based intent classification for Telegram messages",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
