package io.emcip.adminui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// No UserDetailsService is configured anywhere (the security chain permits all requests and
// exists only to emit response headers), so without this exclusion Spring Boot generates and
// logs a random password on every startup. That noise is undesirable here.
@SpringBootApplication(exclude = {UserDetailsServiceAutoConfiguration.class})
public class AdminUiApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdminUiApplication.class, args);
    }
}
