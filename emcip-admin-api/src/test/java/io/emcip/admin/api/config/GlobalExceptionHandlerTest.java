package io.emcip.admin.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

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
}
