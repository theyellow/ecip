package io.emcip.admin.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(
                    org.mockito.Mockito.mock(io.emcip.admin.api.audit.AdminAuditPublisher.class));

    @Test
    void handleResponseStatus_returnsCorrectStatusAndDetail() {
        ResponseStatusException ex =
                new ResponseStatusException(HttpStatus.NOT_FOUND, "resource not found");

        ResponseEntity<ProblemDetail> result = handler.handleResponseStatus(ex).block();

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDetail()).isEqualTo("resource not found");
    }

    @Test
    void handleIllegalArgument_returns400WithDetail() {
        IllegalArgumentException ex = new IllegalArgumentException("invalid input value");

        ResponseEntity<ProblemDetail> result = handler.handleIllegalArgument(ex).block();

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDetail()).isEqualTo("invalid input value");
    }

    @Test
    void handleGeneric_returns500WithGenericMessage() {
        Exception ex = new RuntimeException("secret internal details");

        ResponseEntity<ProblemDetail> result = handler.handleGeneric(ex).block();

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDetail()).isEqualTo("An unexpected error occurred");
        // Real error message must not be leaked to the client
        assertThat(result.getBody().getDetail()).doesNotContain("secret internal details");
    }

    @Test
    void callNotPermitted_returns503() {
        CircuitBreaker cb = CircuitBreaker.ofDefaults("test");
        CallNotPermittedException ex =
                CallNotPermittedException.createCallNotPermittedException(cb);

        ResponseEntity<ProblemDetail> result = handler.handleCircuitOpen(ex).block();

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).isNotNull();
        assertThat(result.getBody().getDetail()).isEqualTo("Service temporarily unavailable");
    }

    @Test
    void handleValidation_returns400WithFieldErrors() {
        @org.springframework.validation.annotation.Validated
        @org.springframework.web.bind.annotation.RestController
        class TestController {
            @org.springframework.web.bind.annotation.PostMapping("/test-validation")
            public reactor.core.publisher.Mono<String> handle(
                    @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                            TestRequest body) {
                return reactor.core.publisher.Mono.just("ok");
            }

            public record TestRequest(@jakarta.validation.constraints.NotBlank String value) {}
        }

        org.springframework.test.web.reactive.server.WebTestClient client =
                org.springframework.test.web.reactive.server.WebTestClient.bindToController(
                                new TestController())
                        .controllerAdvice(
                                new GlobalExceptionHandler(
                                        org.mockito.Mockito.mock(
                                                io.emcip.admin.api.audit.AdminAuditPublisher
                                                        .class)))
                        .build();

        client.post()
                .uri("/test-validation")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("value", ""))
                .exchange()
                .expectStatus()
                .isBadRequest()
                .expectBody()
                .jsonPath("$.status")
                .isEqualTo(400)
                .jsonPath("$.errors.value")
                .isNotEmpty();
    }
}
