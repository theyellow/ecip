package io.emcip.conversation.context;

import lombok.extern.slf4j.Slf4j;
import org.testcontainers.DockerClientFactory;

/**
 * Checks if Docker is available for Testcontainers.
 */
@Slf4j
public class DockerAvailabilityChecker {

    /**
     * Checks if Docker is available and accessible.
     *
     * @return true if Docker is available, false otherwise
     */
    public static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            log.debug("Docker is available");
            return true;
        } catch (Exception e) {
            log.warn("Docker is not available: {}", e.getMessage());
            return false;
        }
    }
}
