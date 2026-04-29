package io.emcip.policy.engine;

import io.emcip.policy.engine.config.PolicyEngineRuntimeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.policy.engine.repository")
@ImportRuntimeHints(PolicyEngineRuntimeHints.class)
public class PolicyEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyEngineApplication.class, args);
    }
}
