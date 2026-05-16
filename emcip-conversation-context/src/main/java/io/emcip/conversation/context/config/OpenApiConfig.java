package io.emcip.conversation.context.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@OpenAPIDefinition(
        info =
                @Info(
                        title = "Conversation Context API",
                        description = "Thread tracking, speaker roles, and conversation history",
                        version = "1.0"))
@Configuration
public class OpenApiConfig {}
