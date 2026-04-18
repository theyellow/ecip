package io.emcip.policy.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "io.emcip.policy.engine.repository")
public class PolicyEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolicyEngineApplication.class, args);
    }
}
