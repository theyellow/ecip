package io.emcip.admin.api.security;

import io.emcip.admin.api.audit.AdminAuditPublisher;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AdminAuditPublisher auditPublisher;

    @Value("${admin.cors.allowed-origins:http://localhost:14009}")
    private List<String> allowedOrigins;

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            JwtAuthenticationFilter jwtFilter,
            ServiceTokenAuthenticationFilter serviceTokenFilter,
            AdminTenantContextFilter adminTenantContextFilter) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(corsSpec -> corsSpec.configurationSource(corsConfigurationSource()))
                .headers(
                        headers ->
                                headers.contentSecurityPolicy(
                                                csp ->
                                                        csp.policyDirectives(
                                                                "default-src 'self'; script-src"
                                                                    + " 'self'; style-src 'self'"
                                                                    + " 'unsafe-inline'; img-src"
                                                                    + " 'self' data:; font-src"
                                                                    + " 'self'"))
                                        .frameOptions(
                                                frame ->
                                                        frame.mode(
                                                                XFrameOptionsServerHttpHeadersWriter
                                                                        .Mode.DENY))
                                        .hsts(
                                                hsts ->
                                                        hsts.includeSubdomains(true)
                                                                .maxAge(
                                                                        java.time.Duration.ofDays(
                                                                                365)))
                                        .contentTypeOptions(contentType -> {}))
                .authorizeExchange(
                        auth ->
                                auth.pathMatchers(
                                                HttpMethod.POST,
                                                "/api/auth/token",
                                                "/auth/token",
                                                "/api/auth/refresh",
                                                "/api/auth/logout")
                                        .permitAll()
                                        .pathMatchers("/actuator/health", "/actuator/health/**")
                                        .permitAll()
                                        .pathMatchers(
                                                "/actuator/prometheus",
                                                "/actuator/info",
                                                "/actuator/metrics",
                                                "/actuator/metrics/**")
                                        .permitAll()
                                        .pathMatchers("/api/internal/**")
                                        .hasRole("SERVICE")
                                        .anyExchange()
                                        .authenticated())
                .exceptionHandling(
                        ex ->
                                ex.accessDeniedHandler(
                                        (exchange, denied) -> {
                                            String path = exchange.getRequest().getPath().value();
                                            return exchange.getPrincipal()
                                                    .map(p -> p.getName())
                                                    .defaultIfEmpty("anonymous")
                                                    .flatMap(
                                                            actor -> {
                                                                auditPublisher.publish(
                                                                        "ACCESS_DENIED",
                                                                        "Endpoint",
                                                                        path,
                                                                        actor,
                                                                        null,
                                                                        Map.of(
                                                                                "reason",
                                                                                denied.getMessage()
                                                                                                != null
                                                                                        ? denied
                                                                                                .getMessage()
                                                                                        : "Access"
                                                                                              + " denied"));
                                                                return Mono.<Void>error(denied);
                                                            });
                                        }))
                .addFilterAt(serviceTokenFilter, SecurityWebFiltersOrder.HTTP_BASIC)
                .addFilterAt(jwtFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .addFilterAfter(adminTenantContextFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }

    @Bean
    public AdminTenantContextFilter adminTenantContextFilter() {
        return new AdminTenantContextFilter();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Tenant-Id"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
