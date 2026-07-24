package io.emcip.knowledge.engine.config;

import io.emcip.common.crypto.SecretCipherConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Activates the shared {@code SecretCipher} in knowledge-engine.
 *
 * <p>emcip-core sits outside this service's component-scan base package, so the cipher must be
 * imported explicitly. Importing it makes {@code EMCIP_SECRET_KEY} mandatory for this service.
 */
@Configuration
@Import(SecretCipherConfig.class)
public class CryptoConfig {}
