package io.emcip.common.crypto;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the startup self-check. Imported explicitly by the three services that store secrets,
 * alongside {@link SecretCipherConfig}.
 *
 * <p>Like the rest of {@code io.emcip.common.crypto} this sits outside every service's
 * component-scan base package, so a service that stores no secrets picks up nothing — the same
 * isolation property that lets those services run without an {@code EMCIP_SECRET_KEY}.
 *
 * <p>Each service supplies its own {@code List<SecretColumn>} bean.
 */
@Configuration
@EnableConfigurationProperties(SecretsSelfCheckProperties.class)
public class SecretsSelfCheckConfig {

    @Bean
    public SecretColumnScanner secretColumnScanner(DataSource dataSource, SecretCipher cipher) {
        return new SecretColumnScanner(dataSource, cipher);
    }

    @Bean
    public SecretsSelfCheck secretsSelfCheck(
            SecretColumnScanner scanner,
            List<SecretColumn> columns,
            SecretsSelfCheckProperties properties) {
        return new SecretsSelfCheck(scanner, columns, properties);
    }

    @Bean
    public SecretsMetrics secretsMetrics(
            MeterRegistry registry, SecretsSelfCheck selfCheck, List<SecretColumn> columns) {
        return new SecretsMetrics(registry, selfCheck, columns);
    }
}
