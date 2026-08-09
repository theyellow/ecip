package io.emcip.llm.orchestrator.config;

import io.emcip.common.crypto.PlaintextSecretException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns "this secret was never encrypted" into an answer an operator can act on.
 *
 * <p>Without this the condition surfaces as a bare 500: the exception is raised inside Hibernate's
 * attribute converter, which wraps it in a {@link JpaSystemException} whose message is the
 * unhelpful "Error attempting to apply AttributeConverter". Matching on the declared type alone is
 * therefore not enough - the cause chain has to be walked.
 *
 * <p>The response mirrors admin-api's handler for the same condition (409 with {@code
 * SECRET_NOT_ENCRYPTED}) so a caller sees one contract regardless of which service noticed. The
 * detail deliberately names no table, column, or runbook path: the location goes to the log, where
 * operators can see it and clients cannot.
 */
@RestControllerAdvice
@Slf4j
public class SecretExceptionHandler {

    @ExceptionHandler({PlaintextSecretException.class, JpaSystemException.class})
    public ResponseEntity<ProblemDetail> handlePlaintextSecret(RuntimeException ex) {
        PlaintextSecretException plaintext = findPlaintextSecretCause(ex);
        if (plaintext == null) {
            // Some other JPA failure: not ours to reinterpret as a secrets problem.
            throw ex;
        }
        log.error("Unencrypted secret encountered at {}", plaintext.getLocation(), plaintext);

        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.CONFLICT,
                        "This value was stored before secrets encryption was enabled. Re-enter the"
                                + " LLM provider API key to secure it.");
        problem.setTitle("Secret not encrypted");
        problem.setProperty("code", "SECRET_NOT_ENCRYPTED");
        problem.setProperty("field", "the LLM provider API key");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    private static PlaintextSecretException findPlaintextSecretCause(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof PlaintextSecretException p) {
                return p;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return null;
    }
}
