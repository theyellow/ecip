package io.emcip.conversation.context;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.conversation.context.repository")
public class ConversationContextApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConversationContextApplication.class, args);
    }
}
