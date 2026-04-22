package io.emcip.admin.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            ServiceTokenAuthenticationFilter serviceTokenFilter) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(HttpMethod.POST, "/api/auth/token")
                                        .permitAll()
                                        .pathMatchers("/actuator/**")
                                        .permitAll()
                                        .pathMatchers("/api/policy-rules/**")
                                        .permitAll()
                                        .anyExchange()
                                        .authenticated())
                .addFilterAt(serviceTokenFilter, SecurityWebFiltersOrder.HTTP_BASIC)
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
